package miko

import ackcord.CacheSnapshot.BotUser
import ackcord.data._
import ackcord.{MemoryCacheSnapshot, SnowflakeMap}
import io.circe._
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import shapeless.tag.@@

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

  implicit private lazy val botUserEncoder: Encoder[User @@ BotUser] = (a: User @@ BotUser) => (a: User).asJson
  implicit private lazy val botUserDecoder: Decoder[User @@ BotUser] = (c: HCursor) =>
    c.as[User].map(u => shapeless.tag[BotUser](u))

  implicit private lazy val cacheProcessorEncoder: Encoder[MemoryCacheSnapshot.CacheProcessor] =
    (_: MemoryCacheSnapshot.CacheProcessor) => Json.Null
  implicit private lazy val cacheProcessorDecoder: Decoder[MemoryCacheSnapshot.CacheProcessor] = (_: HCursor) =>
    Right(MemoryCacheSnapshot.defaultCacheProcessor)

  implicit lazy val cacheSnapshotCodec: Codec[MemoryCacheSnapshot] = deriveConfiguredCodec

  implicit lazy val messageActivityCodec: Codec[MessageActivity] = deriveConfiguredCodec

  implicit lazy val stickerCodec: Codec[Sticker] = deriveConfiguredCodec

  implicit lazy val messageCodec: Codec[Message] = deriveConfiguredCodec

  implicit lazy val guildChannelCodec: Codec[GuildChannel] = deriveConfiguredCodec

  //TODO: roleCodec should really be roleEncoder in DiscordProtocol
  //implicit val roleCodec: Codec[Role] = deriveConfiguredCodec

  implicit lazy val emojiCodec: Codec[Emoji] = deriveConfiguredCodec

  implicit lazy val guildMemberCodec: Codec[GuildMember] = deriveConfiguredCodec

  implicit lazy val activityPartyCodec: Codec[ActivityParty] = deriveConfiguredCodec

  implicit lazy val activityCodec: Codec[Activity] = deriveConfiguredCodec

  implicit lazy val presenceCodec: Codec[Presence] = deriveConfiguredCodec

  implicit lazy val guildCodec: Codec[GatewayGuild] = deriveConfiguredCodec

  implicit lazy val dmChannelCodec: Codec[DMChannel]                   = deriveConfiguredCodec
  implicit lazy val groupDMChannelCodec: Codec[GroupDMChannel]         = deriveConfiguredCodec
  implicit lazy val threadGuildChannelCodec: Codec[ThreadGuildChannel] = deriveConfiguredCodec

  implicit lazy val threadMemberCodec: Codec[ThreadMember] = deriveConfiguredCodec

  implicit lazy val banCodec: Codec[Ban] = deriveConfiguredCodec

}
object MikoProtocol extends MikoProtocol
