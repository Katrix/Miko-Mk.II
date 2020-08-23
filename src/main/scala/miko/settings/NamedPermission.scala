package miko.settings

import ackcord.data.Permission
import enumeratum.values.{StringEnum, StringEnumEntry}
import io.circe.{Codec, Decoder, Encoder}

sealed abstract class NamedPermission(val value: String, val permission: Permission) extends StringEnumEntry
object NamedPermission extends StringEnum[NamedPermission] {
  override def values: IndexedSeq[NamedPermission] = findValues

  case object CreateInstantInvite extends NamedPermission("create_instant_invite", Permission.CreateInstantInvite)
  case object KickMembers         extends NamedPermission("kick_members", Permission.KickMembers)
  case object BanMembers          extends NamedPermission("ban_members", Permission.BanMembers)
  case object Administrator       extends NamedPermission("administrator", Permission.Administrator)
  case object ManageChannels      extends NamedPermission("manage_channels", Permission.ManageChannels)
  case object ManageGuild         extends NamedPermission("manage_guild", Permission.ManageGuild)
  case object AddReactions        extends NamedPermission("add_reactions", Permission.AddReactions)
  case object ViewAuditLog        extends NamedPermission("view_audit_log", Permission.ViewAuditLog)
  case object ViewChannel         extends NamedPermission("view_channel", Permission.ViewChannel)
  case object SendMessages        extends NamedPermission("send_messages", Permission.SendMessages)
  case object SendTtsMessages     extends NamedPermission("send_tts_messages", Permission.SendTtsMessages)
  case object ManageMessages      extends NamedPermission("manage_messages", Permission.ManageMessages)
  case object EmbedLinks          extends NamedPermission("embed_links", Permission.EmbedLinks)
  case object AttachFiles         extends NamedPermission("attach_files", Permission.AttachFiles)
  case object ReadMessageHistory  extends NamedPermission("read_message_history", Permission.ReadMessageHistory)
  case object MentionEveryone     extends NamedPermission("mention_everyone", Permission.MentionEveryone)
  case object UseExternalEmojis   extends NamedPermission("use_external_emojis", Permission.UseExternalEmojis)
  case object ViewGuildInsights   extends NamedPermission("view_guild_insights", Permission.ViewGuildInsights)
  case object Connect             extends NamedPermission("connect", Permission.Connect)
  case object Speak               extends NamedPermission("speak", Permission.Speak)
  case object MuteMembers         extends NamedPermission("mute_members", Permission.MuteMembers)
  case object DeafenMembers       extends NamedPermission("deafen_members", Permission.DeafenMembers)
  case object MoveMembers         extends NamedPermission("move_members", Permission.MoveMembers)
  case object UseVad              extends NamedPermission("use_vad", Permission.UseVad)
  case object PrioritySpeaker     extends NamedPermission("priority_speaker", Permission.PrioritySpeaker)
  case object Stream              extends NamedPermission("stream", Permission.Stream)
  case object ChangeNickname      extends NamedPermission("change_nickname", Permission.ChangeNickname)
  case object ManageNicknames     extends NamedPermission("manage_nicknames", Permission.ManageNicknames)
  case object ManageRoles         extends NamedPermission("manage_roles", Permission.ManageRoles)
  case object ManageWebhooks      extends NamedPermission("manage_webhooks", Permission.ManageWebhooks)
  case object ManageEmojis        extends NamedPermission("manage_emojis", Permission.ManageEmojis)

  def fromPermission(permission: Permission): Seq[NamedPermission] =
    values.filter(named => permission.hasPermissions(named.permission))

  def toPermission(named: Seq[NamedPermission]): Permission = Permission(named.map(_.permission): _*)

  implicit val codec: Codec[NamedPermission] = Codec.from(
    Decoder.decodeString.emap(s => valuesToEntriesMap.get(s).toRight(s"$s is not a valid permission")),
    Encoder.encodeString.contramap(_.value)
  )
}
