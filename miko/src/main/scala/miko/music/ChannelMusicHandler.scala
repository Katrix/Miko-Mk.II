package miko.music

import java.util.concurrent.ThreadLocalRandom

import ackcord.{Cache, CacheSnapshot}
import ackcord.data.{ChannelId, GuildId, UserId}
import ackcord.gateway.{GatewayMessage, VoiceStateUpdate, VoiceStateUpdateData}
import ackcord.lavaplayer.LavaplayerHandler
import ackcord.requests.RequestHelper
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.stream.scaladsl.Source
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import miko.slaves.{AbstractSlave, SlaveHandler}
import miko.web.WebEvents

object ChannelMusicHandler {

  case class Parameters(
      context: ActorContext[Command],
      stash: StashBuffer[Command],
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: ChannelId,
      topCache: Cache,
      loader: ActorRef[AudioItemLoader.Command],
      slaveHandler: ActorRef[SlaveHandler.Command],
      handler: ActorRef[GuildMusicHandler.Command],
      initialCacheSnapshot: CacheSnapshot,
      reservationId: Long
  )

  case class State(
      musicReady: Boolean = false,
      gotSlaveInfo: Option[GotSlaveInfo] = None,
      slaveUserId: Option[UserId] = None
  )

  case class GotSlaveInfo(
      slaveCache: Cache,
      awaitingLifetime: ActorRef[AbstractSlave.SetLifetime],
      lavaplayerHandler: ActorRef[LavaplayerHandler.Command]
  )

  def apply(
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: ChannelId,
      topCache: Cache,
      loader: ActorRef[AudioItemLoader.Command],
      slaveHandler: ActorRef[SlaveHandler.Command],
      handler: ActorRef[GuildMusicHandler.Command],
      initialCacheSnapshot: CacheSnapshot
  )(implicit requests: RequestHelper, webEvents: WebEvents): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withStash(32) { stash =>
      val reservationId = ThreadLocalRandom.current().nextLong()
      slaveHandler ! SlaveHandler.ReserveSlave(
        reservationId,
        guildId,
        ctx.messageAdapter {
          case SlaveHandler.ReservationFailed(_)                 => FailedToGetSlave
          case SlaveHandler.SendLifetime(_, replyTo, slaveCache) => GotSlave(replyTo, slaveCache)
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
          slaveHandler,
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
  )(implicit requests: RequestHelper, webEvents: WebEvents): Behavior[Command] = {
    import parameters._

    def tryStart(state: State) = state match {
      case State(
          true,
          Some(GotSlaveInfo(slaveCache, awaitingLifetime, lavaplayerHandler)),
          Some(slaveUserId)
          ) =>
        val controller = context.spawn(
          ChannelMusicController(
            player,
            guildId,
            firstVChannelId,
            initialCacheSnapshot,
            topCache,
            slaveUserId,
            loader,
            handler
          ),
          "ChannelMusicController"
        )
        context.watchWith(controller, ControllerDied)

        awaitingLifetime ! AbstractSlave.SetLifetime(reservationId, controller)
        lavaplayerHandler ! LavaplayerHandler.SetPlaying(true)

        stash.unstashAll(running(parameters, controller, lavaplayerHandler, slaveCache))
      case _ => initializing(parameters, state)
    }

    Behaviors.receiveMessage {
      case GotSlave(replyTo, slaveCache) =>
        val lavaplayerHandler =
          context.spawn(LavaplayerHandler(player, guildId, slaveCache), "LavaplayerHandler")
        lavaplayerHandler ! LavaplayerHandler.ConnectVChannel(
          firstVChannelId,
          force = false,
          context.messageAdapter {
            case LavaplayerHandler.AlreadyConnectedFailure(_, _) => AlreadyConnected
            case LavaplayerHandler.ForcedConnectionFailure(_, _) => sys.error("impossible")
            case LavaplayerHandler.MusicReady(_, userId)         => MusicReady(userId)
          }
        )
        context.watchWith(lavaplayerHandler, LavaplayerDied)

        tryStart(state.copy(gotSlaveInfo = Some(GotSlaveInfo(slaveCache, replyTo, lavaplayerHandler))))

      case MusicReady(userId) =>
        tryStart(state.copy(musicReady = true, slaveUserId = Some(userId)))

      case AlreadyConnected =>
        player.destroy()
        handler ! GuildMusicHandler.FailedToStart(GuildMusicHandler.AlreadyConnected, firstVChannelId)
        Behaviors.stopped

      case FailedToGetSlave =>
        player.destroy()
        handler ! GuildMusicHandler.FailedToStart(GuildMusicHandler.NoSlaves, firstVChannelId)
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

  private def sendLeaving(parameters: Parameters, slaveCache: Cache): Unit = {
    implicit val system: ActorSystem[Nothing] = parameters.context.system

    Source
      .single(
        VoiceStateUpdate(VoiceStateUpdateData(parameters.guildId, None, selfMute = false, selfDeaf = false))
          .asInstanceOf[GatewayMessage[Any]]
      )
      .runWith(slaveCache.gatewayPublish)
  }

  def running(
      parameters: ChannelMusicHandler.Parameters,
      controller: ActorRef[ChannelMusicController.Command],
      lavaplayerHandler: ActorRef[LavaplayerHandler.Command],
      slaveCache: Cache
  ): Behavior[Command] = Behaviors.receiveMessagePartial {
    case Shutdown =>
      sendLeaving(parameters, slaveCache)
      lavaplayerHandler ! LavaplayerHandler.Shutdown
      controller ! ChannelMusicController.Shutdown

      deathwatch(parameters.player, lavaplayerDead = false, controllerDead = false)

    case ChannelMusicCommandWrapper(command, info) =>
      controller ! ChannelMusicController.ChannelMusicCommandWrapper(command, info)
      Behaviors.same

    case ControllerDied =>
      sendLeaving(parameters, slaveCache)
      lavaplayerHandler ! LavaplayerHandler.Shutdown
      deathwatch(parameters.player, lavaplayerDead = false, controllerDead = true)

    case LavaplayerDied =>
      sendLeaving(parameters, slaveCache)
      controller ! ChannelMusicController.Shutdown
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
  case class ChannelMusicCommandWrapper(command: GuildMusicHandler.MusicCommand, info: GuildMusicHandler.MusicCmdInfo)
      extends Command

  case object LavaplayerDied extends Command
  case object ControllerDied extends Command

  private case class GotSlave(replyTo: ActorRef[AbstractSlave.SetLifetime], slaveCache: Cache) extends Command
  private case class MusicReady(slaveUserId: UserId)                                           extends Command

  private case object FailedToGetSlave extends Command
  private case object AlreadyConnected extends Command
}
