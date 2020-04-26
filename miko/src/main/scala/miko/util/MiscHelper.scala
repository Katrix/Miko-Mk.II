package miko.util

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildMember, TextGuildChannel}
import ackcord.syntax._
import miko.settings.GuildSettings

object MiscHelper {

  def canHandlerMember(
      guild: Guild,
      member: GuildMember
  )(implicit c: CacheSnapshot): Boolean =
    guild.memberById(c.botUser.id).exists(_.hasRoleAboveId(guild, member))

  def botSpamChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.botSpamChannel.flatMap(guild.textChannelById)

  def botSpamChannel(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.botSpamChannel.flatMap(_.resolve(guildSettings.guildId))

  def staffChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.staffChannel.flatMap(guild.textChannelById)

  def staffChannel(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.staffChannel.flatMap(_.resolve(guildSettings.guildId))
}
