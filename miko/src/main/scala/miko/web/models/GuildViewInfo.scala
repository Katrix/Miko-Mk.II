package miko.web.models

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildId, GuildMember, User}

case class GuildViewInfo(
    isGuest: Boolean,
    guildId: GuildId,
    userInVChannel: Boolean,
    isAdmin: Boolean,
    guild: Guild,
    user: User,
    member: GuildMember,
    cache: CacheSnapshot,
)
