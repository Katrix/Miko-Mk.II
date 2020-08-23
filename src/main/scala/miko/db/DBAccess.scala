package miko.db

import java.time.OffsetDateTime

import ackcord.data._
import cats.effect.Bracket
import doobie._
import doobie.enum.JdbcType
import doobie.implicits._
import io.circe._
import io.circe.syntax._
import miko.MikoProtocol._

class DBAccess[F[_]](implicit xa: Transactor[F], F: Bracket[F, Throwable]) {

  implicit private val messageCodec: Codec[Message] = {
    import io.circe.generic.semiauto._
    deriveCodec[Message]
  }

  implicit def snowflakeMeta[A]: Meta[SnowflakeType[A]] =
    Meta[Long].asInstanceOf[Meta[SnowflakeType[A]]]

  implicit val byteArrayMeta: Meta[IndexedSeq[Byte]] =
    Meta[Array[Byte]].timap[IndexedSeq[Byte]](_.toIndexedSeq)(_.toArray)

  implicit val offsetDateTimeMeta: Meta[OffsetDateTime] = Meta.Basic.one(
    JdbcType.TimestampWithTimezone,
    List(JdbcType.Timestamp),
    _.getObject(_, classOf[OffsetDateTime]),
    _.setObject(_, _),
    _.updateObject(_, _)
  )

  def insertVTMsg(
      msg: Message,
      channel: TextGuildChannel,
      category: Option[GuildCategory],
      user: User,
      key: String
  ): F[Int] =
    sql"""|INSERT INTO vt_channel_messages (message_id, created_at, content, guild_id, channel_name,
          |category_id, category_name, user_id, user_name) VALUES (${msg.id}, ${msg.timestamp},
          |pgp_pub_encrypt(${msg.asJson.noSpaces}, dearmor($key), 'compress-algo=1'),
          |${channel.guildId}, ${channel.name}, ${category.map(_.id)}, ${category.map(_.name)}, ${user.id}, 
          |${user.username})""".stripMargin.update.run.transact(xa)

  def updateVTMsg(
      msg: Message,
      channel: TextGuildChannel,
      category: Option[GuildCategory],
      user: User,
      key: String
  ): F[Int] =
    sql"""|UPDATE vt_channel_messages SET updated_at = ${msg.editedTimestamp},
          |content = pgp_pub_encrypt(${msg.asJson.noSpaces}, dearmor($key), 'compress-algo=1'),
          |guild_id = ${channel.guildId},
          |channel_name = ${channel.name}, category_id = ${category.map(_.id)}, category_name = ${category.map(_.name)},
          |user_id = ${user.id}, user_name = ${user.username} WHERE message_id = ${msg.id}""".stripMargin.update.run
      .transact(xa)

  def deleteVTMsg(msgId: MessageId): F[Int] =
    sql"""DELETE FROM vt_channel_messages WHERE message_id = $msgId""".update.run.transact(xa)
}
