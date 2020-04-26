package miko.web.forms

import play.api.data.Forms._
import play.api.data.{Form, Mapping}

import ackcord.data.{Channel, ChannelId, Permission, RawSnowflake, SnowflakeType}
import miko.settings.VTPermissionValue

case class WebSettings(
    botSpam: Option[ChannelId],
    staffChat: Option[ChannelId],
    vt: VtSettings,
)
object WebSettings {

  def optSnowflakeMapping[A]: Mapping[Option[SnowflakeType[A]]] =
    text.transform(s => {
      if (s == "None") None
      else Some(SnowflakeType[A](RawSnowflake(s)))
    }, _.fold("None")(_.asString))

  def snowflakeMapping[A]: Mapping[SnowflakeType[A]] =
    text.transform(s => SnowflakeType[A](RawSnowflake(s)), _.asString)

  lazy val settingsForm = Form(
    mapping(
      "botSpam"   -> optSnowflakeMapping[Channel],
      "staffChat" -> optSnowflakeMapping[Channel],
      "vt"        -> VtSettings.objMapping
    )(WebSettings.apply)(WebSettings.unapply)
  )
}

case class VtSettings(
    enabled: Boolean,
    dynamicallyResizeChannels: Int,
    destructive: VtDestructiveSettings,
    perms: VtPermsSettings
)
object VtSettings {
  val objMapping: Mapping[VtSettings] = mapping(
    "enabled"                   -> boolean,
    "dynamicallyResizeChannels" -> number(min = 0),
    "destructive"               -> VtDestructiveSettings.objMapping,
    "perms"                     -> VtPermsSettings.objMapping
  )(VtSettings.apply)(VtSettings.unapply)
}

case class VtDestructiveSettings(
    enabled: Boolean,
    saveOnDestroy: Boolean,
    blacklist: Seq[ChannelId],
)

object VtDestructiveSettings {
  val objMapping: Mapping[VtDestructiveSettings] = mapping(
    "enabled"       -> boolean,
    "saveOnDestroy" -> boolean,
    "blacklist"     -> seq(WebSettings.snowflakeMapping[Channel])
  )(VtDestructiveSettings.apply)(VtDestructiveSettings.unapply)
}

case class VtPermsSettings(
    everyone: VTPermissionValue,
    join: VTPermissionValue,
    leave: VTPermissionValue
)

object VtPermsSettings {

  sealed trait Tristate
  object Tristate {
    case object On        extends Tristate
    case object Off       extends Tristate
    case object Undefined extends Tristate
  }

  val tristate: Mapping[Tristate] = text
    .verifying(s => s == "on" || s == "off" || s == "undefined")
    .transform(
      {
        case "on"        => Tristate.On
        case "off"       => Tristate.Off
        case "undefined" => Tristate.Undefined
      }, {
        case Tristate.On        => "on"
        case Tristate.Off       => "off"
        case Tristate.Undefined => "undefined"
      }
    )

  def permValue(perm: Permission): Mapping[VTPermissionValue] =
    tristate.transform(
      {
        case Tristate.On        => VTPermissionValue(allow = perm)
        case Tristate.Off       => VTPermissionValue(deny = perm)
        case Tristate.Undefined => VTPermissionValue()
      }, {
        case VTPermissionValue(`perm`, Permission.None)          => Tristate.On
        case VTPermissionValue(Permission.None, `perm`)          => Tristate.Off
        case VTPermissionValue(Permission.None, Permission.None) => Tristate.Undefined
        case _                                                   => Tristate.Undefined
      }
    )

  import cats.syntax.semigroup._

  def unpackPermValue(perm: Permission, allow: Permission, deny: Permission) =
    VTPermissionValue(Permission.fromLong(allow.toLong & perm.toLong), Permission.fromLong(deny.toLong & perm.toLong))

  val permMapping: Mapping[VTPermissionValue] = mapping(
    "createInstantInvite" -> permValue(Permission.CreateInstantInvite),
    "manageChannel"       -> permValue(Permission.ManageChannels),
    "addReactions"        -> permValue(Permission.AddReactions),
    "readMessages"        -> permValue(Permission.ViewChannel),
    "sendMessages"        -> permValue(Permission.SendMessages),
    "sendTtsMessages"     -> permValue(Permission.SendTtsMessages),
    "manageMessages"      -> permValue(Permission.ManageMessages),
    "embedLinks"          -> permValue(Permission.EmbedLinks),
    "attachFiles"         -> permValue(Permission.AttachFiles),
    "readMessageHistory"  -> permValue(Permission.ReadMessageHistory),
    "mentionEveryone"     -> permValue(Permission.MentionEveryone),
    "useExternalEmojis"   -> permValue(Permission.UseExternalEmojis),
    "manageRoles"         -> permValue(Permission.ManageRoles),
    "manageWebhooks"      -> permValue(Permission.ManageWebhooks)
  )(_ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _ |+| _) {
    case VTPermissionValue(allow, deny) =>
      Some(
        (
          unpackPermValue(Permission.CreateInstantInvite, allow, deny),
          unpackPermValue(Permission.ManageChannels, allow, deny),
          unpackPermValue(Permission.AddReactions, allow, deny),
          unpackPermValue(Permission.ViewChannel, allow, deny),
          unpackPermValue(Permission.SendMessages, allow, deny),
          unpackPermValue(Permission.SendTtsMessages, allow, deny),
          unpackPermValue(Permission.ManageMessages, allow, deny),
          unpackPermValue(Permission.EmbedLinks, allow, deny),
          unpackPermValue(Permission.AttachFiles, allow, deny),
          unpackPermValue(Permission.ReadMessageHistory, allow, deny),
          unpackPermValue(Permission.MentionEveryone, allow, deny),
          unpackPermValue(Permission.UseExternalEmojis, allow, deny),
          unpackPermValue(Permission.ManageRoles, allow, deny),
          unpackPermValue(Permission.ManageWebhooks, allow, deny)
        )
      )
  }

  val objMapping: Mapping[VtPermsSettings] = mapping(
    "everyone" -> permMapping,
    "join"     -> permMapping,
    "leave"    -> permMapping
  )(VtPermsSettings.apply)(VtPermsSettings.unapply)
}
