package miko.db

import java.time.OffsetDateTime

import cats.implicits._
import doobie._
import doobie.enum.JdbcType
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe._
import io.circe.syntax._
import ackcord.data._
import ackcord.data.DiscordProtocol._
import cats.effect.Bracket
import miko.settings.{GuildSettings, _}
import miko.web.forms.WebSettings
import org.postgresql.util.PGobject

object DBAccess {

  private implicit val messageCodec: Codec[Message] = {
    import io.circe.generic.auto._
    import io.circe.generic.semiauto._
    deriveCodec[Message]
  }

  implicit def snowflakeMeta[A]: Meta[SnowflakeType[A]] =
    Meta[Long].asInstanceOf[Meta[SnowflakeType[A]]]

  implicit def listSnowflakeMeta[A]: Meta[List[SnowflakeType[A]]] =
    Meta[Array[Long]].timap[List[Long]](_.toList)(_.toArray).asInstanceOf[Meta[List[SnowflakeType[A]]]]

  implicit val vtPermValueMeta: Meta[VTPermissionValue] =
    Meta.Advanced
      .other[PGobject]("VT_PERMISSION_VALUE")
      .timap { o =>
        Option(o).map { a =>
          val str    = a.getValue
          val values = str.drop(1).dropRight(1).split(",", 2)
          VTPermissionValue(Permission.fromLong(values(0).toLong), Permission.fromLong(values(1).toLong))
        }.orNull
      } { oa =>
        Option(oa).map { a =>
          val o = new PGobject
          o.setType("VT_PERMISSION_VALUE")
          o.setValue(s"(${a.allow},${a.deny})")
          o
        }.orNull

      }

  implicit val permissionMeta: Meta[Permission] = Meta[Long].asInstanceOf[Meta[Permission]]

  implicit val byteArrayMeta: Meta[IndexedSeq[Byte]] =
    Meta[Array[Byte]].timap[IndexedSeq[Byte]](_.toIndexedSeq)(_.toArray)

  implicit val cmdPermissionValueMeta: Meta[CmdPermissionValue] = pgEnumStringOpt[CmdPermissionValue](
    "CMD_PERMISSION_VALUE",
    CmdPermissionValue.fromString,
    CmdPermissionValue.asString
  )

  implicit val offsetDateTimeMeta: Meta[OffsetDateTime] = Meta.Basic.one(
    JdbcType.TimestampWithTimezone,
    List(JdbcType.Timestamp),
    _.getObject(_, classOf[OffsetDateTime]),
    _.setObject(_, _),
    _.updateObject(_, _)
  )

  private[db] def getGuildSettings[F[_]](
      guildId: GuildId
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[GuildSettings] =
    sql"""|SELECT guild_id,
          |       bot_spam_channel,
          |       staff_channel,
          |       default_music_volume,
          |       public_key,
          |       private_key_channel_id,
          |       private_key_msg_id,
          |       requires_mention,
          |       vt_enabled,
          |       vt_perms_everyone,
          |       vt_perms_join,
          |       vt_perms_leave,
          |       vt_dynamically_resize_channels,
          |       vt_destructive_enabled,
          |       vt_destructive_blacklist,
          |       vt_save_destructable
          |FROM guild_settings WHERE guild_id = $guildId""".stripMargin
      .query[GuildSettings]
      .option
      .transact(xa)
      .map(_.getOrElse(GuildSettings(guildId)))

  private[db] def insertEmptySettings[F[_]](
      guildId: GuildId
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[Int] =
    sql"INSERT INTO guild_settings (guild_id) VALUES ($guildId) ON CONFLICT DO NOTHING".update.run.transact(xa)

  private[db] def updateSettings[F[_]](guildId: GuildId, settings: WebSettings)(
      implicit xa: Transactor[F],
      F: Bracket[F, Throwable]
  ): F[Int] =
    sql"""|UPDATE guild_settings
          |SET bot_spam_channel               = ${settings.botSpam},
          |    staff_channel                  = ${settings.staffChat},
          |    vt_enabled                     = ${settings.vt.enabled},
          |    vt_perms_everyone              = (${settings.vt.perms.everyone.allow}, ${settings.vt.perms.everyone.deny}),
          |    vt_perms_join                  = (${settings.vt.perms.join.allow}, ${settings.vt.perms.join.deny}),
          |    vt_perms_leave                 = (${settings.vt.perms.leave.allow}, ${settings.vt.perms.leave.deny}),
          |    vt_dynamically_resize_channels = ${settings.vt.dynamicallyResizeChannels},
          |    vt_destructive_enabled         = ${settings.vt.destructive.enabled},
          |    vt_destructive_blacklist       = ${settings.vt.destructive.blacklist.toList},
          |    vt_save_destructable           = ${settings.vt.destructive.saveOnDestroy}
          |WHERE guild_id = $guildId;""".stripMargin.update.run.transact(xa)

  private[db] def updateDefaultVolume[F[_]](
      guildId: GuildId,
      volume: Int
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[Int] =
    sql"UPDATE guild_settings SET default_music_volume = $volume WHERE guild_id = $guildId".update.run.transact(xa)

  private[db] def updateKey[F[_]](
      guildId: GuildId,
      pub: String,
      privateChannelId: ChannelId,
      privateMsgId: MessageId
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[Int] =
    sql"""|UPDATE guild_settings SET public_key = dearmor($pub), private_key_channel_id = $privateChannelId,
          |private_key_msg_id = $privateMsgId WHERE guild_id = $guildId""".stripMargin.update.run.transact(xa)

  def insertVTMsg[F[_]](
      msg: Message,
      channel: TGuildChannel,
      category: Option[GuildCategory],
      user: User,
      key: IndexedSeq[Byte]
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[Int] =
    sql"""|INSERT INTO vt_channel_messages (message_id, created_at, content, guild_id, channel_name,
          |category_id, category_name, user_id, user_name) VALUES (${msg.id}, ${msg.timestamp},
          |pgp_pub_encrypt(${msg.asJson.noSpaces}, $key, 'compress-algo=1'),
          |${channel.guildId}, ${channel.name}, ${category.map(_.id)}, ${category.map(_.name)}, ${user.id}, 
          |${user.username})""".stripMargin.update.run.transact(xa)

  def updateVTMsg[F[_]](
      msg: Message,
      channel: TGuildChannel,
      category: Option[GuildCategory],
      user: User,
      key: IndexedSeq[Byte]
  )(implicit xa: Transactor[F], F: Bracket[F, Throwable]): F[Int] =
    sql"""|UPDATE vt_channel_messages SET updated_at = ${msg.editedTimestamp},
          |content = pgp_pub_encrypt(${msg.asJson.noSpaces}, $key, 'compress-algo=1'),
          |guild_id = ${channel.guildId},
          |channel_name = ${channel.name}, category_id = ${category.map(_.id)}, category_name = ${category.map(_.name)},
          |user_id = ${user.id}, user_name = ${user.username} WHERE message_id = ${msg.id}""".stripMargin.update.run
      .transact(xa)

  def deleteVTMsg[F[_]](msg: Message)(
      implicit xa: Transactor[F],
      F: Bracket[F, Throwable]
  ): F[Int] = sql"""DELETE FROM vt_channel_messages WHERE message_id = ${msg.id}""".update.run.transact(xa)
}
