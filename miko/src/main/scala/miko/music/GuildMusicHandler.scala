package miko.music

import java.util.concurrent.ThreadLocalRandom

import ackcord.data.{ChannelId, Guild, GuildId, OutgoingEmbed, TChannel, TGuildChannel, UserId}
import ackcord.syntax._
import ackcord.{Cache, CacheSnapshot, Requests}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import cats.effect.Effect
import cats.effect.syntax.all._
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import doobie.util.transactor.Transactor
import miko.db.DBMemoizedAccess
import miko.services.{MusicSetDefaultVolume, ServerEvent}
import miko.settings.GuildSettings
import miko.slaves.SlaveHandler
import miko.util.Color
import miko.voicetext.VoiceTextStreams
import miko.web.WebEvents
import miko.web.WebEvents.ServerEventWrapper
import scalacache.Mode

import scala.collection.mutable

class GuildMusicHandler[G[_]: Mode: Effect: Transactor](
    ctx: ActorContext[GuildMusicHandler.Command],
    guildId: GuildId,
    playerManager: AudioPlayerManager,
    topCache: Cache,
    slaveHandler: ActorRef[SlaveHandler.Command],
    audioItemLoader: ActorRef[AudioItemLoader.Command]
)(implicit requests: Requests, webEvents: WebEvents, guildSettingsCache: scalacache.Cache[GuildSettings])
    extends AbstractBehavior[GuildMusicHandler.Command](ctx) {
  import GuildMusicHandler._

  val connectedPlayers = mutable.HashMap.empty[ChannelId, (ActorRef[ChannelMusicHandler.Command], MusicCmdInfo)]
  val channelIdById    = mutable.HashMap.empty[Long, ChannelId]
  val idByChannelId    = mutable.HashMap.empty[ChannelId, Long]

  def textChannel(vChannelId: ChannelId, current: Option[TChannel]): Option[TChannel] = {
    for {
      guild <- guildId.resolve(lastCacheSnapshot)
      ch <- guild
        .vChannelById(vChannelId)
        .flatMap(
          VoiceTextStreams
            .getTextChannel(_, guild)
            .headOption
        )
        .orElse(current)
    } yield ch
  }

  def createHandler(vChannelId: ChannelId): ActorRef[ChannelMusicHandler.Command] = {
    import context.executionContext

    val player = playerManager.createPlayer()
    DBMemoizedAccess.getGuildSettings(guildId).toIO.unsafeToFuture().foreach { settings =>
      player.setVolume(settings.defaultMusicVolume)
    }
    val id = ThreadLocalRandom.current().nextLong()

    val handler = context.spawnAnonymous(
      ChannelMusicHandler(
        player,
        guildId,
        vChannelId,
        topCache,
        audioItemLoader,
        slaveHandler,
        context.self,
        lastCacheSnapshot
      )
    )

    context.watchWith(handler, ChannelStopping(id))
    channelIdById.put(id, vChannelId)
    idByChannelId.put(vChannelId, id)

    handler
  }

  var lastCacheSnapshot: CacheSnapshot = _

  def webEventApplicableUsers: Set[UserId] =
    guildId.resolve(lastCacheSnapshot).toSet.flatMap((g: Guild) => g.members.keySet)

  def sendServerEvent(event: ServerEvent): Unit =
    webEvents.publishSingle(ServerEventWrapper(webEventApplicableUsers, event))

  def updateMusicInfo(info: MusicCmdInfo): Unit = info.cacheSnapshot.foreach { cache =>
    lastCacheSnapshot = cache
    connectedPlayers.updateWith(info.vChannelId)(_.map(t => t._1 -> info))
  }

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case Shutdown =>
      context.log.debug("Shutting down")
      if (connectedPlayers.nonEmpty) {
        connectedPlayers.foreach {
          case (_, (actor, _)) => actor ! ChannelMusicHandler.Shutdown
        }

        Behaviors.same
      } else {
        Behaviors.stopped
      }

    case ChannelStopping(id) =>
      channelIdById.remove(id).foreach { vChannelId =>
        idByChannelId.remove(vChannelId)
        connectedPlayers.remove(vChannelId)
      }

      if (connectedPlayers.isEmpty) Behaviors.stopped else Behaviors.same

    case PlayerMoved(oldId, newId) =>
      connectedPlayers.remove(oldId).foreach { t =>
        connectedPlayers.put(newId, t)
      }

      idByChannelId.remove(oldId).foreach { id =>
        channelIdById.remove(id)

        channelIdById.put(id, newId)
        idByChannelId.put(newId, id)
      }

      Behaviors.same

    case SetDefaultVolume(volume, tChannel, cacheSnapshot) =>
      cacheSnapshot.foreach(lastCacheSnapshot = _)

      sendServerEvent(MusicSetDefaultVolume(guildId, volume))
      DBMemoizedAccess.updateDefaultVolume[G](guildId, volume).toIO.unsafeToFuture()

      tChannel.foreach { chan =>
        requests.singleIgnore(
          chan.sendMessage(
            embed = Some(
              OutgoingEmbed(
                description = Some(s"Set default volume to $volume%"),
                color = Some(Color.forVolume(volume))
              )
            )
          )
        )
      }

      Behaviors.same

    case GuildMusicCommandWrapper(queue: MusicCommand.Queue, info) =>
      updateMusicInfo(info)

      val handler = connectedPlayers.getOrElseUpdate(info.vChannelId, (createHandler(info.vChannelId), info))._1
      handler ! ChannelMusicHandler.ChannelMusicCommandWrapper(queue, info)

      Behaviors.same

    case FailedToStart(reason, vChannelId) =>
      textChannel(vChannelId, connectedPlayers.get(vChannelId).flatMap(_._2.tChannel)).foreach { channel =>
        val message = reason match {
          case GuildMusicHandler.AlreadyConnected => "An error occurred"
          case GuildMusicHandler.NoSlaves         => "Not enough slaves to connect"
        }

        requests.singleIgnore(
          channel.sendMessage(embed = Some(OutgoingEmbed(description = Some(message), color = Some(Color.Failure))))
        )
      }

      Behaviors.same

    case GuildMusicCommandWrapper(command, info) =>
      updateMusicInfo(info)

      connectedPlayers
        .get(info.vChannelId)
        .map(_._1)
        .foreach(_ ! ChannelMusicHandler.ChannelMusicCommandWrapper(command, info))

      Behaviors.same
  }
}
object GuildMusicHandler {

