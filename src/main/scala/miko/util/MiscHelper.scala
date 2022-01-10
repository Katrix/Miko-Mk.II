package miko.util

import ackcord.CacheSnapshot
import ackcord.data.{GatewayGuild, GuildId, GuildMember, TextGuildChannel}
import ackcord.syntax._
import miko.settings.GuildSettings

object MiscHelper {

  def canHandlerMember(
      guild: GatewayGuild,
      member: GuildMember
  )(implicit c: CacheSnapshot): Boolean =
    guild.memberById(c.botUser.id).exists(_.hasRoleAboveId(guild, member))

  def botSpamChannel(guild: GatewayGuild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.channels.botSpamChannel.flatMap(guild.textChannelById)

  def botSpamChannel(guildId: GuildId)(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.channels.botSpamChannel.flatMap(_.resolve(guildId))

  def staffChannel(guild: GatewayGuild)(implicit guildSettings: GuildSettings): Option[TextGuildChannel] =
    guildSettings.channels.staffChannel.flatMap(guild.textChannelById)

  def staffChannel(guildId: GuildId)(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TextGuildChannel] =
    guildSettings.channels.staffChannel.flatMap(_.resolve(guildId))
}
