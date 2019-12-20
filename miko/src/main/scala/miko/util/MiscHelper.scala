package miko.util

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildMember, TGuildChannel}
import ackcord.syntax._
import miko.settings.GuildSettings

object MiscHelper {

  def canHandlerMember(
      guild: Guild,
      member: GuildMember
  )(implicit c: CacheSnapshot): Boolean =
    guild.memberById(c.botUser.id).exists(_.hasRoleAboveId(guild, member))

  def botSpamChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TGuildChannel] =
    guildSettings.botSpamChannel.flatMap(guild.tChannelById)

  def botSpamChannel(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TGuildChannel] =
    guildSettings.botSpamChannel
      .flatMap(c.getGuildChannel(guildSettings.guildId, _))
      .flatMap(_.asTGuildChannel)

  def staffChannel(guild: Guild)(implicit guildSettings: GuildSettings): Option[TGuildChannel] =
    guildSettings.staffChannel.flatMap(guild.tChannelById)

  def staffChannel(
      implicit guildSettings: GuildSettings,
      c: CacheSnapshot,
  ): Option[TGuildChannel] =
    guildSettings.staffChannel
      .flatMap(c.getGuildChannel(guildSettings.guildId, _))
      .flatMap(_.asTGuildChannel)
}
