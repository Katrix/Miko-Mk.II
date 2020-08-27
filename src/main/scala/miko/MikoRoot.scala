package miko

import ackcord.cachehandlers.CacheTypeRegistry
import ackcord.commands.{CommandConnector, CommandDescription}
import ackcord.data.VoiceGuildChannel
import ackcord.gateway.{GatewayEvent, GatewayIntents, GatewaySettings}
import ackcord.requests.{BotAuthentication, Ratelimiter, Requests}
import ackcord.util.{GuildRouter, Streamable}
import ackcord.{APIMessage, Cache, CacheSnapshot, CacheState, DiscordShard}
import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl._
import akka.event.Logging
import akka.http.scaladsl.model.Uri
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.sedmelluq.discord.lavaplayer.player.{AudioConfiguration, DefaultAudioPlayerManager}
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import miko.commands.{CommandCategory, GenericCommands, MikoCommandComponents, MikoHelpCommand}
import miko.db.DBAccess
import miko.image.{ImageCache, ImageCommands}
import miko.music.{AudioItemLoader, GuildMusicHandler, MusicCommands}
import miko.settings.GuildSettings.Commands.Permissions.CommandPermission
import miko.settings.SettingsAccess
import miko.slaves.SlaveHandler
import miko.util.SGFCPool
import miko.voicetext.VoiceTextStreams
import miko.web.WebEvents
import org.slf4j.Logger
import play.api.ApplicationLoader.DevContext
import play.core.WebCommands
import zio.blocking.Blocking
import zio.{RIO, Task, ZEnv}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

