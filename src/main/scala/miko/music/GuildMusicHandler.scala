package miko.music

import ackcord.data.raw.RawMessage
import ackcord.data.{
  ActionRow,
  GatewayGuild,
  GuildId,
  NormalVoiceGuildChannelId,
  OutgoingEmbed,
  TextGuildChannel,
  UserId
}
import ackcord.syntax._
import ackcord.{Cache, CacheSnapshot, OptFuture, Requests}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import cats.effect.unsafe.IORuntime
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import miko.instances.InstanceHandler
import miko.services._
import miko.settings.SettingsAccess
import miko.util.Color
import miko.voicetext.VoiceTextStreams
import miko.web.WebEvents
import miko.web.WebEvents.ServerEventWrapper

import java.util.concurrent.ThreadLocalRandom
import scala.collection.mutable
import scala.concurrent.Future

class GuildMusicHandler(
    ctx: ActorContext[GuildMusicHandler.Command],
    guildId: GuildId,
    playerManager: AudioPlayerManager,
    topCache: Cache,
    instanceHandler: ActorRef[InstanceHandler.Command],
    audioItemLoader: ActorRef[AudioItemLoader.Command]
)(
    implicit requests: Requests,
    webEvents: WebEvents,
    settings: SettingsAccess,
    IORuntime: IORuntime
) extends AbstractBehavior[GuildMusicHandler.Command](ctx) {
  import GuildMusicHandler._
  import ctx.executionContext

  val connectedPlayers =
    mutable.HashMap.empty[NormalVoiceGuildChannelId, (ActorRef[ChannelMusicHandler.Command], MusicCmdInfo)]
  val channelIdById = mutable.HashMap.empty[Long, NormalVoiceGuildChannelId]
  val idByChannelId = mutable.HashMap.empty[NormalVoiceGuildChannelId, Long]

  var lastCacheSnapshot: CacheSnapshot = _

  def textChannel(
      vChannelId: NormalVoiceGuildChannelId,
      current: Option[TextGuildChannel]
  ): Future[Option[TextGuildChannel]] = {
    settings
      .getGuildSettings(guildId)
      .map { implicit settings =>
        for {
          guild <- guildId.resolve(lastCacheSnapshot)
          ch <- guild
            .voiceChannelById(vChannelId)
            .flatMap(VoiceTextStreams.getTextChannel(_, guild).headOption)
            .orElse(current)
        } yield ch
      }
      .unsafeToFuture()
  }

  def createHandler(vChannelId: NormalVoiceGuildChannelId): ActorRef[ChannelMusicHandler.Command] = {
    import context.executionContext

    val player = playerManager.createPlayer()
    settings.getGuildSettings(guildId).unsafeToFuture().foreach { settings =>
      player.setVolume(settings.music.defaultMusicVolume)
    }
    val id = ThreadLocalRandom.current().nextLong()

    val handler = context.spawnAnonymous(
      ChannelMusicHandler(
        player,
        guildId,
        vChannelId,
        topCache,
        audioItemLoader,
        instanceHandler,
        context.self,
        lastCacheSnapshot
      )
    )

    context.watchWith(handler, ChannelStopping(id))
    channelIdById.put(id, vChannelId)
    idByChannelId.put(vChannelId, id)

    handler
  }

  def webEventApplicableUsers: Set[UserId] =
    guildId.resolve(lastCacheSnapshot).toSet.flatMap((guild: GatewayGuild) => guild.members.keySet)

  def sendServerEvent(event: ServerMessage, info: GuildMusicHandler.MusicCmdInfo): Unit =
    webEvents.publishSingle(ServerEventWrapper(webEventApplicableUsers, guildId, event, info.webIdFor))

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

    case SetDefaultVolume(volume, sendEmbed, _, cacheSnapshot) =>
      cacheSnapshot.foreach(lastCacheSnapshot = _)

      //sendServerEvent(ServerMessage.UpdateVolume(???, volume))
      this.settings
        .updateGuildSettings(guildId, gs => gs.copy(music = gs.music.copy(defaultMusicVolume = volume)))
        .unsafeRunAndForget()

      sendEmbed(
        OutgoingEmbed(
          description = Some(s"Set default volume to $volume%"),
          color = Some(Color.forVolume(volume))
        )
      )

      Behaviors.same

    case GuildMusicCommandWrapper(queue: MusicCommand.Queue, sendEmbed, info) =>
      updateMusicInfo(info)

      val handler = connectedPlayers.getOrElseUpdate(info.vChannelId, (createHandler(info.vChannelId), info))._1
      handler ! ChannelMusicHandler.ChannelMusicCommandWrapper(queue, sendEmbed, info)

      Behaviors.same

    case FailedToStart(reason, vChannelId) =>
      textChannel(vChannelId, connectedPlayers.get(vChannelId).flatMap(_._2.tChannel)).foreach { optChannel =>
        optChannel.foreach { channel =>
          val message = reason match {
            case GuildMusicHandler.AlreadyConnected => "An error occurred"
            case GuildMusicHandler.NoInstances      => "Not enough instances to connect"
          }

          requests.singleIgnore(
            channel.sendMessage(embeds = Seq(OutgoingEmbed(description = Some(message), color = Some(Color.Failure))))
          )
        }
      }

      Behaviors.same

    case GuildMusicCommandWrapper(command, sendEmbed, info) =>
      updateMusicInfo(info)

      connectedPlayers
        .get(info.vChannelId)
        .map(_._1)
        .foreach(_ ! ChannelMusicHandler.ChannelMusicCommandWrapper(command, sendEmbed, info))

      Behaviors.same
  }
}
object GuildMusicHandler {

