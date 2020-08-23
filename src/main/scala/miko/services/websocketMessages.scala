package miko.services

import enumeratum.values.{ByteCirceEnum, ByteEnum, ByteEnumEntry}
import io.circe._
import io.circe.syntax._
import miko.music.MusicEntry

sealed trait BaseMessage

sealed abstract class ClientMessage(val op: ClientMessage.ClientOpCode) extends BaseMessage
object ClientMessage {
  sealed abstract class ClientOpCode(val value: Byte) extends ByteEnumEntry
  object ClientOpCode extends ByteEnum[ClientOpCode] with ByteCirceEnum[ClientOpCode] {
    override def values: IndexedSeq[ClientOpCode] = findValues

    case object C_SET_POSITION      extends ClientOpCode(12)
    case object C_SET_PAUSED        extends ClientOpCode(13)
    case object C_UPDATE_VOLUME     extends ClientOpCode(14)
    case object C_SET_PLAYLIST      extends ClientOpCode(16)
    case object C_SET_TRACK_PLAYING extends ClientOpCode(17)
  }

  case class SetPosition(position: Long)               extends ClientMessage(ClientOpCode.C_SET_POSITION)
  case class SetPaused(paused: Boolean)                extends ClientMessage(ClientOpCode.C_SET_PAUSED)
  case class UpdateVolume(volume: Int, defVolume: Int) extends ClientMessage(ClientOpCode.C_UPDATE_VOLUME)
  case class SetPlaylist(playlist: Seq[MusicEntry])    extends ClientMessage(ClientOpCode.C_SET_PLAYLIST)
  case class SetTrackPlaying(idx: Int, position: Long) extends ClientMessage(ClientOpCode.C_SET_TRACK_PLAYING)

  implicit val codec: Codec[ClientMessage] = Codec.from(
    (c: HCursor) =>
      for {
        op <- c.get[ClientOpCode]("op")
        res <- op match {
          case ClientOpCode.C_SET_POSITION => c.get[Long]("position").map(SetPosition)
          case ClientOpCode.C_SET_PAUSED   => c.get[Boolean]("paused").map(SetPaused)
          case ClientOpCode.C_UPDATE_VOLUME =>
            for {
              vol    <- c.get[Int]("volume")
              defVol <- c.get[Int]("defVolume")
            } yield UpdateVolume(vol, defVol)
          case ClientOpCode.C_SET_PLAYLIST => c.get[Seq[MusicEntry]]("playlist").map(SetPlaylist)
          case ClientOpCode.C_SET_TRACK_PLAYING =>
            for {
              idx      <- c.get[Int]("idx")
              position <- c.get[Long]("position")
            } yield SetTrackPlaying(idx, position)
        }
      } yield res,
    (a: ClientMessage) =>
      Json.obj(Seq("op" := a.op) ++ (a match {
        case SetPosition(position)           => Seq("position" := position)
        case SetPaused(paused)               => Seq("paused" := paused)
        case UpdateVolume(volume, defVolume) => Seq("volume" := volume, "defVolumme" := defVolume)
        case SetPlaylist(playlist)           => Seq("playlist" := playlist)
        case SetTrackPlaying(idx, position)  => Seq("idx" := idx, "positonn" := position)
      }): _*)
  )
}

sealed abstract class ServerMessage(val op: ServerMessage.ServerOpCode) extends BaseMessage
object ServerMessage {
  sealed abstract class ServerOpCode(val value: Byte) extends ByteEnumEntry
  object ServerOpCode extends ByteEnum[ServerOpCode] with ByteCirceEnum[ServerOpCode] {
    override def values: IndexedSeq[ServerOpCode] = findValues

    case object S_NEXT_TRACK        extends ServerOpCode(0)
    case object S_ADD_TRACK         extends ServerOpCode(1)
    case object S_SET_POSITION      extends ServerOpCode(2)
    case object S_SET_PAUSED        extends ServerOpCode(3)
    case object S_UPDATE_VOLUME     extends ServerOpCode(4)
    case object S_REFRESH_STATE     extends ServerOpCode(5)
    case object S_SET_PLAYLIST      extends ServerOpCode(6)
    case object S_SET_TRACK_PLAYING extends ServerOpCode(7)
    case object S_SET_LOOP          extends ServerOpCode(8)
    case object S_STOPPING          extends ServerOpCode(9)
  }

