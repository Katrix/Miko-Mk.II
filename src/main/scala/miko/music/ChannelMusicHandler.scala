package miko.music

import ackcord.data.raw.RawMessage
import ackcord.data.{ActionRow, GuildId, NormalVoiceGuildChannelId, OutgoingEmbed, UserId}
import ackcord.gateway.{GatewayMessage, VoiceStateUpdate, VoiceStateUpdateData}
import ackcord.lavaplayer.LavaplayerHandler
import ackcord.requests.Requests
import ackcord.{Cache, CacheSnapshot, OptFuture}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.stream.scaladsl.Source
import cats.effect.unsafe.IORuntime
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import miko.instances.{Instance, InstanceHandler}
import miko.settings.SettingsAccess
import miko.web.WebEvents

import java.util.concurrent.ThreadLocalRandom

object ChannelMusicHandler {

  private case class Parameters(
      context: ActorContext[Command],
      stash: StashBuffer[Command],
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: NormalVoiceGuildChannelId,
      topCache: Cache,
      loader: ActorRef[AudioItemLoader.Command],
      handler: ActorRef[GuildMusicHandler.Command],
      initialCacheSnapshot: CacheSnapshot,
      reservationId: Long
  )

  private case class State(
      gotInstanceInfo: Option[GotInstanceInfo] = None,
      instanceUserId: Option[UserId] = None
  )

  private case class GotInstanceInfo(
      instanceCache: Cache,
      awaitingLifetime: ActorRef[Instance.SetLifetime],
      lavaplayerHandler: ActorRef[LavaplayerHandler.Command]
  )

  def apply(
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: NormalVoiceGuildChannelId,
      topCache: Cache,
      loader: ActorRef[AudioItemLoader.Command],
      instanceHandler: ActorRef[InstanceHandler.Command],
      handler: ActorRef[GuildMusicHandler.Command],
      initialCacheSnapshot: CacheSnapshot
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      settings: SettingsAccess,
      IORuntime: IORuntime
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withStash(32) { stash =>
      val reservationId = ThreadLocalRandom.current().nextLong()
      instanceHandler ! InstanceHandler.ReserveInstance(
        reservationId,
        guildId,
        ctx.messageAdapter {
          case InstanceHandler.ReservationFailed(_)                    => FailedToGetInstance
          case InstanceHandler.SendLifetime(_, replyTo, instanceCache) => GotInstance(replyTo, instanceCache)
        }
      )

      initializing(
        Parameters(
          ctx,
          stash,
          player,
          guildId,
          firstVChannelId,
          topCache,
          loader,
          handler,
          initialCacheSnapshot,
          reservationId
        ),
        State()
      )
    }
  }

  private def initializing(
      parameters: Parameters,
      state: State
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      settings: SettingsAccess,
      IORuntime: IORuntime
  ): Behavior[Command] = {
    import parameters._

    def tryStart(state: State) = state match {
      case State(
          Some(GotInstanceInfo(instanceCache, awaitingLifetime, lavaplayerHandler)),
          Some(instanceUserId)
          ) =>
        val controller = context.spawn(
          ChannelMusicController(
            player,
            guildId,
            firstVChannelId,
            initialCacheSnapshot,
            topCache,
            instanceUserId,
            loader,
            handler
          ),
          "ChannelMusicController"
        )
        context.watchWith(controller, ControllerDied)

        awaitingLifetime ! Instance.SetLifetime(reservationId, controller)
        lavaplayerHandler ! LavaplayerHandler.SetPlaying(true)

        stash.unstashAll(running(parameters, controller, lavaplayerHandler, instanceCache))
      case _ => initializing(parameters, state)
    }

    Behaviors.receiveMessage {
      case GotInstance(replyTo, instanceCache) =>
        val lavaplayerHandler = context.spawn(LavaplayerHandler(player, guildId, instanceCache), "LavaplayerHandler")
        lavaplayerHandler ! LavaplayerHandler.ConnectVoiceChannel(
          firstVChannelId,
          force = false,
          context.messageAdapter {
            case LavaplayerHandler.AlreadyConnectedFailure(_, _) => AlreadyConnected
            case LavaplayerHandler.ForcedConnectionFailure(_, _) => sys.error("impossible")
            case LavaplayerHandler.MusicReady(_, userId)         => MusicReady(userId)
          }
        )
        context.watchWith(lavaplayerHandler, LavaplayerDied)

        tryStart(state.copy(gotInstanceInfo = Some(GotInstanceInfo(instanceCache, replyTo, lavaplayerHandler))))

      case MusicReady(userId) =>
        tryStart(state.copy(instanceUserId = Some(userId)))

      case AlreadyConnected =>
        player.destroy()
        handler ! GuildMusicHandler.FailedToStart(GuildMusicHandler.AlreadyConnected, firstVChannelId)
        Behaviors.stopped

      case FailedToGetInstance =>
        player.destroy()
        handler ! GuildMusicHandler.FailedToStart(GuildMusicHandler.NoInstances, firstVChannelId)
        Behaviors.stopped

      case Shutdown =>
        player.destroy()
        Behaviors.stopped

      case cmdWrapper: ChannelMusicCommandWrapper =>
        stash.stash(cmdWrapper)
        Behaviors.same

      case ControllerDied | LavaplayerDied =>
        Behaviors.stopped
    }
  }

