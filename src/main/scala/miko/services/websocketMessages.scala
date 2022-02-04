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

    case object C_SET_POSITION      extends ClientOpCode(62)
    case object C_SET_PAUSED        extends ClientOpCode(63)
    case object C_UPDATE_VOLUME     extends ClientOpCode(64)
    case object C_SET_TRACK_PLAYING extends ClientOpCode(65)
    case object C_QUEUE_TRACK       extends ClientOpCode(66)
    case object C_MOVE_TRACK        extends ClientOpCode(67)
    case object C_REMOVE_TRACK      extends ClientOpCode(68)
  }

  case class SetPosition(position: Long)               extends ClientMessage(ClientOpCode.C_SET_POSITION)
  case class SetPaused(paused: Boolean)                extends ClientMessage(ClientOpCode.C_SET_PAUSED)
  case class UpdateVolume(volume: Int, defVolume: Int) extends ClientMessage(ClientOpCode.C_UPDATE_VOLUME)
  case class SetTrackPlaying(idx: Int)                 extends ClientMessage(ClientOpCode.C_SET_TRACK_PLAYING)
  case class QueueTrack(url: String)                   extends ClientMessage(ClientOpCode.C_QUEUE_TRACK)
  case class MoveTrack(fromIdx: Int, toIdx: Int)       extends ClientMessage(ClientOpCode.C_MOVE_TRACK)
  case class RemoveTrack(idx: Int)                     extends ClientMessage(ClientOpCode.C_REMOVE_TRACK)

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
          case ClientOpCode.C_SET_TRACK_PLAYING => c.get[Int]("idx").map(SetTrackPlaying)
          case ClientOpCode.C_QUEUE_TRACK       => c.get[String]("url").map(QueueTrack)
          case ClientOpCode.C_MOVE_TRACK =>
            for {
              fromIdx <- c.get[Int]("fromIdx")
              toIdx   <- c.get[Int]("toIdx")
            } yield MoveTrack(fromIdx, toIdx)
          case ClientOpCode.C_REMOVE_TRACK => c.get[Int]("idx").map(RemoveTrack)
        }
      } yield res,
    (a: ClientMessage) =>
      Json.obj(Seq("op" := a.op) ++ (a match {
        case SetPosition(position)           => Seq("position" := position)
        case SetPaused(paused)               => Seq("paused" := paused)
        case UpdateVolume(volume, defVolume) => Seq("volume" := volume, "defVolumme" := defVolume)
        case SetTrackPlaying(idx)            => Seq("idx" := idx)
        case QueueTrack(url)                 => Seq("url" := url)
        case MoveTrack(fromIdx, toIdx)       => Seq("fromIdx" := fromIdx, "toIdx" := toIdx)
        case RemoveTrack(idx)                => Seq("idx" := idx)
      }): _*)
  )
}

sealed abstract class ServerMessage(val op: ServerMessage.ServerOpCode) extends BaseMessage {

  def webId: Option[Long]

  def includeWebIdIf(cond: Boolean): ServerMessage
}
object ServerMessage {
  sealed abstract class ServerOpCode(val value: Byte) extends ByteEnumEntry
  object ServerOpCode extends ByteEnum[ServerOpCode] with ByteCirceEnum[ServerOpCode] {
    override def values: IndexedSeq[ServerOpCode] = findValues

    case object S_NEXT_TRACK        extends ServerOpCode(0)
    case object S_ADD_TRACK         extends ServerOpCode(1)
    case object S_MOVED_TRACK       extends ServerOpCode(2)
    case object S_REMOVED_TRACK     extends ServerOpCode(3)
    case object S_SET_POSITION      extends ServerOpCode(4)
    case object S_SET_PAUSED        extends ServerOpCode(5)
    case object S_UPDATE_VOLUME     extends ServerOpCode(6)
    case object S_REFRESH_STATE     extends ServerOpCode(7)
    case object S_SET_PLAYLIST      extends ServerOpCode(8)
    case object S_SET_TRACK_PLAYING extends ServerOpCode(9)
    case object S_SET_LOOP          extends ServerOpCode(10)
    case object S_STOPPING          extends ServerOpCode(11)
  }