class MikoRoot(
    ctx: ActorContext[MikoRoot.Command],
    shutdown: CoordinatedShutdown,
    devContext: Option[DevContext]
)(
    implicit
    webEvents: WebEvents,
    settings: SettingsAccess,
    db: DBAccess[Task],
    taskStreamable: Streamable[Task],
    blockingStreamable: Streamable[RIO[Blocking, *]],
    runtime: zio.Runtime[ZEnv]
) extends AbstractBehavior[MikoRoot.Command](ctx) {
  import MikoRoot._

  val log: Logger = context.log

  implicit val system: ActorSystem[Nothing] = context.system
  implicit val config: MikoConfig           = MikoConfig()
  implicit val requests: Requests = new Requests(
    BotAuthentication(config.token),
    context.spawn(Ratelimiter(), "Ratelimiter"),
    millisecondPrecision = false,
    relativeTime = true
  )
  implicit val cache: Cache = Cache.create(
    ignoredEvents = Seq(
      classOf[GatewayEvent.PresenceUpdate],
      classOf[GatewayEvent.TypingStart],
      classOf[GatewayEvent.GuildBanAdd],
      classOf[GatewayEvent.GuildBanRemove],
      classOf[GatewayEvent.GuildEmojisUpdate],
      classOf[GatewayEvent.GuildEmojisUpdate]
    ),
    cacheTypeRegistry = CacheTypeRegistry.noPresencesBansEmoji
  )

  implicit val commandComponents: MikoCommandComponents = MikoCommandComponents(requests, config, settings, runtime)

  val cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]] =
    context.spawn(CacheStorage(cache), "CacheStorage")

  //val helpActor: ActorRef  = context.actorOf(MikoHelpCmd.props, "HelpCmdActor")
  val imageCache: ActorRef[ImageCache.Command] = context.spawn(ImageCache(), "ImageCache")

  val wsUri: Uri = try {
    Await.result(DiscordShard.fetchWsGateway, 30.seconds)
  } catch {
    case NonFatal(e) =>
      log.error("Could not connect to Discord", e)
      throw e
  }

  val client: ActorRef[DiscordShard.Command] = context.spawn(
    DiscordShard(
      wsUri,
      GatewaySettings(config.token, intents = GatewayIntents.All),
      cache
    ),
    "DiscordShard"
  )
  val slaveHandler: ActorRef[SlaveHandler.Command] =
    context.spawn(SlaveHandler(cache, wsUri), "SlaveHandler")
  val topMusicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]] =
    initializeMusic()

  val commandConnector = new CommandConnector(
    cache.subscribeAPI.collectType[APIMessage.MessageCreate].map(m => m.message -> m.cache.current),
    requests,
    requests.parallelism
  )

  val helpCommand = new MikoHelpCommand(requests)

  shutdown.addTask("service-requests-done", "stop-music") { () =>
    implicit val timeout: Timeout = shutdown.timeout("service-requests-done")
    context.self.ask[Done](MikoRoot.StopMusic)
  }

  shutdown.addTask("service-stop", "stop-discord") { () =>
    implicit val timeout: Timeout = shutdown.timeout("service-stop")
    context.self.ask[Done](StopShard)
  }

  if (!config.useDummyData) {
    context.self ! Connect
  }

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case Connect =>
        log.info("Miko connecting")
        client ! DiscordShard.StartShard

        val vtStreams = new VoiceTextStreams
        registerCommands(vtStreams)
        runVtStreams(vtStreams)

        Behaviors.same

      case GetCacheStorage(replyTo) =>
        replyTo ! cacheStorage
        Behaviors.same

      case GetRequests(replyTo) =>
        replyTo ! requests
        Behaviors.same

      case GetHelpCommands(replyTo) =>
        replyTo ! helpCommand
        Behaviors.same

      case StopMusic(replyTo) =>
        if (!config.useDummyData) {
          context.watchWith(topMusicHandler, PartTerminated(topMusicHandler, replyTo))
          topMusicHandler ! GuildRouter.Shutdown
        } else {
          replyTo ! Done
        }

        Behaviors.same

      case StopShard(replyTo) =>
        if (!config.useDummyData) {
          context.watchWith(client, PartTerminated(client, replyTo))
          client ! DiscordShard.StopShard
          Behaviors.same
        } else {
          replyTo ! Done
          Behaviors.stopped
        }

      case PartTerminated(ref, replyTo) =>
        replyTo ! Done
        log.info("{} shut down", ref.path)
        Behaviors.same
    }
  }

  private def initializeMusic(): ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]] = {
    val man = new DefaultAudioPlayerManager

    val audioItemLoader = context.spawn(AudioItemLoader(man, 0), "AudioItemLoader")

    shutdown.addTask("music", "StopMusic") { () =>
      Future {
        man.shutdown()
        Done
      }(scala.concurrent.ExecutionContext.global)
    }

    AudioSourceManagers.registerRemoteSources(man)
    man.enableGcMonitoring()
    man.getConfiguration.setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM)

    context.spawn(
      GuildRouter.partitioner(
        None,
        guildId => GuildMusicHandler(guildId, man, cache, slaveHandler, audioItemLoader),
        None,
        GuildRouter.OnShutdownSendMsg(GuildMusicHandler.Shutdown)
      ),
      "MusicHandler"
    )
  }

  private def registerCommands(vtStreams: VoiceTextStreams): Unit = {
    val genericCommands = new GenericCommands(vtStreams, devContext)
    val imageCommands   = new ImageCommands(imageCache)
    val musicCommands   = new MusicCommands(topMusicHandler)

    commandConnector.bulkRunNamedWithHelp(
      helpCommand,
      helpCommand.command
        .toNamed(genericCommands.namedCustomPerm(Seq("help"), CommandCategory.General, CommandPermission.Allow))
        .toDescribed(CommandDescription("Help", "This command right here", extra = CommandCategory.General.extra)),
      genericCommands.kill(shutdown),
      genericCommands.cleanup,
      genericCommands.shiftChannels,
      genericCommands.genKeys,
      genericCommands.info,
      genericCommands.debug,
      genericCommands.eval,
      genericCommands.execute(commandConnector, helpCommand),
      genericCommands.reload,
      imageCommands.safebooru,
      musicCommands.pause,
      musicCommands.volume,
      musicCommands.defVolume,
      musicCommands.stop,
      musicCommands.nowPlaying,
      musicCommands.queue,
      musicCommands.next,
      musicCommands.prev,
      musicCommands.clear,
      musicCommands.shuffle,
      musicCommands.ytQueue,
      musicCommands.scQueue,
      musicCommands.seek,
      musicCommands.progress,
      musicCommands.loop,
      musicCommands.gui
    )
  }

  private def runVtStreams(vtStreams: VoiceTextStreams): Unit = {
    cache.subscribeAPI.via(vtStreams.saveDestructable).to(Sink.ignore).run()

    cache.subscribeAPI
      .collectType[APIMessage.ChannelCreate]
      .map(_.cache.current)
      .to(vtStreams.shiftChannels)
      .run()

    cache.subscribeAPI
      .collect {
        case APIMessage.VoiceStateUpdate(vState, CacheState(current, previous)) if vState.guildId.isDefined =>
          (vState.guildId.get, vState.channelId, vState.userId, current, previous)
      }
      .addAttributes(ActorAttributes.logLevels(Logging.InfoLevel, Logging.InfoLevel, Logging.InfoLevel))
      .to(vtStreams.channelEnterLeave)
      .run()

    cache.subscribeAPI
      .collect {
        case APIMessage.ChannelUpdate(_, vChannel: VoiceGuildChannel, CacheState(current, previous)) =>
          (vChannel, current, previous)
      }
      .to(vtStreams.channelUpdate)
      .run()

    cache.subscribeAPI
      .collect { case APIMessage.VoiceStateUpdate(_, CacheState(current, _)) => current }
      .to(vtStreams.cleanup)
      .run()
  }
}
object MikoRoot {

  def apply(shutdown: CoordinatedShutdown, devContext: Option[DevContext])(
      implicit
      webEvents: WebEvents,
      settings: SettingsAccess,
      db: DBAccess[Task],
      taskStreamable: Streamable[Task],
      blockingStreamable: Streamable[RIO[Blocking, *]],
      runtime: zio.Runtime[ZEnv]
  ): Behavior[Command] =
    Behaviors.setup(ctx => new MikoRoot(ctx, shutdown, devContext))

  sealed trait Command
  private case class PartTerminated(ref: ActorRef[_], replyTo: ActorRef[Done]) extends Command
  private case class StopMusic(replyTo: ActorRef[Done])                        extends Command
  private case class StopShard(replyTo: ActorRef[Done])                        extends Command
  private case object Connect                                                  extends Command

  case class GetCacheStorage(replyTo: ActorRef[ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]]])
      extends Command
  case class GetRequests(replyTo: ActorRef[Requests])            extends Command
  case class GetHelpCommands(replyTo: ActorRef[MikoHelpCommand]) extends Command
}
