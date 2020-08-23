package miko.util

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildId, GuildMember, TextGuildChannel}
import ackcord.syntax._
import miko.settings.GuildSettings

object MiscHelper {

  def canHandlerMember(
      guild: Guild,
      member: GuildMember
  )(implicit c: CacheSnapshot): Boolean =
    guild.memberById(c.botUser.id).exists(_.hasRoleAboveId(guild, member))

  def botSpamChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.channels.botSpamChannel.flatMap(guild.textChannelById)

  def botSpamChannel(guildId: GuildId)(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.channels.botSpamChannel.flatMap(_.resolve(guildId))

  def staffChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.channels.staffChannel.flatMap(guild.textChannelById)

  def staffChannel(guildId: GuildId)(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.channels.staffChannel.flatMap(_.resolve(guildId))
}
