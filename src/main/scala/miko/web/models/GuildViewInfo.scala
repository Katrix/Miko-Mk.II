package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.{GatewayGuild, GuildId, GuildMember, User}

case class GuildViewInfo(
    guildId: GuildId,
    userInVChannel: Boolean,
    isAdmin: Boolean,
    guild: GatewayGuild,
    member: GuildMember,
    user: User,
    cache: CacheSnapshot
)
