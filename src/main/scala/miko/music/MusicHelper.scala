package miko.music

import ackcord.data.{OutgoingEmbed, OutgoingEmbedFooter, EmbedField}
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import miko.util.Color

import scala.collection.mutable.ArrayBuffer

object MusicHelper {

  def appendTrackInfo(player: AudioPlayer, track: AudioTrack, embed: OutgoingEmbed): OutgoingEmbed = {
    val dur    = formatDuration(track.getDuration)
    val footer = new ArrayBuffer[String]
    footer.append(dur)

    if (track.getInfo.isStream) {
      footer.append("Is stream")
    }
    footer.append(s"Vol: ${player.getVolume}")

    val trackInfo = s"**[${track.getInfo.title}](${track.getInfo.uri})**"
    embed.copy(
      description = Some(embed.description.fold(trackInfo)(s => s"$s $trackInfo")),
      footer = Some(OutgoingEmbedFooter(footer.mkString("|")))
    )
  }

  def formatDuration(millis: Long): String = {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    if(hours != 0) {
      "%02d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
    }
    else {
      "%02d:%02d".format(minutes % 60, seconds % 60)
    }
  }

  def handleFriendlyException(embed: OutgoingEmbed, exception: FriendlyException): OutgoingEmbed = {
    val reason = EmbedField("Reason:", exception.getMessage)

    exception.severity match {
      case FriendlyException.Severity.COMMON =>
        embed.copy(color = Some(Color.Warning), fields = embed.fields :+ reason)
      case FriendlyException.Severity.SUSPICIOUS =>
        embed.copy(color = Some(Color.Failure), fields = embed.fields :+ EmbedField("Severity:", "Suspicious") :+ reason)
      case FriendlyException.Severity.FAULT =>
        embed.copy(color = Some(Color.Fatal), fields = embed.fields :+ EmbedField("Severity:", "Internal error") :+ reason)
    }
  }

  def handleFriendlyExceptionWithTrack(
      player: AudioPlayer,
      embed: OutgoingEmbed,
      track: AudioTrack,
      exception: FriendlyException
  ): OutgoingEmbed = {
    val withMessage = handleFriendlyException(embed, exception)

    exception.severity match {
      case FriendlyException.Severity.COMMON => withMessage
      case FriendlyException.Severity.SUSPICIOUS =>
        appendTrackInfo(player, track, withMessage)
      case FriendlyException.Severity.FAULT =>
        appendTrackInfo(player, track, withMessage)
    }
  }
}
