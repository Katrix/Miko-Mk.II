package miko.music

import ackcord.data._
import ackcord.data.raw.RawMessage
import ackcord.interactions._
import ackcord.interactions.components.{ButtonHandler, GlobalRegisteredComponents}
import ackcord.lavaplayer.LavaplayerHandler
import ackcord.requests._
import ackcord.syntax._
import ackcord.{APIMessage, Cache, CacheSnapshot, JsonSome, OptFuture}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.stream.scaladsl.{Keep, Source, SourceQueueWithComplete}
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{KillSwitches, OverflowStrategy}
import cats.effect.unsafe.IORuntime
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

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class ChannelMusicController(
    ctx: ActorContext[ChannelMusicController.Command],
    timers: TimerScheduler[ChannelMusicController.Command],
    player: AudioPlayer,
    guildId: GuildId,
    firstVChannelId: NormalVoiceGuildChannelId,
    initialCacheSnapshot: CacheSnapshot,
    cache: Cache,
    slaveUserId: UserId,
    loader: ActorRef[AudioItemLoader.Command],
    handler: ActorRef[GuildMusicHandler.Command]
)(
    implicit requests: Requests,
    webEvents: WebEvents,
    settings: SettingsAccess,
    IORuntime: IORuntime
) extends AbstractBehavior[ChannelMusicController.Command](ctx) {
  import ChannelMusicController._
  import GuildMusicHandler.MusicCommand._
  import ctx.executionContext

  val log: Logger = context.log

  implicit val system: ActorSystem[Nothing] = context.system

  private val msgQueue: SourceQueueWithComplete[Request[RawMessage]] =
    requests.sinkIgnore[RawMessage].runWith(Source.queue(128, OverflowStrategy.dropHead))

  private var stopping: Boolean = false

  private var currentTrackIdx: Int                 = -1
  private val playlist: mutable.Buffer[AudioTrack] = mutable.Buffer.empty
  private var shouldLoop: Boolean                  = false

  private var vChannelId: NormalVoiceGuildChannelId = firstVChannelId
  private var lastCacheSnapshot: CacheSnapshot      = initialCacheSnapshot

  player.addListener(new LavaplayerHandler.AudioEventSender(context.self, LavaplayerEvent))
  val emojiIdentifierPrefix: String = UUID.randomUUID().toString
  val guiHandler = new GuiButtonHandler(
    EmojiCommand.commands.map(c => s"${emojiIdentifierPrefix}_${c.identifier}" -> c.command).toMap,
    requests,
    ctx.self,
    vChannelId
  )
  EmojiCommand.commands.foreach(
    c => GlobalRegisteredComponents.addHandler(s"${emojiIdentifierPrefix}_${c.identifier}", guiHandler)
  )

  def dummyMusicCmdInfo: GuildMusicHandler.MusicCmdInfo =
    GuildMusicHandler.MusicCmdInfo(None, vChannelId, None, None, None)

  private val killswitchUpdates = cache.subscribeAPI
    .viaMat(KillSwitches.single)(Keep.right)
    .collect {
      case APIMessage.ChannelDelete(_, vChannel, _, _) if vChannel.id == vChannelId => Seq(StartShutdown)
      case APIMessage.VoiceStateUpdate(vs, cs, _) if vs.userId == slaveUserId && !vs.channelId.contains(vChannelId) =>
        val newVChannelIdOpt = for {
          id      <- vs.channelId
          guildId <- vs.guildId
          channel <- id.resolve(guildId)(cs.current)
          normalChannel <- channel match {
            case msg: NormalVoiceGuildChannel => Some(msg)
            case _                            => None
          }
        } yield normalChannel.id

        newVChannelIdOpt match {
          case Some(newVChannelId) => Seq(VChannelMoved(newVChannelId), SetCacheSnapshot(cs.current))
          case None                => Seq(StartShutdown)
        }
      case other => Seq(SetCacheSnapshot(other.cache.current))
    }
    .mapConcat(identity)
    .toMat(ActorSink.actorRef(context.self, UpdatesDone, ShutdownErr))(Keep.left)
    .run()

  timers.startTimerWithFixedDelay("CheckContinue", CheckContinuePlay, 1.minute)

  def textChannel(current: Option[TextGuildChannel]): Future[Option[TextGuildChannel]] = {
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

  def webEventApplicableUsers: Set[UserId] =
    vChannelId
      .resolve(guildId)(lastCacheSnapshot)
      .map(_.connectedUsers(lastCacheSnapshot))
      .getOrElse(Nil)
      .map(_.id)
      .toSet

  def sendServerEvent(event: ServerMessage, info: GuildMusicHandler.MusicCmdInfo): Unit =
    webEvents.publishSingle(ServerEventWrapper(webEventApplicableUsers, guildId, event, info.webIdFor))

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
    sendMessage(info, queue)(CreateMessageData(embeds = Seq(embed)))

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case StartShutdown =>
      stopMusic(dummyMusicCmdInfo)
      Behaviors.same

    case Shutdown =>
      Behaviors.stopped

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
      stopMusic(dummyMusicCmdInfo)
      Behaviors.same

    case UpdatesDone =>
      sendEmbed(
        None,
        OutgoingEmbed(
          description = Some("Shutting down music because updates ending"),
          color = Some(Color.Warning)
        )
      )
      stopMusic(dummyMusicCmdInfo)
      Behaviors.same

    case VChannelMoved(newVChannelId) =>
      handler ! GuildMusicHandler.PlayerMoved(vChannelId, newVChannelId)
      vChannelId = newVChannelId
      guiHandler.vChannelId = newVChannelId
      Behaviors.same

    case SetCacheSnapshot(cacheSnapshot) =>
      lastCacheSnapshot = cacheSnapshot
      Behaviors.same

    case CheckContinuePlay =>
      if (player.getPlayingTrack == null) {
        stopMusic(dummyMusicCmdInfo)
      }

      Behaviors.same

    case QueuedItemResult(AudioItemLoader.LoadedPlaylist(playlist), sendInteractionEmbed, info) =>
      if (playlist.isSearchResult) {
        Option(playlist.getSelectedTrack)
          .orElse(playlist.getTracks.asScala.headOption)
          .foreach(addTrack(_, info))
      } else {
        addTracks(info)(playlist.getTracks.asScala.toSeq: _*)
      }

      sendInteractionEmbed(Seq(playlistQueueMessage(playlist)), Nil)
      Behaviors.same

    case QueuedItemResult(AudioItemLoader.LoadedTrack(track), sendInteractionEmbed, info) =>
      addTrack(track, info)
      sendInteractionEmbed(Seq(trackQueueMessage(track)), Nil)
      Behaviors.same

    case QueuedItemResult(AudioItemLoader.FailedToLoad(e), sendInteractionEmbed, _) =>
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

      sendInteractionEmbed(Seq(embedToSend), Nil)
      Behaviors.same

    case LavaplayerEvent(_: PlayerResumeEvent) => Behaviors.same //NO-OP
    case LavaplayerEvent(_: PlayerPauseEvent)  => Behaviors.same //NO-OP
    case LavaplayerEvent(event: TrackStartEvent) =>
      sendServerEvent(ServerMessage.SetTrackPlaying(currentTrackIdx, 0, None), dummyMusicCmdInfo)

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
        stopMusic(dummyMusicCmdInfo)
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
        context.self ! StartShutdown
      }
      Behaviors.same

    case LavaplayerEvent(event) =>
      throw new Exception(s"Got unknown lavaplayer event $event")

    case ChannelMusicCommandWrapper(command, sendInteractionEmbed, info) =>
      def sendStandardEmbed(message: String, color: Int = Color.Success): Unit =
        sendInteractionEmbed(
          Seq(OutgoingEmbed(description = Some(message), color = Some(color))),
          Nil
        )

      lazy val playingTrack = player.getPlayingTrack
      lazy val hasTrack     = playingTrack != null

      val webId = info.webId

      command match {
        case Queue(identifier) =>
          loader ! AudioItemLoader
            .LoadItem(identifier, context.messageAdapter(msg => QueuedItemResult(msg, sendInteractionEmbed, info)))

          Behaviors.same

        case SetPlaying(idx) =>
          playTrackAtIdx(idx)
          sendStandardEmbed(s"Jumping to track ${playlist(currentTrackIdx).getInfo.title}")
          Behaviors.same

        case MoveTrack(fromIdx, toIdx) =>
          val clampedToIdx = math.max(0, math.min(playlist.length - 1, toIdx))

          if (playlist.isDefinedAt(fromIdx)) {
            val track = playlist(fromIdx)

            playlist.insert(clampedToIdx, track)
            playlist.remove(fromIdx)

            if (currentTrackIdx == fromIdx) {
              currentTrackIdx = clampedToIdx
            }

            sendStandardEmbed(s"Moved track ${track.getInfo.title}")
            sendServerEvent(ServerMessage.MovedTrack(fromIdx, toIdx, webId), info)
          }

          Behaviors.same

        case RemoveTrack(idx) =>
          if (playlist.isDefinedAt(idx)) {
            val track = playlist(idx)

            if (playlist.size == 1) {
              removeAllTracks()
            } else {
              if (currentTrackIdx == idx) {
                nextTrack()
              }
              playlist.remove(idx)
            }

            sendStandardEmbed(s"Removed track ${track.getInfo.title} from the playlist", color = Color.Red)
            sendServerEvent(ServerMessage.RemovedTrack(idx, webId), info)
          }

          Behaviors.same

        case Pause =>
          player.setPaused(!player.isPaused)
          sendServerEvent(ServerMessage.SetPaused(player.isPaused, webId), info)
          sendStandardEmbed(
            message = if (player.isPaused) "Paused" else "Unpaused",
            color = if (player.isPaused) Color.Red else Color.Green
          )
          Behaviors.same

        case SetPaused(paused) =>
          player.setPaused(paused)
          sendServerEvent(ServerMessage.SetPaused(player.isPaused, webId), info)
          sendStandardEmbed(
            message = if (paused) "Paused" else "Unpaused",
            color = if (paused) Color.Red else Color.Green
          )
          Behaviors.same

        case Volume(volume) =>
          player.setVolume(volume)
          sendServerEvent(ServerMessage.UpdatedVolume(Some(volume), None, webId), info)
          sendStandardEmbed(s"Set volume to ${player.getVolume}", color = Color.forVolume(volume))
          Behaviors.same

        case VolumeBoth(volume, defVolume) =>
          player.setVolume(volume)

          this.settings
            .updateGuildSettings(guildId, gs => gs.copy(music = gs.music.copy(defaultMusicVolume = defVolume)))
            .unsafeRunAndForget()

          sendServerEvent(ServerMessage.UpdatedVolume(Some(volume), Some(defVolume), webId), info)
          sendStandardEmbed(s"Set volume to ${player.getVolume}", color = Color.forVolume(volume))
          Behaviors.same

        case Stop =>
          stopMusic(info)
          sendStandardEmbed("Stopped music")
          Behaviors.same

        case NowPlaying =>
          val embedToSend =
            if (hasTrack) {
              val position = MusicHelper.formatDuration(playingTrack.getPosition)
              val duration = MusicHelper.formatDuration(playingTrack.getDuration)

              MusicHelper
                .appendTrackInfo(
                  player,
                  playingTrack,
                  OutgoingEmbed(
                    title = Some("Now playing"),
                    color = Some(Color.Success),
                    fields = Seq(EmbedField("Position", s"$position / $duration"))
                  )
                )
            } else {
              OutgoingEmbed(description = Some("No track currently playing"), color = Some(Color.Failure))
            }

          sendInteractionEmbed(Seq(embedToSend), Nil)
          Behaviors.same

        case Playlist =>
          def pageEmbed(pageNum: Int): OutgoingEmbed = {
            val currentPage = playlist.iterator.zipWithIndex.slice(pageNum * 20, (pageNum + 1) * 20).toSeq

            OutgoingEmbed(
              title = Some(s"Playlist page: ${pageNum + 1}"),
              description = Some(
                currentPage
                  .map {
                    case (track, idx) =>
                      val line = s"${idx + 1}. ${track.getInfo.title}"
                      if (currentTrackIdx == idx) s"**$line**" else line
                  }
                  .mkString("\n")
              ),
              footer = Option.when(hasTrack) {
                val position = MusicHelper.formatDuration(playingTrack.getPosition)
                val duration = MusicHelper.formatDuration(playingTrack.getDuration)

                OutgoingEmbedFooter(
                  s"Now playing: ${playingTrack.getInfo.title}|Position: $position / $duration|Vol: ${player.getVolume}"
                )
              },
              color = Some(Color.Success)
            )
          }

          val forwardIdentifier  = UUID.randomUUID().toString
          val backwardIdentifier = UUID.randomUUID().toString

          sendInteractionEmbed(
            Seq(pageEmbed(0)),
            Seq(
              ActionRow.of(
                Button.textEmoji(PartialEmoji(name = Some("\u23EA")), identifier = backwardIdentifier),
                Button.textEmoji(PartialEmoji(name = Some("\u23E9")), identifier = forwardIdentifier)
              )
            )
          ).foreach { message =>
            new ButtonHandler[ComponentInteraction](requests) {
              var page = 0

              GlobalRegisteredComponents.addHandler(backwardIdentifier, this)
              GlobalRegisteredComponents.addHandler(forwardIdentifier, this)

              override def handle(implicit interaction: ComponentInteraction): InteractionResponse = {
                if (interaction.customId == forwardIdentifier) page += 1
                else if (interaction.customId == backwardIdentifier) page -= 1

                page = math.max(0, math.min(playlist.length / 20 + 1, page))

                async { implicit t =>
                  editPreviousMessage(message.id, embeds = JsonSome(Seq(pageEmbed(page))))
                }
              }
            }
          }

          Behaviors.same

        case Gui =>
          sendInteractionEmbed(
            Seq(
              OutgoingEmbed(
                title = Some("Music GUI"),
                description = Some(
                  EmojiCommand.commands
                    .map(cmd => s":${cmd.shortcode} ${cmd.description}")
                    .mkString("\n")
                )
              )
            ),
            EmojiCommand.actionRows(emojiIdentifierPrefix)
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
          sendServerEvent(ServerMessage.SetPlaylist(Nil, webId), info)
          removeAllTracks()
          sendStandardEmbed("Cleared the playlist")
          Behaviors.same

        case Shuffle =>
          shuffleTracks(info)
          sendStandardEmbed("Shuffled the playlist")
          Behaviors.same

        case Seek(progress, useOffset) =>
          val msg = if (hasTrack) {
            val millisProgress = progress * 1000
            println(useOffset)
            println(playingTrack.getPosition)
            val position = if (useOffset) playingTrack.getPosition + millisProgress else millisProgress

            playingTrack.setPosition(position)
            sendServerEvent(ServerMessage.SetPosition(position / 1000, webId), info)
            s"Set current progress to ${MusicHelper.formatDuration(position)}"
          } else {
            "No track playing"
          }

          sendStandardEmbed(msg, if (hasTrack) Color.Success else Color.Failure)
          Behaviors.same

        case ToggleLoop =>
          shouldLoop = !shouldLoop
          sendServerEvent(ServerMessage.SetLoop(shouldLoop, webId), info)

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

  def stopMusic(info: GuildMusicHandler.MusicCmdInfo): Unit = {
    if (!stopping) {
      stopping = true
      sendServerEvent(ServerMessage.MusicStopping(info.webId), info)
      player.stopTrack()
      killswitchUpdates.shutdown()
      GlobalRegisteredComponents.removeHandler(guiHandler)
      context.self ! Shutdown
    }
  }

  def addTrack(track: AudioTrack, info: GuildMusicHandler.MusicCmdInfo): Unit = {
    sendServerEvent(ServerMessage.AddTracks(Seq(musicEntryForTrack(track)), info.webId), info)

    playlist += track

    if (player.startTrack(track, true)) {
      currentTrackIdx = playlist.indexOf(track)
    }
  }

  def addTracks(info: GuildMusicHandler.MusicCmdInfo)(tracks: AudioTrack*): Unit = {
    addTrack(tracks.head, info)
    sendServerEvent(ServerMessage.AddTracks(tracks.tail.map(musicEntryForTrack), info.webId), info)

    playlist ++= tracks.tail
  }

  def removeAllTracks(): Unit = playlist.clear()

  def nextTrack(): Unit = moveTrack(1)
  def prevTrack(): Unit = moveTrack(-1)

  def moveTrack(offset: Int): Unit = {
    val idx = if (shouldLoop) (currentTrackIdx + offset) % playlist.size else currentTrackIdx + offset
    playTrackAtIdx(idx)
  }

  def playTrackAtIdx(idx: Int): Unit = {
    if (playlist.isDefinedAt(idx)) {
      val item = playlist(idx)

      if (item.getState != AudioTrackState.INACTIVE) {
        playlist(idx) = item.makeClone()
      }

      player.playTrack(playlist(idx))
      currentTrackIdx = idx
    }
  }

  def shuffleTracks(info: GuildMusicHandler.MusicCmdInfo): Unit = {
    val res = scala.util.Random.javaRandomToRandom(ThreadLocalRandom.current()).shuffle(playlist)

    playlist.clear()
    playlist ++= res
    currentTrackIdx = -1

    sendServerEvent(ServerMessage.SetPlaylist(res.map(musicEntryForTrack).toSeq, info.webId), info)

    nextTrack()
  }
}
object ChannelMusicController {

  class GuiButtonHandler(
      commandMapping: Map[String, GuildMusicHandler.MusicCommand],
      requests: Requests,
      replyTo: ActorRef[ChannelMusicCommandWrapper],
      var vChannelId: NormalVoiceGuildChannelId
  ) extends ButtonHandler[GuildComponentInteraction](requests, ButtonHandler.guildTransformer) {

    override def handle(implicit interaction: GuildComponentInteraction): InteractionResponse = {
      val command = commandMapping(interaction.customId)

      asyncLoading { implicit t =>
        OptFuture.unit.map { _ =>
          replyTo ! ChannelMusicCommandWrapper(
            command,
            (embeds, components) => sendAsyncEmbed(embeds, components = components),
            GuildMusicHandler
              .MusicCmdInfo(Some(interaction.textChannel), vChannelId, Some(interaction.cache), None, None)
          )
        }
      }
    }
  }

  def apply(
      player: AudioPlayer,
      guildId: GuildId,
      firstVChannelId: NormalVoiceGuildChannelId,
      initialCacheSnapshot: CacheSnapshot,
      cache: Cache,
      slaveUserId: UserId,
      loader: ActorRef[AudioItemLoader.Command],
      handler: ActorRef[GuildMusicHandler.Command]
  )(
      implicit requests: Requests,
      webEvents: WebEvents,
      settings: SettingsAccess,
      IORuntime: IORuntime
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
  case object StartShutdown             extends Command
  case object Shutdown                  extends Command
  case class ShutdownErr(e: Throwable)  extends Command
  private case object UpdatesDone       extends Command
  private case object CheckContinuePlay extends Command
  case class ChannelMusicCommandWrapper(
      command: GuildMusicHandler.MusicCommand,
      sendEmbed: (Seq[OutgoingEmbed], Seq[ActionRow]) => OptFuture[RawMessage],
      info: GuildMusicHandler.MusicCmdInfo
  ) extends Command

  private case class QueuedItemResult(
      msg: AudioItemLoader.LoadReply,
      sendEmbed: (Seq[OutgoingEmbed], Seq[ActionRow]) => OptFuture[RawMessage],
      info: GuildMusicHandler.MusicCmdInfo
  ) extends Command
  private case class LavaplayerEvent(event: AudioEvent) extends Command

  private case class VChannelMoved(newVChannelId: NormalVoiceGuildChannelId) extends Command
  case class SetCacheSnapshot(cacheSnapshot: CacheSnapshot)                  extends Command
}
