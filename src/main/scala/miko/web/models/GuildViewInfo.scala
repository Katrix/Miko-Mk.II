package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.{GatewayGuild, GuildId, GuildMember, User, VoiceGuildChannelId}

case class GuildViewInfo(
    guildId: GuildId,
    userInVChannel: Option[VoiceGuildChannelId],
    isAdmin: Boolean,
    guild: GatewayGuild,
    member: GuildMember,
    user: User,
    cache: CacheSnapshot
)
