package miko.db

import cats.syntax.apply._
import doobie.Transactor
import ackcord.data.{ChannelId, GuildId, MessageId}
import cats.effect.Bracket
import miko.settings._
import miko.web.forms.WebSettings
import scalacache._

object DBMemoizedAccess {

  def getGuildSettings[F[_]: Mode: Transactor](
      guildId: GuildId
  )(implicit cache: Cache[GuildSettings], F: Bracket[F, Throwable]): F[GuildSettings] =
    cache.cachingF(guildId)(None)(DBAccess.getGuildSettings(guildId))

  def insertEmptySettings[F[_]: Mode: Transactor](guildId: GuildId)(
      implicit
      guildSettingsCache: Cache[GuildSettings],
      F: Bracket[F, Throwable]
  ): F[Int] =
    guildSettingsCache.remove(guildId) *>
      DBAccess.insertEmptySettings(guildId)

  def updateSettings[F[_]: Mode: Transactor](guildId: GuildId, settings: WebSettings)(
      implicit
      guildSettingsCache: Cache[GuildSettings],
      F: Bracket[F, Throwable]
  ): F[Int] =
    guildSettingsCache.remove(guildId) *> DBAccess.updateSettings(guildId, settings)

  def updateDefaultVolume[F[_]: Mode: Transactor](guildId: GuildId, volume: Int)(
      implicit cache: Cache[GuildSettings],
      F: Bracket[F, Throwable]
  ): F[Int] =
    cache.remove(guildId) *> DBAccess.updateDefaultVolume(guildId, volume)

  def updateKey[F[_]: Mode: Transactor](
      guildId: GuildId,
      pub: String,
      privateChannelId: ChannelId,
      privateMsgId: MessageId
  )(implicit cache: Cache[GuildSettings], F: Bracket[F, Throwable]): F[Int] =
    cache.remove(guildId) *> DBAccess.updateKey(guildId, pub, privateChannelId, privateMsgId)

}
