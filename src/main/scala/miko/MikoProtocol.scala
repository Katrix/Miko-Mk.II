package miko

import ackcord.SnowflakeMap
import ackcord.data._
import io.circe._
import io.circe.generic.extras.semiauto._

import scala.util.Try

trait MikoProtocol extends DiscordProtocol {

  implicit def snowflakeMapEncoder[A, B](
      implicit keyEncoder: KeyEncoder[SnowflakeType[A]],
      valueEncoder: Encoder[B]
  ): Encoder[SnowflakeMap[A, B]] =
    Encoder[Map[SnowflakeType[A], B]].contramap(identity)

  implicit def snowflakeMapDecoder[A, B](
      implicit keyDecoder: KeyDecoder[SnowflakeType[A]],
      valueDecoder: Decoder[B]
  ): Decoder[SnowflakeMap[A, B]] = Decoder[Map[SnowflakeType[A], B]].map(m => SnowflakeMap(m.toSeq: _*))

  implicit def snowflakeKeyEncoder[A]: KeyEncoder[SnowflakeType[A]] =
    KeyEncoder.instance(_.asString)
  implicit def snowflakeKeyDecoder[A]: KeyDecoder[SnowflakeType[A]] =
    KeyDecoder.instance(s => Try(RawSnowflake(s)).toOption.map(SnowflakeType[A]))

  implicit val messageActivityCodec: Codec[MessageActivity] = deriveConfiguredCodec

  implicit val messageCodec: Codec[Message] = deriveConfiguredCodec

  implicit val guildChannelCodec: Codec[GuildChannel] = deriveConfiguredCodec

  //TODO: roleCodec should really be roleEncoder in DiscordProtocol
  //implicit val roleDecoder: Codec[Role] = deriveConfiguredCodec

  implicit val emojiCodec: Codec[Emoji] = deriveConfiguredCodec

  implicit val guildMemberCodec: Codec[GuildMember] = deriveConfiguredCodec

  implicit val activityPartyCodec: Codec[ActivityParty] = deriveConfiguredCodec

  implicit val activityCodec: Codec[Activity] = deriveConfiguredCodec

  implicit val presenceCodec: Codec[Presence] = deriveConfiguredCodec

  implicit val guildCodec: Codec[Guild] = deriveConfiguredCodec

  implicit val banCodec: Codec[Ban]     = deriveConfiguredCodec
}
object MikoProtocol extends MikoProtocol
