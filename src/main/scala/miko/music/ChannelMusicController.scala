package miko.music

import java.util.concurrent.ThreadLocalRandom

import ackcord.data._
import ackcord.data.raw.RawMessage
import ackcord.lavaplayer.LavaplayerHandler
import ackcord.requests._
import ackcord.syntax._
import ackcord.{APIMessage, Cache, CacheSnapshot}
import akka.NotUsed
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{KillSwitches, OverflowStrategy, UniqueKillSwitch}
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event._
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track._
import miko.services._
import miko.settings.SettingsAccess
import miko.util.Color
import miko.voicetext.VoiceTextStreams
import miko.web.WebEvents
import miko.web.WebEvents.ServerEventWrapper
import org.slf4j.Logger
import zio.ZEnv

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class ChannelMusicController(
    ctx: ActorContext[ChannelMusicController.Command],
    timers: TimerScheduler[ChannelMusicController.Command],
    player: AudioPlayer,
    guildId: GuildId,
    firstVChannelId: VoiceGuildChannelId,
    initialCacheSnapshot: CacheSnapshot,
    cache: Cache,
    slaveUserId: UserId,
    loader: ActorRef[AudioItemLoader.Command],
    handler: ActorRef[GuildMusicHandler.Command]
)(
    implicit requests: Requests,
    webEvents: WebEvents,
    settings: SettingsAccess,
    runtime: zio.Runtime[ZEnv],
) extends AbstractBehavior[ChannelMusicController.Command](ctx) {
  import ChannelMusicController._
  import GuildMusicHandler.MusicCommand._
  import ctx.executionContext

  val log: Logger = context.log

  implicit val system: ActorSystem[Nothing] = context.system

  private val msgQueue: SourceQueueWithComplete[Request[RawMessage]] =
    requests.sinkIgnore[RawMessage].runWith(Source.queue(128, OverflowStrategy.dropHead))

  private val killSwitchReactions = ChannelMusicController.listenForReactions(cache, slaveUserId, context.self).run()

  private val guiMsgQueue: SourceQueueWithComplete[Request[RawMessage]] =
    Source
      .queue[Request[RawMessage]](128, OverflowStrategy.dropHead)
      .via(RequestStreams.removeContext(requests.flow))
      .mapConcat(_.eitherData.toSeq)
      .to(ChannelMusicController.guiControls)
      .run()

  private var currentTrackIdx: Int                 = -1
  private val playlist: mutable.Buffer[AudioTrack] = mutable.Buffer.empty
  private var shouldLoop: Boolean                  = false

  private var vChannelId: VoiceGuildChannelId  = firstVChannelId
  private var lastCacheSnapshot: CacheSnapshot = initialCacheSnapshot

  player.addListener(new LavaplayerHandler.AudioEventSender(context.self, LavaplayerEvent))

  private val killswitchUpdates = cache.subscribeAPI
    .viaMat(KillSwitches.single)(Keep.right)
    .collect {
      case APIMessage.ChannelDelete(_, vChannel, _) if vChannel.id == vChannelId => Seq(Shutdown)
      case APIMessage.VoiceStateUpdate(vs, cs) if vs.userId == slaveUserId && !vs.channelId.contains(vChannelId) =>
        vs.channelId match {
          case Some(newVChannelId) => Seq(VChannelMoved(newVChannelId), SetCacheSnapshot(cs.current))
          case None                => Seq(Shutdown)
        }
      case other => Seq(SetCacheSnapshot(other.cache.current))
    }
    .mapConcat(identity)
    .toMat(ActorSink.actorRef(context.self, UpdatesDone, ShutdownErr))(Keep.left)
    .run()

  timers.startTimerWithFixedDelay("CheckContinue", CheckContinuePlay, 1.minute)

  def textChannel(current: Option[TextGuildChannel]): Future[Option[TextGuildChannel]] = {
    runtime.unsafeRunToFuture(
      settings.getGuildSettings(guildId).map { implicit settings =>
        for {
          guild <- guildId.resolve(lastCacheSnapshot)
          ch <- guild
            .voiceChannelById(vChannelId)
            .flatMap(
              VoiceTextStreams
                .getTextChannel(_, guild)
                .headOption
            )
            .orElse(current)
        } yield ch
      }
    )
  }

  def webEventApplicableUsers: Set[UserId] =
    vChannelId
      .resolve(guildId)(lastCacheSnapshot)
      .map(_.connectedUsers(lastCacheSnapshot))
      .getOrElse(Nil)
      .map(_.id)
      .toSet

  def sendServerEvent(event: ServerMessage): Unit =
    webEvents.publishSingle(ServerEventWrapper(webEventApplicableUsers, guildId, event))

  def sendMessage(
      info: Option[GuildMusicHandler.MusicCmdInfo],
      queue: SourceQueueWithComplete[Request[RawMessage]] = msgQueue
  )(
      message: => CreateMessageData
  ): Unit =
    textChannel(info.flatMap(_.tChannel)).foreach(_.foreach(chan => queue.offer(CreateMessage(chan.id, message))))

  def sendEmbed(
      info: Option[GuildMusicHandler.MusicCmdInfo],
      embed: => OutgoingEmbed,
      queue: SourceQueueWithComplete[Request[RawMessage]] = msgQueue
  ): Unit =
    sendMessage(info, queue)(CreateMessageData(embed = Some(embed)))

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case Shutdown =>
      stopMusic()
      Behaviors.same

    case ShutdownErr(e) =>
      sendEmbed(
        None,
        OutgoingEmbed(
          description = Some("Shutting down music because of error"),
          color = Some(Color.Fatal),
          fields = Seq(EmbedField("Reason: ", e.getMessage))
        )
      )
      log.error("Shutting down music because of error", e)
      stopMusic()
      Behaviors.same

    case UpdatesDone => Behaviors.stopped

    case VChannelMoved(newVChannelId) =>
      handler ! GuildMusicHandler.PlayerMoved(vChannelId, newVChannelId)
      vChannelId = newVChannelId
      Behaviors.same

    case SetCacheSnapshot(cacheSnapshot) =>
      lastCacheSnapshot = cacheSnapshot
      Behaviors.same

    case CheckContinuePlay =>
      if (player.getPlayingTrack == null) {
        stopMusic()
      }

      Behaviors.same

    case QueuedItemResult(AudioItemLoader.LoadedPlaylist(playlist), info) =>
      if (playlist.isSearchResult) {
        Option(playlist.getSelectedTrack)
          .orElse(playlist.getTracks.asScala.headOption)
          .foreach(addTrack)
      } else {
        addTracks(playlist.getTracks.asScala.toSeq: _*)
      }

      sendEmbed(Some(info), playlistQueueMessage(playlist))
      Behaviors.same

    case QueuedItemResult(AudioItemLoader.LoadedTrack(track), info) =>
      addTrack(track)
      sendEmbed(Some(info), trackQueueMessage(track))
      Behaviors.same

    case QueuedItemResult(AudioItemLoader.FailedToLoad(e), info) =>
      val embedToSend = e match {
        case e: FriendlyException =>
          MusicHelper.handleFriendlyException(OutgoingEmbed(description = Some("Failed to load track")), e)
        case e =>
          OutgoingEmbed(
            description = Some(s"Failed to load track for unknown reason"),
            color = Some(Color.Fatal),
            fields = Seq(EmbedField("Reason:", e.getMessage))
          )
      }

      sendEmbed(Some(info), embedToSend)
      Behaviors.same

    case LavaplayerEvent(_: PlayerResumeEvent) => Behaviors.same //NO-OP
    case LavaplayerEvent(_: PlayerPauseEvent)  => Behaviors.same //NO-OP
    case LavaplayerEvent(event: TrackStartEvent) =>
      sendServerEvent(ServerMessage.SetTrackPlaying(event.track.getUserData.asInstanceOf[Long].toInt, 0))

      sendEmbed(
        None,
        MusicHelper.appendTrackInfo(
          event.player,
          event.track,
          OutgoingEmbed(title = Some("Playing song"), color = Some(Color.Success))
        )
        //nowPlayingMsgQueue
      )

      Behaviors.same

    case LavaplayerEvent(event: TrackEndEvent) =>
      val embed = event.endReason match {
        case AudioTrackEndReason.FINISHED =>
          MusicHelper.appendTrackInfo(
            event.player,
            event.track,
            OutgoingEmbed(title = Some("Finished playing song"), color = Some(Color.Success))
          )
        case AudioTrackEndReason.LOAD_FAILED =>
          OutgoingEmbed(title = Some("Failed to load song"), color = Some(Color.Success))
        case AudioTrackEndReason.STOPPED =>
          OutgoingEmbed(title = Some("Stopping"), color = Some(Color.Success))
        case AudioTrackEndReason.REPLACED =>
          OutgoingEmbed(title = Some("Requested next song"), color = Some(Color.Success))
        case AudioTrackEndReason.CLEANUP =>
          OutgoingEmbed(title = Some("Leaking audio player"), color = Some(Color.Fatal))
      }

      sendEmbed(None, embed)

      if (event.endReason.mayStartNext) {
        nextTrack()
      } else if (event.endReason != AudioTrackEndReason.REPLACED) {
        stopMusic()
      }
      Behaviors.same

    case LavaplayerEvent(event: TrackStuckEvent) =>
      val hasNext = playlist.isDefinedAt(currentTrackIdx + 1)

      sendEmbed(
        None,
        MusicHelper.appendTrackInfo(
          event.player,
          event.track,
          OutgoingEmbed(
            title = Some("Music handler is stuck"),
            color = Some(Color.Failure),
            description = if (hasNext) Some("Will play next track") else None,
            fields = Seq(EmbedField("Been waiting for", s"${event.thresholdMs} ms"))
          )
        )
      )

      nextTrack()
      Behaviors.same

    case LavaplayerEvent(event: TrackExceptionEvent) =>
      sendEmbed(
        None,
        MusicHelper.handleFriendlyExceptionWithTrack(
          event.player,
          OutgoingEmbed(title = Some("Music handler encountered exception")),
          event.track,
          event.exception
        )
      )

      if (event.exception.severity == FriendlyException.Severity.FAULT) {
        log.error("Stopping faulty player", event.exception)
        context.self ! Shutdown
      }
      Behaviors.same

    case LavaplayerEvent(event) =>
      throw new Exception(s"Got unknown lavaplayer event $event")

    case ChannelMusicCommandWrapper(command, info) =>
      def sendStandardEmbed(message: String, color: Int = Color.Success): Unit =
        sendEmbed(Some(info), OutgoingEmbed(description = Some(message), color = Some(color)))

      lazy val playingTrack = player.getPlayingTrack
      lazy val hasTrack     = playingTrack != null

      command match {
        case Queue(identifier) =>
          loader ! AudioItemLoader.LoadItem(identifier, context.messageAdapter(msg => QueuedItemResult(msg, info)))

          Behaviors.same

        case Pause =>
          player.setPaused(!player.isPaused)
          sendServerEvent(ServerMessage.SetPaused(player.isPaused))
          //TODO: Inform of state, and reflect the state in the color
          sendStandardEmbed("Toggled pause")
          Behaviors.same

        case SetPaused(paused) =>
          player.setPaused(paused)
          sendServerEvent(ServerMessage.SetPaused(player.isPaused))
          //TODO: Inform of state, and reflect the state in the color
          sendStandardEmbed(s"Set paused: $paused")
          Behaviors.same

        case Volume(volume) =>
          player.setVolume(volume)
          sendServerEvent(ServerMessage.UpdateVolume(volume, ???))
          sendStandardEmbed(s"Set volume to ${player.getVolume}", color = Color.forVolume(volume))
          Behaviors.same

        case VolumeBoth(volume, defVolume) =>
          player.setVolume(volume)
          ??? //Update default volume
          sendServerEvent(ServerMessage.UpdateVolume(volume, ???))
          sendStandardEmbed(s"Set volume to ${player.getVolume}", color = Color.forVolume(volume))
          Behaviors.same

        case Stop =>
          stopMusic()
          sendStandardEmbed("Stopped music")
          Behaviors.same

        case NowPlaying =>
          val embedToSend =
            if (hasTrack) {
              MusicHelper
                .appendTrackInfo(
                  player,
                  playingTrack,
                  OutgoingEmbed(title = Some("Now playing"), color = Some(Color.Success))
                )
            } else {
              OutgoingEmbed(description = Some("No track currently playing"), color = Some(Color.Failure))
            }

          sendEmbed(Some(info), embedToSend)
          Behaviors.same

        case Gui =>
          sendEmbed(
            Some(info),
            OutgoingEmbed(
              title = Some("Music GUI"),
              description = Some(
                EmojiCommand.commands
                  .map(cmd => s":${cmd.shortcode}:: ${lastCacheSnapshot.botUser.mention} ${cmd.description}")
                  .mkString("\n")
              )
            ),
            queue = guiMsgQueue
          )
          Behaviors.same

        case Next =>
          nextTrack()
          sendStandardEmbed("Playing next track")
          Behaviors.same

        case Prev =>
          prevTrack()
          sendStandardEmbed("Playing previous track")
          Behaviors.same

        case Clear =>
          sendServerEvent(ServerMessage.SetPlaylist(Nil))
          removeAllTracks()
          sendStandardEmbed("Cleared the playlist")
          Behaviors.same

        case Shuffle =>
          shuffleTracks()
          sendStandardEmbed("Shuffled the playlist")
          Behaviors.same

        case Progress =>
          val msg = if (hasTrack) {
            val position = MusicHelper.formatDuration(playingTrack.getPosition)
            val duration = MusicHelper.formatDuration(playingTrack.getDuration)

            s"Current progress is $position of $duration"
          } else {
            "No track playing"
          }

          //TODO: Better formatting
          sendStandardEmbed(msg, if (hasTrack) Color.Success else Color.Failure)
          Behaviors.same

        case Seek(progress, useOffset) =>
          val msg = if (hasTrack) {
            val millisProgress = progress * 1000
            val position       = if (useOffset) playingTrack.getPosition + millisProgress else millisProgress

            playingTrack.setPosition(position)
            s"Set current progress to ${MusicHelper.formatDuration(position)}"
          } else {
            "No track playing"
          }
          sendServerEvent(ServerMessage.SetPosition(progress))

          sendStandardEmbed(msg, if (hasTrack) Color.Success else Color.Failure)
          Behaviors.same

        case ToggleLoop =>
          shouldLoop = !shouldLoop
          sendServerEvent(ServerMessage.SetLoop(shouldLoop))

          //TODO: Set color from state
          sendStandardEmbed("Toggled looping", Color.Success)
          Behaviors.same
      }
  }

  def musicEntryForTrack(track: AudioTrack): MusicEntry = MusicEntry(
    track.getUserData.asInstanceOf[Long],
    track.getIdentifier,
    track.getInfo.title,
    track.getDuration / 1000,
    track.isSeekable
  )

  def trackQueueMessage(track: AudioTrack): OutgoingEmbed = {
    OutgoingEmbed(
      title = Some(s"Queue ${track.getIdentifier}"),
      fields = Seq(EmbedField("Added track to queue", track.getInfo.title)),
      footer = Some(
        OutgoingEmbedFooter(
          if (track.getDuration == Long.MaxValue) "Stream"
          else MusicHelper.formatDuration(track.getDuration)
        )
      ),
      color = Some(Color.Success)
    )
  }

  def playlistQueueMessage(playlist: AudioPlaylist): OutgoingEmbed = {
    if (playlist.isSearchResult) {
      val selected = playlist.getSelectedTrack
      val toUse    = if (selected != null) selected else playlist.getTracks.get(0)

      OutgoingEmbed(
        title = Some(s"Queue ${playlist.getName}"),
        footer = Some(
          OutgoingEmbedFooter(
            if (toUse.getDuration == Long.MaxValue) "Stream"
            else MusicHelper.formatDuration(toUse.getDuration)
          )
        ),
        fields = Seq(EmbedField("Added searched track to queue", toUse.getInfo.title)),
        color = Some(Color.Success)
      )
    } else {
      OutgoingEmbed(
        title = Some(s"Queue ${playlist.getName}"),
        footer = Some(
          OutgoingEmbedFooter(
            s"Playlist length: ${MusicHelper.formatDuration(playlist.getTracks.asScala.map(_.getDuration).sum)}"
          )
        ),
        color = Some(Color.Success)
      )
    }
  }

  def stopMusic(): Unit = {
    sendServerEvent(ServerMessage.MusicStopping)
    player.stopTrack()
    killswitchUpdates.shutdown()
    killSwitchReactions.shutdown()
  }

  def addTrack(track: AudioTrack): Unit = {
    sendServerEvent(ServerMessage.AddTracks(Seq(musicEntryForTrack(track))))

    playlist += track

    if (player.startTrack(track, true)) {
      currentTrackIdx = playlist.indexOf(track)
    }
  }

  def addTracks(tracks: AudioTrack*): Unit = {
    addTrack(tracks.head)
    sendServerEvent(ServerMessage.AddTracks(tracks.tail.map(musicEntryForTrack)))

    playlist ++= tracks.tail
  }

  def removeAllTracks(): Unit = playlist.clear()

  def nextTrack(): Unit = moveTrack(1)
  def prevTrack(): Unit = moveTrack(-1)

  def moveTrack(offset: Int): Unit = {
    val idx = if (shouldLoop) (currentTrackIdx + offset) % playlist.size else currentTrackIdx + offset

    if (playlist.isDefinedAt(idx)) {
      val item = playlist(idx)

      if (item.getState != AudioTrackState.INACTIVE) {
        playlist(idx) = item.makeClone()
      }

      player.playTrack(playlist(idx))
      currentTrackIdx = idx
    }
  }

  def shuffleTracks(): Unit = {
    val res = scala.util.Random.javaRandomToRandom(ThreadLocalRandom.current()).shuffle(playlist)

    playlist.clear()
    playlist ++= res
    currentTrackIdx = -1

    sendServerEvent(ServerMessage.SetPlaylist(res.map(musicEntryForTrack).toSeq))

    nextTrack()
  }
}
object ChannelMusicController {