  def apply[G[_]: Mode: Effect: Transactor](
      guildId: GuildId,
      playerManager: AudioPlayerManager,
      topCache: Cache,
      slaveHandler: ActorRef[SlaveHandler.Command],
      audioItemLoader: ActorRef[AudioItemLoader.Command]
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      guildSettingsCache: scalacache.Cache[GuildSettings]
  ): Behavior[Command] =
    Behaviors.setup(ctx => new GuildMusicHandler(ctx, guildId, playerManager, topCache, slaveHandler, audioItemLoader))

  case class MusicCmdInfo(
      tChannel: Option[TGuildChannel],
      vChannelId: ChannelId,
      cacheSnapshot: Option[CacheSnapshot]
  )

  sealed trait Command
  case object Shutdown                                   extends Command
  case class PlayerMoved(from: ChannelId, to: ChannelId) extends Command
  private case class ChannelStopping(id: Long)           extends Command

  sealed trait FailedToStartReason
  case object AlreadyConnected extends FailedToStartReason
  case object NoSlaves         extends FailedToStartReason

  case class FailedToStart(reason: FailedToStartReason, vChannelId: ChannelId) extends Command

  case class SetDefaultVolume(volume: Int, tChannel: Option[TGuildChannel], cacheSnapshot: Option[CacheSnapshot])
      extends Command

  case class GuildMusicCommandWrapper(command: MusicCommand, info: MusicCmdInfo) extends Command

  sealed trait MusicCommand
  object MusicCommand {
    case class Queue(identifier: String)                extends MusicCommand
    case object Pause                                   extends MusicCommand
    case class Volume(volume: Int)                      extends MusicCommand
    case object Stop                                    extends MusicCommand
    case object NowPlaying                              extends MusicCommand
    case object Next                                    extends MusicCommand
    case object Prev                                    extends MusicCommand
    case object Clear                                   extends MusicCommand
    case object Shuffle                                 extends MusicCommand
    case object Progress                                extends MusicCommand
    case class Seek(progress: Long, useOffset: Boolean) extends MusicCommand
    case object ToggleLoop                              extends MusicCommand
    case object Gui                                     extends MusicCommand
  }
}
