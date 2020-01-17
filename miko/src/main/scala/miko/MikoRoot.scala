package miko

import ackcord.cachehandlers.CacheTypeRegistry
import ackcord.data.VGuildChannel
import ackcord.gateway.{GatewayEvent, GatewaySettings}
import ackcord.commands.CommandConnector
import ackcord.requests.{BotAuthentication, Ratelimiter, Requests}
import ackcord.util.{GuildRouter, Streamable}
import ackcord.{APIMessage, Cache, CacheSnapshot, CacheState, DiscordShard}
import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.actor.typed.scaladsl.AskPattern._
import akka.event.Logging
import akka.http.scaladsl.model.Uri
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import cats.effect.Effect
import com.sedmelluq.discord.lavaplayer.player.{AudioConfiguration, DefaultAudioPlayerManager}
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import doobie.util.transactor.Transactor
import miko.commands.GenericCommands
import miko.db.DBMemoizedAccess
import miko.image.{ImageCache, ImageCommands}
import miko.music.{AudioItemLoader, GuildMusicHandler, MusicCommands}
import miko.settings.GuildSettings
import miko.slaves.SlaveHandler
import miko.util.SGFCPool
import miko.voicetext.VoiceTextStreams
import miko.web.WebEvents
import org.slf4j.Logger
import scalacache.Mode

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

class MikoRoot[F[_]: Transactor: Mode: Streamable: Effect](
    ctx: ActorContext[MikoRoot.Command],
    shutdown: CoordinatedShutdown
)(
    implicit
    webEvents: WebEvents,
    guildSettingsCache: scalacache.Cache[GuildSettings]
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
  implicit val cache: Cache = Cache.create

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
      GatewaySettings(config.token),
      cache,
      Seq(
        classOf[GatewayEvent.PresenceUpdate],
        classOf[GatewayEvent.TypingStart],
        classOf[GatewayEvent.GuildBanAdd],
        classOf[GatewayEvent.GuildBanRemove],
        classOf[GatewayEvent.GuildEmojisUpdate],
        classOf[GatewayEvent.GuildEmojisUpdate]
      ),
      CacheTypeRegistry.noPresencesBansEmoji
    ),
    "DiscordShard"
  )
  val slaveHandler: ActorRef[SlaveHandler.Command] =
    context.spawn(SlaveHandler(cache, wsUri), "SlaveHandler")
  val topMusicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]] =
    initializeMusic()

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

        cache.subscribeAPI
          .collect {
            case APIMessage.GuildCreate(g, _) => Streamable[F].toSource(DBMemoizedAccess.insertEmptySettings[F](g.id))
          }
          .to(Sink.ignore)
          .run()

        val vtStreams = new VoiceTextStreams[F]
        registerCommands(vtStreams)
        runVtStreams(vtStreams)

        Behaviors.same

      case GetCacheStorage(replyTo) =>
        replyTo ! cacheStorage
        Behaviors.same

      case GetRequests(replyTo) =>
        replyTo ! requests
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

  private def registerCommands(vtStreams: VoiceTextStreams[F]): Unit = {
    val connector = new CommandConnector(
      cache.subscribeAPI.collectType[APIMessage.MessageCreate].map(m => m.message -> m.cache.current),
      requests
    )

    def mainPrefix(aliases: String*)  = connector.prefix("!", aliases, mustMention = true)
    def musicPrefix(aliases: String*) = connector.prefix("&", aliases, mustMention = true)

    val genericCommands = new GenericCommands(vtStreams)
    //connector.runNewCommand(mainPrefix("help"), genericCommands.help)
    connector.runNewCommand(mainPrefix("kill", "die"), genericCommands.kill(shutdown))
    connector.runNewCommand(mainPrefix("cleanup"), genericCommands.cleanup)
    connector.runNewCommand(mainPrefix("shiftChannels"), genericCommands.shiftChannels)
    connector.runNewCommand(mainPrefix("genKeys"), genericCommands.genKeys)
    connector.runNewCommand(mainPrefix("info"), genericCommands.info)
    connector.runNewCommand(mainPrefix("debug"), genericCommands.debug)

    val imageCommands = new ImageCommands(requests, imageCache)
    connector.runNewCommand(mainPrefix("safebooru"), imageCommands.safebooru)

    val musicCommands = new MusicCommands(topMusicHandler, requests)
    connector.runNewCommand(musicPrefix("pause", "p"), musicCommands.pause)
    connector.runNewCommand(musicPrefix("volume", "vol"), musicCommands.volume)
    connector.runNewCommand(musicPrefix("defaultVolume", "defvol"), musicCommands.defVolume)
    connector.runNewCommand(musicPrefix("stop", "s"), musicCommands.stop)
    connector.runNewCommand(musicPrefix("nowPlaying", "np"), musicCommands.nowPlaying)
    connector.runNewCommand(musicPrefix("queue", "q"), musicCommands.queue)
    connector.runNewCommand(musicPrefix("next", "n"), musicCommands.next)
    connector.runNewCommand(musicPrefix("prev"), musicCommands.prev)
    connector.runNewCommand(musicPrefix("clear"), musicCommands.clear)
    connector.runNewCommand(musicPrefix("shuffle"), musicCommands.clear)
    connector.runNewCommand(musicPrefix("ytQueue", "ytq"), musicCommands.ytQueue)
    connector.runNewCommand(musicPrefix("scQueue", "scq"), musicCommands.scQueue)
    connector.runNewCommand(musicPrefix("seek"), musicCommands.seek)
    connector.runNewCommand(musicPrefix("progress"), musicCommands.progress)
    connector.runNewCommand(musicPrefix("loop"), musicCommands.loop)
    connector.runNewCommand(musicPrefix("gui"), musicCommands.gui)
  }

  private def runVtStreams(vtStreams: VoiceTextStreams[F]): Unit = {
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
        case APIMessage.ChannelUpdate(vChannel: VGuildChannel, CacheState(current, previous)) =>
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

  def apply[F[_]: Transactor: Mode: Streamable: Effect](shutdown: CoordinatedShutdown)(
      implicit
      webEvents: WebEvents,
      guildSettingsCache: scalacache.Cache[GuildSettings]
  ): Behavior[Command] =
    Behaviors.setup(ctx => new MikoRoot[F](ctx, shutdown))

  sealed trait Command
  private case class PartTerminated(ref: ActorRef[_], replyTo: ActorRef[Done]) extends Command
  private case class StopMusic(replyTo: ActorRef[Done])                        extends Command
  private case class StopShard(replyTo: ActorRef[Done])                        extends Command
  private case object Connect                                                  extends Command

  case class GetCacheStorage(replyTo: ActorRef[ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]]])
      extends Command
  case class GetRequests(replyTo: ActorRef[Requests]) extends Command
}