  case class NextTrack(webId: Option[Long]) extends ServerMessage(ServerOpCode.S_NEXT_TRACK) {
    def includeWebIdIf(cond: Boolean): NextTrack = copy(webId = if (cond) webId else None)
  }
  case class AddTracks(tracks: Seq[MusicEntry], webId: Option[Long]) extends ServerMessage(ServerOpCode.S_ADD_TRACK) {
    def includeWebIdIf(cond: Boolean): AddTracks = copy(webId = if (cond) webId else None)
  }
  case class MovedTrack(fromIdx: Int, toIdx: Int, webId: Option[Long])
      extends ServerMessage(ServerOpCode.S_MOVED_TRACK) {
    def includeWebIdIf(cond: Boolean): MovedTrack = copy(webId = if (cond) webId else None)
  }
  case class RemovedTrack(idx: Int, webId: Option[Long]) extends ServerMessage(ServerOpCode.S_REMOVED_TRACK) {
    def includeWebIdIf(cond: Boolean): RemovedTrack = copy(webId = if (cond) webId else None)
  }
  case class SetPosition(position: Long, webId: Option[Long]) extends ServerMessage(ServerOpCode.S_SET_POSITION) {
    def includeWebIdIf(cond: Boolean): SetPosition = copy(webId = if (cond) webId else None)
  }
  case class SetPaused(paused: Boolean, webId: Option[Long]) extends ServerMessage(ServerOpCode.S_SET_PAUSED) {
    def includeWebIdIf(cond: Boolean): SetPaused = copy(webId = if (cond) webId else None)
  }
  case class UpdatedVolume(volume: Option[Int], defVolume: Option[Int], webId: Option[Long])
      extends ServerMessage(ServerOpCode.S_UPDATE_VOLUME) {
    def includeWebIdIf(cond: Boolean): UpdatedVolume = copy(webId = if (cond) webId else None)
  }
  case class SetTrackPlaying(idx: Int, position: Long, webId: Option[Long])
      extends ServerMessage(ServerOpCode.S_SET_TRACK_PLAYING) {
    def includeWebIdIf(cond: Boolean): SetTrackPlaying = copy(webId = if (cond) webId else None)
  }
  case class SetPlaylist(playlist: Seq[MusicEntry], webId: Option[Long])
      extends ServerMessage(ServerOpCode.S_SET_PLAYLIST) {
    def includeWebIdIf(cond: Boolean): SetPlaylist = copy(webId = if (cond) webId else None)
  }
  case class RefreshState(
      playlist: Seq[MusicEntry],
      idx: Int,
      paused: Boolean,
      volume: Int,
      defVolume: Int,
      playingCurrentPos: Long,
      loop: Boolean,
      webId: Option[Long]
  ) extends ServerMessage(ServerOpCode.S_REFRESH_STATE) {
    def includeWebIdIf(cond: Boolean): RefreshState = copy(webId = if (cond) webId else None)
  }
  case class SetLoop(loop: Boolean, webId: Option[Long]) extends ServerMessage(ServerOpCode.S_SET_LOOP) {
    def includeWebIdIf(cond: Boolean): SetLoop = copy(webId = if (cond) webId else None)
  }
  case class MusicStopping(webId: Option[Long]) extends ServerMessage(ServerOpCode.S_STOPPING) {
    def includeWebIdIf(cond: Boolean): MusicStopping = copy(webId = if (cond) webId else None)
  }

  implicit val codec: Codec[ServerMessage] = Codec.from(
    (c: HCursor) =>
      for {
        op    <- c.get[ServerOpCode]("op")
        webId <- c.get[Option[Long]]("webId")
        res <- op match {
          case ServerOpCode.S_NEXT_TRACK => Right(NextTrack(webId))
          case ServerOpCode.S_ADD_TRACK  => c.get[Seq[MusicEntry]]("tracks").map(AddTracks(_, webId))
          case ServerOpCode.S_MOVED_TRACK =>
            for {
              fromIdx <- c.get[Int]("fromIdx")
              toIdx   <- c.get[Int]("toIdx")
            } yield MovedTrack(fromIdx, toIdx, webId)
          case ServerOpCode.S_REMOVED_TRACK => c.get[Int]("idx").map(RemovedTrack(_, webId))
          case ServerOpCode.S_SET_POSITION  => c.get[Long]("position").map(SetPosition(_, webId))
          case ServerOpCode.S_SET_PAUSED    => c.get[Boolean]("paused").map(SetPaused(_, webId))
          case ServerOpCode.S_UPDATE_VOLUME =>
            for {
              vol    <- c.get[Option[Int]]("volume")
              defVol <- c.get[Option[Int]]("defVolume")
            } yield UpdatedVolume(vol, defVol, webId)
          case ServerOpCode.S_REFRESH_STATE =>
            for {
              playlist <- c.get[Seq[MusicEntry]]("idx")
              idx      <- c.get[Int]("idx")
              paused   <- c.get[Boolean]("paused")
              vol      <- c.get[Int]("volume")
              defVol   <- c.get[Int]("defVolume")
              position <- c.get[Long]("playingCurrentPos")
              loop     <- c.get[Boolean]("loop")
            } yield RefreshState(playlist, idx, paused, vol, defVol, position, loop, webId)
          case ServerOpCode.S_SET_PLAYLIST => c.get[Seq[MusicEntry]]("playlist").map(SetPlaylist(_, webId))
          case ServerOpCode.S_SET_TRACK_PLAYING =>
            for {
              idx      <- c.get[Int]("idx")
              position <- c.get[Long]("position")
            } yield SetTrackPlaying(idx, position, webId)
          case ServerOpCode.S_SET_LOOP => c.get[Boolean]("loop").map(SetLoop(_, webId))
          case ServerOpCode.S_STOPPING => Right(MusicStopping(webId))
        }
      } yield res,
    (a: ServerMessage) =>
      Json.obj(Seq("op" := a.op, "webId" := a.webId) ++ (a match {
        case NextTrack(_)                        => Nil
        case AddTracks(tracks, _)                => Seq("tracks" := tracks)
        case MovedTrack(fromIdx, toIdx, _)       => Seq("fromIdx" := fromIdx, "toIdx" := toIdx)
        case RemovedTrack(idx, _)                => Seq("idx" := idx)
        case SetPosition(position, _)            => Seq("position" := position)
        case SetPaused(paused, _)                => Seq("paused" := paused)
        case UpdatedVolume(volume, defVolume, _) => Seq("volume" := volume, "defVolume" := defVolume)
        case SetTrackPlaying(idx, position, _)   => Seq("idx" := idx, "position" := position)
        case SetPlaylist(playlist, _)            => Seq("playlist" := playlist)
        case RefreshState(playlist, idx, paused, volume, defVolume, playingCurrentPos, loop, _) =>
          Seq(
            "playlist" := playlist,
            "idx" := idx,
            "paused" := paused,
            "volume" := volume,
            "defVolume" := defVolume,
            "playingCurrentPos" := playingCurrentPos,
            "loop" := loop
          )
        case SetLoop(loop, _) => Seq("loop" := loop)
        case MusicStopping(_) => Nil
      }): _*)
  )
}
