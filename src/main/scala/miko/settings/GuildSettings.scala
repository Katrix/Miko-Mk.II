package miko.settings

import ackcord.data._
import cats.kernel.Monoid
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import miko.MikoProtocol._

case class PublicGuildSettings(
    channels: GuildSettings.Channels = GuildSettings.Channels(),
    music: GuildSettings.Music = GuildSettings.Music(),
    voiceText: GuildSettings.VoiceText = GuildSettings.VoiceText(),
    commands: GuildSettings.Commands = GuildSettings.Commands()
) {

  def toAll(gs: GuildSettings): GuildSettings = GuildSettings(
    channels,
    music,
    gs.guildEncryption,
    voiceText,
    commands
  )
}
object PublicGuildSettings {
  implicit val codec: Codec[PublicGuildSettings] = deriveCodec
}

case class GuildSettings(
    channels: GuildSettings.Channels = GuildSettings.Channels(),
    music: GuildSettings.Music = GuildSettings.Music(),
    guildEncryption: GuildSettings.GuildEncryption = GuildSettings.GuildEncryption(),
    voiceText: GuildSettings.VoiceText = GuildSettings.VoiceText(),
    commands: GuildSettings.Commands = GuildSettings.Commands()
) {

  def asPublic: PublicGuildSettings = PublicGuildSettings(
    channels,
    music,
    voiceText,
    commands
  )
}
object GuildSettings {
  implicit val codec: Codec[GuildSettings] = deriveCodec

  case class Channels(
      botSpamChannel: Option[TextGuildChannelId] = None,
      staffChannel: Option[TextGuildChannelId] = None
  )
  object Channels {
    implicit val codec: Codec[Channels] = deriveCodec
  }

  case class Music(
      defaultMusicVolume: Int = 100
  )
  object Music {
    implicit val codec: Codec[Music] = deriveCodec
  }

  case class GuildEncryption(
      publicKey: Option[String] = None,
      secretKeyChannelId: Option[TextGuildChannelId] = None,
      secretKeyMsgId: Option[MessageId] = None
  )
  object GuildEncryption {
    implicit val codec: Codec[GuildEncryption] = deriveCodec
  }

  case class VoiceText(
      enabled: Boolean = false,
      blacklist: VoiceText.Blacklist = VoiceText.Blacklist(),
      dynamicallyResizeChannels: Int = 0,
      destructive: VoiceText.Destructive = VoiceText.Destructive(),
      perms: VoiceText.VTPermissions = VoiceText.VTPermissions()
  )
  object VoiceText {
    implicit val codec: Codec[VoiceText] = deriveCodec

    case class Blacklist(
        channels: Seq[VoiceGuildChannelId] = Nil,
        categories: Seq[SnowflakeType[GuildCategory]] = Nil
    )
    object Blacklist {
      implicit val codec: Codec[Blacklist] = deriveCodec
    }

    case class Destructive(
        enabled: Boolean = false,
        blacklist: List[TextGuildChannelId] = Nil,
        saveDestroyed: Boolean = false
    )
    object Destructive {
      implicit val codec: Codec[Destructive] = deriveCodec
    }

    case class VTPermissions(
        global: VTPermissionGroup = VTPermissionGroup(),
        overrideChannel: Map[VoiceGuildChannelId, VTPermissionGroup] = Map.empty,
        overrideCategory: Map[SnowflakeType[GuildCategory], VTPermissionGroup] = Map.empty
    )
    object VTPermissions {
      implicit val codec: Codec[VTPermissions] = deriveCodec
    }

    case class VTPermissionGroup(
        everyone: VTPermissionSet = VTPermissionSet(),
        users: Map[UserId, VTPermissionSet] = Map.empty,
        roles: Map[RoleId, VTPermissionValue] = Map.empty
    )
    object VTPermissionGroup {
      implicit val codec: Codec[VTPermissionGroup] = deriveCodec
    }

    case class VTPermissionSet(
        outside: VTPermissionValue = VTPermissionValue(),
        inside: VTPermissionValue = VTPermissionValue()
    )
    object VTPermissionSet {
      implicit val codec: Codec[VTPermissionSet] = deriveCodec
    }

    case class VTPermissionValue(allow: Seq[NamedPermission] = Nil, deny: Seq[NamedPermission] = Nil) {

      def allowNative: Permission = NamedPermission.toPermission(allow)

      def denyNative: Permission = NamedPermission.toPermission(deny)

      def isNone: Boolean = (allowNative ++ denyNative).isNone

      def sameAsOverwrite(overwrite: PermissionOverwrite): Boolean =
        overwrite.allow == allowNative && overwrite.deny == denyNative

      def toOverwrite(id: UserOrRoleId, tpe: PermissionOverwriteType): PermissionOverwrite =
        PermissionOverwrite(id, tpe, allowNative, denyNative)
    }
    object VTPermissionValue {
      implicit val codec: Codec[VTPermissionValue] = deriveCodec

      implicit val vtPermValueMonoid: Monoid[VTPermissionValue] = new Monoid[VTPermissionValue] {
        override def empty: VTPermissionValue = VTPermissionValue()
        override def combine(x: VTPermissionValue, y: VTPermissionValue): VTPermissionValue =
          VTPermissionValue(x.allow ++ y.allow, x.deny ++ y.deny)
      }
    }
  }

