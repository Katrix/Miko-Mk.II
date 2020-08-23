package miko.music

import io.circe._
import io.circe.derivation._

case class MusicEntry(id: Long, url: String, name: String, duration: Long, isSeekable: Boolean)
object MusicEntry {
  implicit val codec: Codec[MusicEntry] = deriveCodec
}