  def apply(
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: VoiceGuildChannelId,
      initialCacheSnapshot: CacheSnapshot,
      cache: Cache,
      slaveUserId: UserId,
      loader: ActorRef[AudioItemLoader.Command],
      handler: ActorRef[GuildMusicHandler.Command]
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      settings: SettingsAccess,
      runtime: zio.Runtime[ZEnv],
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new ChannelMusicController(
          ctx,
          timers,
          player,
          guildId,
          firstVChannelId,
          initialCacheSnapshot,
          cache,
          slaveUserId,
          loader,
          handler
        )
      }
    }

  sealed trait Command
  case object Shutdown                  extends Command
  case class ShutdownErr(e: Throwable)  extends Command
  private case object UpdatesDone       extends Command
  private case object CheckContinuePlay extends Command
  case class ChannelMusicCommandWrapper(command: GuildMusicHandler.MusicCommand, info: GuildMusicHandler.MusicCmdInfo)
      extends Command

  private case class QueuedItemResult(msg: AudioItemLoader.LoadReply, info: GuildMusicHandler.MusicCmdInfo)
      extends Command
  private case class LavaplayerEvent(event: AudioEvent) extends Command

  private case class VChannelMoved(newVChannelId: VoiceGuildChannelId) extends Command
  case class SetCacheSnapshot(cacheSnapshot: CacheSnapshot)            extends Command

  def listenForReactions(
      cache: Cache,
      slaveUserId: UserId,
      actor: ActorRef[Command]
  ): RunnableGraph[UniqueKillSwitch] = {
    def validReactionEvent[M <: APIMessage](m: M)(user: M => Option[User], emoji: M => PartialEmoji): Boolean =
      user(m).exists(_.id != m.cache.current.botUser.id) && EmojiCommand.commandsByUnicode.exists(
        t => emoji(m).name.contains(t._1)
      )

    cache.subscribeAPI
      .collect {
        case m: APIMessage.MessageReactionAdd if validReactionEvent(m)(_.user, _.emoji) =>
          EmojiCommand.commandsByUnicode(m.emoji.name.get).command -> m
        case m: APIMessage.MessageReactionRemove if validReactionEvent(m)(_.user, _.emoji) =>
          EmojiCommand.commandsByUnicode(m.emoji.name.get).command -> m
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .mapConcat {
        case (cmd, m) =>
          implicit val c: CacheSnapshot = m.cache.current

          val res = for {
            ch         <- m.channel
            tCh        <- ch.asTextGuildChannel
            guild      <- m.guild
            voiceState <- guild.voiceStateFor(slaveUserId)
            vCh        <- voiceState.voiceChannel
            if VoiceTextStreams.getTextVoiceChannelName(vCh) == tCh.name
          } yield (cmd, m, tCh, vCh)

          res.toSeq
      }
      .map {
        case (cmd, m, ch, vCh) =>
          ChannelMusicCommandWrapper(
            cmd,
            GuildMusicHandler.MusicCmdInfo(Some(ch), vCh.id, Some(m.cache.current))
          )
      }
      .to(ActorSink.actorRef(actor, Shutdown, ShutdownErr))
  }

  def guiControls(implicit requests: Requests): Sink[RawMessage, NotUsed] = {
    Flow[RawMessage]
      .map(_.toMessage)
      .statefulMapConcat { () =>
        var oldMsg: Message = null

        newMsg => {
          val cleanup = if (oldMsg != null) {
            Some(oldMsg.deleteAllReactions)
          } else None

          oldMsg = newMsg

          def react(emoji: String) = CreateReaction(newMsg.channelId, newMsg.id, emoji)

          cleanup.toSeq ++ EmojiCommand.commands.map(_.unicode).map(react)
        }
      }
      .throttle(1, 0.5.second) //TODO: Figure out why this is needed
      .to(requests.sinkIgnore(Requests.RequestProperties.ordered))
  }
}