  private def sendLeaving(parameters: Parameters, instanceCache: Cache): Unit = {
    implicit val system: ActorSystem[Nothing] = parameters.context.system

    Source
      .single(
        VoiceStateUpdate(VoiceStateUpdateData(parameters.guildId, None, selfMute = false, selfDeaf = false))
          .asInstanceOf[GatewayMessage[Any]]
      )
      .runWith(instanceCache.toGatewayPublish)
  }

  def running(
      parameters: ChannelMusicHandler.Parameters,
      controller: ActorRef[ChannelMusicController.Command],
      lavaplayerHandler: ActorRef[LavaplayerHandler.Command],
      instanceCache: Cache
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case Shutdown =>
      sendLeaving(parameters, instanceCache)
      lavaplayerHandler ! LavaplayerHandler.Shutdown
      controller ! ChannelMusicController.StartShutdown

      deathwatch(parameters.player, lavaplayerDead = false, controllerDead = false)

    case ChannelMusicCommandWrapper(command, sendEmbed, info) =>
      controller ! ChannelMusicController.ChannelMusicCommandWrapper(command, sendEmbed, info)
      Behaviors.same

    case ControllerDied =>
      sendLeaving(parameters, instanceCache)
      lavaplayerHandler ! LavaplayerHandler.Shutdown
      deathwatch(parameters.player, lavaplayerDead = false, controllerDead = true)

    case LavaplayerDied =>
      sendLeaving(parameters, instanceCache)
      controller ! ChannelMusicController.StartShutdown
      parameters.player.destroy()
      deathwatch(parameters.player, lavaplayerDead = true, controllerDead = false)
  }

  def deathwatch(player: AudioPlayer, lavaplayerDead: Boolean, controllerDead: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case LavaplayerDied =>
        player.destroy()
        if (controllerDead) Behaviors.stopped
        else deathwatch(player, lavaplayerDead = true, controllerDead = false)
      case ControllerDied =>
        if (lavaplayerDead) Behaviors.stopped
        else deathwatch(player, lavaplayerDead = false, controllerDead = true)
    }

  sealed trait Command
  case object Shutdown extends Command
  case class ChannelMusicCommandWrapper(
      command: GuildMusicHandler.MusicCommand,
      sendEmbed: (Seq[OutgoingEmbed], Seq[ActionRow]) => OptFuture[RawMessage],
      info: GuildMusicHandler.MusicCmdInfo
  ) extends Command

  case object LavaplayerDied extends Command
  case object ControllerDied extends Command

  private case class GotInstance(replyTo: ActorRef[Instance.SetLifetime], instanceCache: Cache) extends Command
  private case class MusicReady(instanceUserId: UserId)                                         extends Command

  private case object FailedToGetInstance extends Command
  private case object AlreadyConnected    extends Command
}
