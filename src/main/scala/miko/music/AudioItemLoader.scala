package miko.music

import ackcord.lavaplayer.LavaplayerHandler
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.{AudioItem, AudioPlaylist, AudioTrack}

import scala.jdk.CollectionConverters._

object AudioItemLoader {

  def apply(playerManager: AudioPlayerManager, trackId: Long): Behavior[Command] = Behaviors.receive {
    case (ctx, LoadItem(identifier, replyTo)) =>
      import ctx.executionContext
      LavaplayerHandler.loadItem(playerManager, identifier).onComplete { t =>
        ctx.self ! LoadedItem(t.toEither, replyTo)
      }

      Behaviors.same

    case (_, LoadedItem(Right(track: AudioTrack), replyTo)) =>
      track.setUserData(trackId)
      replyTo ! LoadedTrack(track)
      apply(playerManager, trackId + 1)

    case (_, LoadedItem(Right(playlist: AudioPlaylist), replyTo)) =>
      playlist.getTracks.asScala.zipWithIndex.foreach(t => t._1.setUserData(trackId + t._2))
      replyTo ! LoadedPlaylist(playlist)
      apply(playerManager, trackId + playlist.getTracks.size)

    case (ctx, LoadedItem(Right(item), _)) =>
      ctx.log.warn(s"Loaded unknown item $item")
      Behaviors.same

    case (_, LoadedItem(Left(e), replyTo)) =>
      replyTo ! FailedToLoad(e)
      Behaviors.same
  }

  sealed trait Command
  case class LoadItem(identifier: String, replyTo: ActorRef[LoadReply])                           extends Command
  private case class LoadedItem(item: Either[Throwable, AudioItem], replyTo: ActorRef[LoadReply]) extends Command

  sealed trait LoadReply
  case class LoadedTrack(track: AudioTrack)          extends LoadReply
  case class LoadedPlaylist(playlist: AudioPlaylist) extends LoadReply
  case class FailedToLoad(e: Throwable)              extends LoadReply
}