  case object NextTrack                                extends ServerMessage(ServerOpCode.S_NEXT_TRACK)
  case class AddTracks(tracks: Seq[MusicEntry])        extends ServerMessage(ServerOpCode.S_ADD_TRACK)
  case class SetPosition(position: Long)               extends ServerMessage(ServerOpCode.S_SET_POSITION)
  case class SetPaused(paused: Boolean)                extends ServerMessage(ServerOpCode.S_SET_PAUSED)
  case class UpdateVolume(volume: Int, defVolume: Int) extends ServerMessage(ServerOpCode.S_UPDATE_VOLUME)
  case class SetTrackPlaying(idx: Int, position: Long) extends ServerMessage(ServerOpCode.S_SET_TRACK_PLAYING)
  case class SetPlaylist(playlist: Seq[MusicEntry])    extends ServerMessage(ServerOpCode.S_SET_PLAYLIST)
  case class RefreshState(
      playlist: Seq[MusicEntry],
      idx: Int,
      paused: Boolean,
      volume: Int,
      defVolume: Int,
      playingCurrentPos: Long,
      loop: Boolean
  ) extends ServerMessage(ServerOpCode.S_REFRESH_STATE)
  case class SetLoop(loop: Boolean) extends ServerMessage(ServerOpCode.S_SET_LOOP)
  case object MusicStopping         extends ServerMessage(ServerOpCode.S_STOPPING)

  implicit val codec: Codec[ServerMessage] = Codec.from(
    (c: HCursor) =>
      for {
        op <- c.get[ServerOpCode]("op")
        res <- op match {
          case ServerOpCode.S_NEXT_TRACK   => Right(NextTrack)
          case ServerOpCode.S_ADD_TRACK    => c.get[Seq[MusicEntry]]("tracks").map(AddTracks)
          case ServerOpCode.S_SET_POSITION => c.get[Long]("position").map(SetPosition)
          case ServerOpCode.S_SET_PAUSED   => c.get[Boolean]("paused").map(SetPaused)
          case ServerOpCode.S_UPDATE_VOLUME =>
            for {
              vol    <- c.get[Int]("volume")
              defVol <- c.get[Int]("defVolume")
            } yield UpdateVolume(vol, defVol)
          case ServerOpCode.S_REFRESH_STATE =>
            for {
              playlist <- c.get[Seq[MusicEntry]]("idx")
              idx      <- c.get[Int]("idx")
              paused   <- c.get[Boolean]("paused")
              vol      <- c.get[Int]("volume")
              defVol   <- c.get[Int]("defVolume")
              position <- c.get[Long]("playingCurrentPos")
              loop     <- c.get[Boolean]("loop")
            } yield RefreshState(playlist, idx, paused, vol, defVol, position, loop)
          case ServerOpCode.S_SET_PLAYLIST => c.get[Seq[MusicEntry]]("playlist").map(SetPlaylist)
          case ServerOpCode.S_SET_TRACK_PLAYING =>
            for {
              idx      <- c.get[Int]("idx")
              position <- c.get[Long]("position")
            } yield SetTrackPlaying(idx, position)
          case ServerOpCode.S_SET_LOOP => c.get[Boolean]("loop").map(SetLoop)
          case ServerOpCode.S_STOPPING => Right(MusicStopping)
        }
      } yield res,
    (a: ServerMessage) =>
      Json.obj(Seq("op" := a.op) ++ (a match {
        case NextTrack                       => Nil
        case AddTracks(tracks)               => Seq("tracks" := tracks)
        case SetPosition(position)           => Seq("position" := position)
        case SetPaused(paused)               => Seq("paused" := paused)
        case UpdateVolume(volume, defVolume) => Seq("volume" := volume, "defVolume" := defVolume)
        case SetTrackPlaying(idx, position)  => Seq("idx" := idx, "position" := position)
        case SetPlaylist(playlist)           => Seq("playlist" := playlist)
        case RefreshState(playlist, idx, paused, volume, defVolume, playingCurrentPos, loop) =>
          Seq(
            "playlist" := playlist,
            "idx" := idx,
            "paused" := paused,
            "volume" := volume,
            "defVolume" := defVolume,
            "playingCurrentPos" := playingCurrentPos,
            "loop" := loop
          )
        case SetLoop(loop) => Seq("loop" := loop)
        case MusicStopping => Nil
      }): _*)
  )
}