  def apply(
      guildId: GuildId,
      playerManager: AudioPlayerManager,
      topCache: Cache,
      instanceHandler: ActorRef[InstanceHandler.Command],
      audioItemLoader: ActorRef[AudioItemLoader.Command]
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      settings: SettingsAccess,
      IORuntime: IORuntime
  ): Behavior[Command] =
    Behaviors.setup(
      ctx => new GuildMusicHandler(ctx, guildId, playerManager, topCache, instanceHandler, audioItemLoader)
    )

  case class MusicCmdInfo(
      tChannel: Option[TextGuildChannel],
      vChannelId: NormalVoiceGuildChannelId,
      cacheSnapshot: Option[CacheSnapshot],
      webId: Option[Long],
      webIdFor: Option[UserId]
  )

  sealed trait Command
  case object Shutdown                                                                   extends Command
  case class PlayerMoved(from: NormalVoiceGuildChannelId, to: NormalVoiceGuildChannelId) extends Command
  private case class ChannelStopping(id: Long)                                           extends Command

  sealed trait FailedToStartReason
  case object AlreadyConnected extends FailedToStartReason
  case object NoInstances      extends FailedToStartReason

  case class FailedToStart(reason: FailedToStartReason, vChannelId: NormalVoiceGuildChannelId) extends Command

  case class SetDefaultVolume(
      volume: Int,
      sendEmbed: OutgoingEmbed => OptFuture[RawMessage],
      tChannel: Option[TextGuildChannel],
      cacheSnapshot: Option[CacheSnapshot]
  ) extends Command

  case class GuildMusicCommandWrapper(
      command: MusicCommand,
      sendEmbed: (Seq[OutgoingEmbed], Seq[ActionRow]) => OptFuture[RawMessage],
      info: MusicCmdInfo
  ) extends Command

  sealed trait MusicCommand
  object MusicCommand {
    case class Queue(identifier: String)                extends MusicCommand
    case class SetPlaying(idx: Int)                     extends MusicCommand
    case class MoveTrack(fromIdx: Int, toIdx: Int)      extends MusicCommand
    case class RemoveTrack(idx: Int)                    extends MusicCommand
    case object Pause                                   extends MusicCommand
    case class SetPaused(paused: Boolean)               extends MusicCommand
    case class Volume(volume: Int)                      extends MusicCommand
    case class VolumeBoth(volume: Int, defVolume: Int)  extends MusicCommand
    case object Stop                                    extends MusicCommand
    case object NowPlaying                              extends MusicCommand
    case object Playlist                                extends MusicCommand
    case object Next                                    extends MusicCommand
    case object Prev                                    extends MusicCommand
    case object Clear                                   extends MusicCommand
    case object Shuffle                                 extends MusicCommand
    case class Seek(progress: Long, useOffset: Boolean) extends MusicCommand
    case object ToggleLoop                              extends MusicCommand
    case object Gui                                     extends MusicCommand
  }
}
