package miko.settings

import cats.kernel.Monoid
import io.circe._
import io.circe.generic.extras.semiauto._
import ackcord.data._
import miko.MikoProtocol._

case class GuildSettings(
    guildId: GuildId,
    botSpamChannel: Option[ChannelId] = None,
    staffChannel: Option[ChannelId] = None,
    defaultMusicVolume: Int = 100,
    publicKey: Option[IndexedSeq[Byte]] = None,
    secretKeyChannelId: Option[ChannelId] = None,
    secretKeyMsgId: Option[MessageId] = None,
    requiresMention: Boolean = true,
    vtSettings: VoiceTextSettings = VoiceTextSettings()
)
object GuildSettings {
  implicit val encoder: Encoder[GuildSettings] = deriveConfiguredEncoder
  implicit val decoder: Decoder[GuildSettings] = deriveConfiguredDecoder
}

case class VoiceTextSettings(
    enabled: Boolean = false,
    permsEveryone: VTPermissionValue = VTPermissionValue(),
    permsJoin: VTPermissionValue = VTPermissionValue(),
    permsLeave: VTPermissionValue = VTPermissionValue(),
    dynamicallyResizeChannels: Int = 0,
    destructiveEnabled: Boolean = false,
    destructiveBlacklist: List[ChannelId] = Nil,
    saveDestructable: Boolean = false
)
object VoiceTextSettings {
  implicit val encoder: Encoder[VoiceTextSettings] = deriveConfiguredEncoder
  implicit val decoder: Decoder[VoiceTextSettings] = deriveConfiguredDecoder
}

case class VTPermissionValue(allow: Permission = Permission.None, deny: Permission = Permission.None) {

  def isNone: Boolean = (allow ++ deny).isNone
}
object VTPermissionValue {
  implicit val encoder: Encoder[VTPermissionValue] = deriveConfiguredEncoder
  implicit val decoder: Decoder[VTPermissionValue] = deriveConfiguredDecoder

  implicit val vtPermValueMonoid: Monoid[VTPermissionValue] = new Monoid[VTPermissionValue] {
    override def empty: VTPermissionValue = VTPermissionValue()
    override def combine(x: VTPermissionValue, y: VTPermissionValue): VTPermissionValue =
      VTPermissionValue(x.allow ++ y.allow, x.deny ++ y.deny)
  }
}

sealed trait CommandPermission {
  def id: GuildId
  def command: Option[List[String]]
  def ifMatch: CmdPermissionValue
}
object CommandPermission {
  implicit val encoder: Encoder[CommandPermission] = deriveConfiguredEncoder
  implicit val decoder: Decoder[CommandPermission] = deriveConfiguredDecoder

  case class InChannel(
      id: GuildId,
      category: String,
      command: Option[List[String]],
      inChannel: ChannelId,
      ifMatch: CmdPermissionValue
  ) extends CommandPermission
  case class HasRole(
      id: GuildId,
      category: String,
      command: Option[List[String]],
      role: RoleId,
      ifMatch: CmdPermissionValue
  ) extends CommandPermission
  case class HasPermission(
      id: GuildId,
      category: String,
      command: Option[List[String]],
      permission: Permission,
      ifMatch: CmdPermissionValue
  ) extends CommandPermission
}

sealed trait CmdPermissionValue
object CmdPermissionValue {
  case object Allow     extends CmdPermissionValue
  case object Disallow  extends CmdPermissionValue
  case object Undefined extends CmdPermissionValue

  def asString(value: CmdPermissionValue): String = value match {
    case Allow     => "allow"
    case Disallow  => "disallow"
    case Undefined => "undefined"
  }

  def fromString(str: String): Option[CmdPermissionValue] = str match {
    case "allow"     => Some(Allow)
    case "disallow"  => Some(Disallow)
    case "undefined" => Some(Undefined)
  }

  implicit val encoder: Encoder[CmdPermissionValue] = deriveEnumerationEncoder
  implicit val decoder: Decoder[CmdPermissionValue] = deriveEnumerationDecoder
}
