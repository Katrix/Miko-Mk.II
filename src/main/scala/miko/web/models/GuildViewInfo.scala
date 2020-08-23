package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildId, GuildMember, User}

case class GuildViewInfo(
    guildId: GuildId,
    userInVChannel: Boolean,
    isAdmin: Boolean,
    guild: Guild,
    member: GuildMember,
    user: User,
    cache: CacheSnapshot,
)
