package miko.web.controllers

import java.time.{LocalDate, LocalTime, Month, OffsetDateTime, ZoneOffset}

import scala.concurrent.ExecutionContext
import scala.util.Random
import play.api.i18n.I18nSupport
import ackcord.data._
import ackcord.{CacheSnapshot, MemoryCacheSnapshot, SnowflakeMap}
import miko.dummy.{CommandDummyData, MusicDummyData}
import miko.settings.GuildSettings
import miko.web.models.GuildViewInfo
import play.api.mvc._
import miko.web.forms.WebSettings
import play.twirl.api.Html

class GuestController(
    implicit
    controllerComponents: ControllerComponents
) extends AbstractController(controllerComponents)
    with I18nSupport { outer =>

  implicit lazy val executionContext: ExecutionContext = defaultExecutionContext

  implicit val info: GuildViewInfo = {
    val guildId = GuildId(RawSnowflake("123456789"))
    val botId   = UserId(RawSnowflake("987654321"))
    val botUser = User(
      id = botId,
      username = "Test user",
      discriminator = "1234",
      avatar = None,
      bot = Some(false),
      mfaEnabled = Some(false),
      verified = Some(true),
      email = None,
      flags = None,
      premiumType = None,
      system = None,
      locale = None
    )

    val users = SnowflakeMap.from(scala.Range(0, 99).map { i =>
      UserId(i) -> User(
        id = UserId(i),
        username = s"User$i",
        discriminator = i.toString.padTo(4, '1'),
        avatar = None,
        bot = Some(false),
        mfaEnabled = Some(false),
        verified = Some(true),
        email = None,
        flags = None,
        premiumType = None,
        system = None,
        locale = None
      )
    }) + (botId -> botUser)

    val adminRole = Role(
      id = RoleId(0),
      guildId = guildId,
      name = "Admin",
      color = 0x000000,
      hoist = true,
      position = 0,
      permissions = Permission.All,
      managed = false,
      mentionable = true
    )

    val roles = SnowflakeMap.from(scala.Range(1, 10).map { i =>
      RoleId(i) -> Role(
        id = RoleId(i),
        guildId = guildId,
        name = s"Role$i",
        color = 0xFFFFFF,
        hoist = Random.nextBoolean(),
        position = i,
        permissions = Permission.fromLong(Random.nextLong() % 0x40000000).removePermissions(Permission.Administrator),
        managed = false,
        mentionable = Random.nextBoolean()
      )
    }) + (RoleId(0) -> adminRole)

    val joinData = OffsetDateTime.of(LocalDate.of(2017, Month.MARCH, 31), LocalTime.of(1, 24), ZoneOffset.ofHours(2))

    val botMember = GuildMember(
      userId = botId,
      guildId = guildId,
      nick = None,
      roleIds = Seq(RoleId(0)),
      joinedAt = joinData,
      deaf = false,
      mute = false,
      premiumSince = None
    )

    val members = users.map {
      case (k, _) =>
        k -> GuildMember(
          userId = k,
          guildId = guildId,
          nick = None,
          roleIds = Random.shuffle(roles.keys).take(Random.nextInt(3)).toSeq,
          joinedAt = OffsetDateTime.now(),
          deaf = false,
          mute = false,
          premiumSince = None
        )
    } + (botId -> botMember)

    val messages = SnowflakeMap.from(
      scala.Range(0, 10).map { i =>
        ChannelId(i).asChannelId[TextChannel] -> SnowflakeMap.from(scala.Range(0, 100).map {
          j =>
          val userId = members.keys.toSeq(Random.nextInt(members.size))
            MessageId(j) -> GuildMessage(
              id = MessageId(j),
              channelId = TextGuildChannelId(i),
              guildId = guildId,
              authorId = RawSnowflake(userId),
              member = members(userId),
              isAuthorUser = true,
              content = Random.alphanumeric.take(Random.nextInt(16)).mkString,
              timestamp = OffsetDateTime.now(),
              editedTimestamp = None,
              tts = false,
              mentionEveryone = false,
              mentions = Nil,
              mentionRoles = Nil,
              attachment = Nil,
              embeds = Nil,
              reactions = Nil,
              nonce = None,
              pinned = false,
              messageType = MessageType.Default,
              activity = None,
              application = None,
              mentionChannels = Nil,
              messageReference = None,
              flags = None
            )
        })
      }
    )

    val tChannels = SnowflakeMap.from(scala.Range(0, 10).map { i =>
      ChannelId(i).asChannelId[NormalTextGuildChannel] -> NormalTextGuildChannel(
        id = ChannelId(i).asChannelId,
        guildId = guildId,
        name = s"TChannel$i",
        position = i,
        permissionOverwrites = SnowflakeMap.empty,
        topic = None,
        lastMessageId = Some(messages(TextChannelId(i)).lastKey),
        rateLimitPerUser = None,
        nsfw = false,
        parentId = None,
        lastPinTimestamp = None
      )
    })

    val vChannels = SnowflakeMap.from(scala.Range(10, 15).map { i =>
      VoiceGuildChannelId(i) -> VoiceGuildChannel(
        id = ChannelId(i).asChannelId,
        guildId = guildId,
        name = s"VChannel$i",
        position = i,
        permissionOverwrites = SnowflakeMap.empty,
        bitrate = 96000,
        userLimit = 99,
        nsfw = false,
        parentId = None
      )
    })

    val channels = tChannels ++ vChannels

    val voiceStates = SnowflakeMap.from(Random.shuffle(users.keys.toSeq).take(Random.nextInt(50)).map { k =>
      k -> VoiceState(
        guildId = Some(guildId),
        channelId = Some(vChannels.keys.toSeq(Random.nextInt(5))),
        userId = k,
        member = None,
        sessionId = "some_session_id",
        deaf = false,
        mute = false,
        selfDeaf = false,
        selfMute = false,
        suppress = false,
        selfStream = None
      )
    })

    val guild = Guild(
      id = guildId,
      name = "Test Guild",
      icon = None,
      splash = None,
      isOwner = Some(true),
      ownerId = botId,
      permissions = None,
      region = "some_region",
      afkChannelId = None,
      afkTimeout = 999,
      embedEnabled = None,
      embedChannelId = None,
      verificationLevel = VerificationLevel.Low,
      defaultMessageNotifications = NotificationLevel.OnlyMentions,
      explicitContentFilter = FilterLevel.Disabled,
      roles = roles,
      emojis = SnowflakeMap.empty,
      features = Nil,
      mfaLevel = MFALevel.NoneMFA,
      applicationId = None,
      widgetEnabled = None,
      widgetChannelId = None,
      systemChannelId = None,
      joinedAt = joinData,
      large = false,
      memberCount = members.size,
      voiceStates = voiceStates,
      members = members,
      channels = SnowflakeMap.from(channels), //TODO: Explore why this broke in AckCord
      presences = SnowflakeMap.empty,
      maxPresences = 1000,
      maxMembers = None,
      vanityUrlCode = None,
      description = None,
      banner = None,
      premiumTier = PremiumTier.None,
      premiumSubscriptionCount = Some(0),
      preferredLocale = None,
      discoverySplash = None,
      systemChannelFlags = SystemChannelFlags.None,
      rulesChannelId = None,
      publicUpdatesChannelId = None
    )

    GuildViewInfo(
      isGuest = true,
      guildId,
      userInVChannel = true,
      isAdmin = true,
      guild,
      botUser,
      botMember,
      MemoryCacheSnapshot(
        botUser = shapeless.tag[CacheSnapshot.BotUser](botUser),
        dmChannelMap = SnowflakeMap.empty,
        groupDmChannelMap = SnowflakeMap.empty,
        unavailableGuildMap = SnowflakeMap.empty,
        guildMap = SnowflakeMap(guildId -> guild),
        messageMap = messages,
        lastTypedMap = SnowflakeMap.empty,
        userMap = users,
        banMap = SnowflakeMap.empty,
        seq = 0,
        creationProcessor = MemoryCacheSnapshot.defaultCacheProcessor
      )
    )
  }

  def guildHome: Action[AnyContent] = Action { implicit request =>
    Ok(Html(miko.web.views.guildHome()))
  }

  def help: Action[AnyContent] = Action { implicit request =>
    Ok(Html(miko.web.views.help(CommandDummyData)))
  }

  def music: Action[AnyContent] = Action { implicit request =>
    Ok(Html(miko.web.views.music(MusicDummyData)))
  }

  def settings: Action[AnyContent] = Action { implicit request =>
    Ok(Html(miko.web.views.settings(GuildSettings(info.guild.id), WebSettings.settingsForm)))
  }

  def log: Action[AnyContent] = TODO
}