  case class Commands(
      requiresMention: Boolean = false,
      prefixes: Commands.Prefixes = Commands.Prefixes(),
      permissions: Commands.Permissions = Commands.Permissions()
  )
  object Commands {
    implicit val codec: Codec[Commands] = deriveCodec

    case class Prefixes(
        general: Seq[String] = Seq("m!"),
        music: Seq[String] = Seq("mm!")
    )
    object Prefixes {
      implicit val codec: Codec[Prefixes] = deriveCodec
    }

    case class Permissions(
        general: Permissions.General = Permissions.General(),
        music: Permissions.Music = Permissions.Music()
    )
    object Permissions {
      implicit val codec: Codec[Permissions] = deriveCodec

      case class General(
          categoryWide: CommandPermission = CommandPermission.Disallow,
          categoryMergeOperation: CommandPermissionMerge = CommandPermissionMerge.Or,
          cleanup: CommandPermission =
            CommandPermission.HasPermission(NamedPermission.fromPermission(Permission.ManageChannels)),
          shiftChannels: CommandPermission =
            CommandPermission.HasPermission(NamedPermission.fromPermission(Permission.ManageChannels)),
          info: CommandPermission = CommandPermission.Allow,
          safebooru: CommandPermission = CommandPermission.Allow
      )
      object General {
        implicit val codec: Codec[General] = deriveCodec
      }

      case class Music(
          categoryWide: CommandPermission = CommandPermission.Disallow,
          categoryMergeOperation: CommandPermissionMerge = CommandPermissionMerge.Or,
          pause: CommandPermission = CommandPermission.Allow,
          volume: CommandPermission = CommandPermission.Allow,
          defVolume: CommandPermission = CommandPermission.Allow,
          stop: CommandPermission = CommandPermission.Allow,
          nowPlaying: CommandPermission = CommandPermission.Allow,
          queue: CommandPermission = CommandPermission.Allow,
          next: CommandPermission = CommandPermission.Allow,
          prev: CommandPermission = CommandPermission.Allow,
          clear: CommandPermission = CommandPermission.Allow,
          shuffle: CommandPermission = CommandPermission.Allow,
          ytQueue: CommandPermission = CommandPermission.Allow,
          scQueue: CommandPermission = CommandPermission.Allow,
          gui: CommandPermission = CommandPermission.Allow,
          seek: CommandPermission = CommandPermission.Allow,
          loop: CommandPermission = CommandPermission.Allow
      )
      object Music {
        implicit val codec: Codec[Music] = deriveCodec
      }

      sealed trait CommandPermissionMerge
      object CommandPermissionMerge {
        implicit val codec: Codec[CommandPermissionMerge] = Codec.from(
          Decoder.decodeString.emap {
            case "and" => Right(And)
            case "or"  => Right(Or)
            case "xor" => Right(Xor)
            case other => Left(s"$other is not a valid merge operation")
          },
          Encoder.encodeString.contramap {
            case And => "and"
            case Or  => "or"
            case Xor => "xor"
          }
        )

        case object And extends CommandPermissionMerge
        case object Or  extends CommandPermissionMerge
        case object Xor extends CommandPermissionMerge
      }

      sealed trait CommandPermission
      object CommandPermission {
        implicit val codec: Codec[CommandPermission] = Codec.from(
          (c: HCursor) => {
            if (c.as[String].contains("allow")) Right(Allow)
            else if (c.as[String].contains("disallow")) Right(Allow)
            else {
              c.get[String]("type").flatMap {
                case "hasPermission" => c.get[Seq[NamedPermission]]("permission").map(HasPermission)
                case "hasRole"       => c.get[RoleId]("role").map(HasRole)
                case "inChannel"     => c.get[ChannelId]("channel").map(InChannel)
                case "isUser"        => c.get[UserId]("user").map(IsUser)
                case "and"           => c.get[Seq[CommandPermission]]("permissions").map(And)
                case "or"            => c.get[Seq[CommandPermission]]("permissions").map(Or)
                case "xor"           => c.get[Seq[CommandPermission]]("permissions").map(Xor)
              }

            }
          }, {
            case Allow                     => "allow".asJson
            case Disallow                  => "disallow".asJson
            case HasPermission(permission) => Json.obj("type" := "hasPermission", "permission" := permission)
            case HasRole(role)             => Json.obj("type" := "hasRole", "role" := role)
            case InChannel(channel)        => Json.obj("type" := "inChannel", "channel" := channel)
            case IsUser(user)              => Json.obj("type" := "isUser", "user" := user)
            case And(permissions)          => Json.obj("type" := "and", "permissions" := permissions)
            case Or(permissions)           => Json.obj("type" := "or", "permissions" := permissions)
            case Xor(permissions)          => Json.obj("type" := "xor", "permissions" := permissions)
          }
        )

        case object Allow    extends CommandPermission
        case object Disallow extends CommandPermission
        case class HasPermission(permission: Seq[NamedPermission]) extends CommandPermission {
          def nativePermission: Permission = NamedPermission.toPermission(permission)
        }
        case class HasRole(role: RoleId)                    extends CommandPermission
        case class InChannel(channel: ChannelId)            extends CommandPermission
        case class IsUser(user: UserId)                     extends CommandPermission
        case class And(permissions: Seq[CommandPermission]) extends CommandPermission
        case class Or(permissions: Seq[CommandPermission])  extends CommandPermission
        case class Xor(permissions: Seq[CommandPermission]) extends CommandPermission
      }
    }
  }
}
