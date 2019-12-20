package miko.services

import ackcord.data.GuildId

sealed trait BaseMessage

sealed trait ClientMessage extends BaseMessage

sealed trait ClientWithResponseMessage[Response <: ResponseMessage[D], D] extends ClientMessage {
  def id: Long
}
sealed trait ClientRequest[Response <: ResponseMessage[D], D] extends ClientWithResponseMessage[Response, D]
sealed trait ServerMessage                                    extends BaseMessage

case class InvalidRequestResponse(id: Long, message: String) extends ServerMessage

sealed trait ResponseMessage[A] extends ServerMessage {
  def data: A
  def id: Long
}

sealed trait ServerEvent extends ServerMessage

sealed trait ServerMusicEvent extends ServerEvent {
  def guildId: GuildId
}
sealed trait ClientMusicMessage extends ClientMessage {
  def guildId: GuildId
}
sealed trait CommonMusicEvent extends ServerMusicEvent with ClientMusicMessage

case class MusicTogglePaused(guildId: GuildId)                             extends CommonMusicEvent
case class MusicStopping(guildId: GuildId)                                 extends ServerMusicEvent
case class MusicSetVolume(guildId: GuildId, volume: Int)                   extends CommonMusicEvent
case class MusicSetDefaultVolume(guildId: GuildId, volume: Int)            extends CommonMusicEvent
case class MusicActivePlaying(guildId: GuildId, id: Long)                  extends ServerMusicEvent
case class MusicQueued(guildId: GuildId, entry: MusicEntry)                extends ServerMusicEvent
case class MusicPlaylistQueued(guildId: GuildId, entries: Seq[MusicEntry]) extends ServerMusicEvent
case class MusicSetPlaylist(guildId: GuildId, entries: Seq[MusicEntry])    extends ServerMusicEvent
case class MusicSeeked(guildId: GuildId, position: Int)                    extends CommonMusicEvent
case class MusicToggleLoop(guildId: GuildId)                               extends CommonMusicEvent
