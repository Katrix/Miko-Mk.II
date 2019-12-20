package miko.services

import io.circe._
import io.circe.generic.extras.semiauto._
import ackcord.SnowflakeMap
import ackcord.data.Guild
import miko.MikoProtocol._

case class GlobalData(
    availableGuilds: SnowflakeMap[Guild, String],
    guildSplashes: SnowflakeMap[Guild, String],
    guildIcons: SnowflakeMap[Guild, String],
)
object GlobalData {
  implicit val encoder: Encoder[GlobalData] = deriveConfiguredEncoder
  implicit val decoder: Decoder[GlobalData] = deriveConfiguredDecoder
}

case class CommandEntry(name: String, aliases: Seq[String], usage: String, description: String)
object CommandEntry {
  implicit val encoder: Encoder[CommandEntry] = deriveConfiguredEncoder
  implicit val decoder: Decoder[CommandEntry] = deriveConfiguredDecoder
}

case class MikoCmdCategory(prefix: String, description: String)
object MikoCmdCategory {
  implicit val keyEncoder: KeyEncoder[MikoCmdCategory] = {
    case MikoCmdCategory(prefix, description) => s"$prefix:::$description"
  }
  implicit val keyDecoder: KeyDecoder[MikoCmdCategory] = (key: String) => {
    val split = key.split(":::", 2)
    if (split.length != 2) None
    else {
      val Array(prefix, description) = split
      Some(MikoCmdCategory(prefix, description))
    }
  }
}

case class CommandData(commands: Map[MikoCmdCategory, Set[CommandEntry]])
object CommandData {
  implicit val encoder: Encoder[CommandData] = deriveConfiguredEncoder
  implicit val decoder: Decoder[CommandData] = deriveConfiguredDecoder
}

case class MusicEntry(id: Long, url: String, name: String, durationSec: Long, isSeekable: Boolean)
object MusicEntry {
  implicit val encoder: Encoder[MusicEntry] = deriveConfiguredEncoder
  implicit val decoder: Decoder[MusicEntry] = deriveConfiguredDecoder
}

case class MusicState(volume: Int, defVolume: Int, paused: Boolean, currentPosition: Long, userInVoiceChannel: Boolean)
object MusicState {
  implicit val encoder: Encoder[MusicState] = deriveConfiguredEncoder
  implicit val decoder: Decoder[MusicState] = deriveConfiguredDecoder
}

case class MusicData(state: MusicState, playlist: Seq[MusicEntry], currentlyPlaying: Option[Int])
object MusicData {
  implicit val encoder: Encoder[MusicData] = deriveConfiguredEncoder
  implicit val decoder: Decoder[MusicData] = deriveConfiguredDecoder
}

case class LogData(filenames: Map[String, Seq[String]])
object LogData {
  implicit val encoder: Encoder[LogData] = deriveConfiguredEncoder
  implicit val decoder: Decoder[LogData] = deriveConfiguredDecoder
}
