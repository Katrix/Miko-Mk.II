package miko.logging

import ackcord.data._
import ackcord.requests._
import ackcord.{APIMessage, CacheSnapshot}
import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.github.difflib.text.DiffRowGenerator
import miko.util.Color

import java.time.{Instant, OffsetDateTime}
import scala.jdk.CollectionConverters._

object LogStream {

  sealed trait LogElement
  case class GuildLogElement(
      apiMessage: APIMessage,
      forGuild: GuildId,
      auditLogEvent: Seq[AuditLogEvent],
      whenHappened: Instant,
      makeEmbed: AuditLog => OutgoingEmbed,
      removeIfEmpty: Boolean
  )(implicit val c: CacheSnapshot)
      extends LogElement
  case class UserUpdateLogElement(
      apiMessage: APIMessage,
      userId: UserId,
      whenHappened: Instant,
      makeEmbed: () => OutgoingEmbed
  ) extends LogElement

  private val differ = DiffRowGenerator
    .create()
    .showInlineDiffs(true)
    .reportLinesUnchanged(true)
    .mergeOriginalRevised(true)
    .inlineDiffByWord(true)
    .build()

  def makeDiff(oldContent: String, newContent: String): String = {
    val diffRows = differ.generateDiffRows(oldContent.linesIterator.toSeq.asJava, newContent.linesIterator.toSeq.asJava)
    diffRows.asScala.map(_.getOldLine).mkString("\n")
  }

  def findEntryCauseUser(entry: Option[AuditLogEntry])(implicit c: CacheSnapshot, log: AuditLog): Option[User] =
    entry.flatMap(_.userId).flatMap(id => id.resolve.orElse(log.users.find(_.id == id)))

  def printChannel(channel: GuildChannel, mentionsWork: Boolean = true): String =
    if (mentionsWork) s"${channel.mention}(#${channel.name})" else s"#${channel.name} (${channel.id.asString})"

  def printChannelId(guild: GatewayGuild, channelId: GuildChannelId, mentionsWork: Boolean = true)(
      implicit auditLog: AuditLog
  ): String = {
    val channelName = guild.channels
      .get(channelId)
      .orElse(auditLog.threads.collectFirst {
        case channel if channel.id == channelId => channel.toGuildChannel(guild.id, None)
      }.flatten)
      .fold("<not found>")(_.name)
    if (mentionsWork) s"${channelId.mention}(#$channelName)" else s"#$channelName (${channelId.asString})"
  }

  def printUser(user: User, mentionsWork: Boolean = true): String =
    if (mentionsWork) s"${user.mention}(@${user.username})" else s"@${user.username} (${user.id.asString})"

  def printUserId(id: UserId, mentionsWork: Boolean = true)(implicit c: CacheSnapshot, auditLog: AuditLog): String = {
    val userName = c.getUser(id).orElse(auditLog.users.find(_.id == id)).fold("<not found>")(_.username)
    if (mentionsWork) s"${id.mention}(@$userName)" else s"@$userName (${id.asString})"
  }

  def printRole(role: Role, mentionsWork: Boolean = true): String =
    if (mentionsWork) s"${role.mention}(@${role.name})" else s"@${role.name} (${role.id})"

  def printRoleId(guild: GatewayGuild, roleId: RoleId, mentionsWork: Boolean = true): String = {
    val roleName = guild.roles.get(roleId).fold("Unknown")(_.name)
    if (mentionsWork) s"${roleId.mention}(@$roleName)" else s"@$roleName (${roleId.asString})"
  }

  def printEmoji(emoji: PartialEmoji): String = emoji match {
    case PartialEmoji(None, Some(name), _)                                         => name
    case PartialEmoji(Some(id), Some(name), animated) if animated.getOrElse(false) => s"<a:$name:$id>(:$name:)"
    case PartialEmoji(Some(id), Some(name), _)                                     => s"<:$name:$id>(:$name:)"
    case _                                                                         => "<not found>"
  }

  def printRequest(request: ImageRequest): String = {
    val uri       = request.route.uri
    val extension = request.format.extension
    val res       = uri.withQuery(uri.query().filter(t => t._1 != "size")).toString()
    res.reverse.replaceFirst(extension.reverse, s".$extension".reverse).reverse
  }

  def jumpToMessageField(guild: GatewayGuild, channelId: TextChannelId, messageId: MessageId): EmbedField =
    EmbedField(
      "Jump to message",
      s"https://discord.com/channels/${guild.id.asString}/${channelId.asString}/${messageId.asString}"
    )

  def getAuditLogEntry[A](
      log: AuditLog,
      actionType: Seq[AuditLogEvent],
      targetId: Option[SnowflakeType[A]],
      filterAuditLogEntries: AuditLogEntry => Boolean = _ => true
  ): Option[AuditLogEntry] =
    log.auditLogEntries
      .collectFirst {
        case entry
            if actionType.contains(entry.actionType) && targetId.forall(entry.targetId.contains) && filterAuditLogEntries(
              entry
            ) =>
          entry
      }

  def auditLogFields[A](
      apiMessage: APIMessage,
      guild: GatewayGuild,
      log: AuditLog,
      actionType: Seq[AuditLogEvent],
      targetId: Option[SnowflakeType[A]],
      filterAuditLogEntries: AuditLogEntry => Boolean = _ => true,
      printChanges: Boolean = true,
      makeOptionalInfoFields: OptionalAuditLogInfo => Seq[EmbedField] = _ => Nil
  )(
      implicit c: CacheSnapshot
  ): Seq[EmbedField] = {
    implicit val impLog: AuditLog = log
    getAuditLogEntry(log, actionType, targetId, filterAuditLogEntries).toSeq
      .flatMap { entry =>
        val changes = entry.changes.toSeq.flatten

        def printRole(roleId: RoleId): String = LogStream.printRoleId(guild, roleId)

        def printChannel(channelId: GuildChannelId): String = LogStream.printChannelId(guild, channelId)

        def printUser(userId: UserId): String =
          log.users
            .find(_.id == userId)
            .orElse(c.getUser(userId))
            .fold(LogStream.printUserId(userId))(LogStream.printUser(_))

        def printPermissions(newPermissions: Permission, oldPermissions: Permission): String = {
          val permNames = Seq(
            Permission.CreateInstantInvite     -> "CreateInstantInvite",
            Permission.KickMembers             -> "KickMembers",
            Permission.BanMembers              -> "BanMembers",
            Permission.Administrator           -> "Administrator",
            Permission.ManageChannels          -> "ManageChannels",
            Permission.ManageGuild             -> "ManageGuild",
            Permission.AddReactions            -> "AddReactions",
            Permission.ViewAuditLog            -> "ViewAuditLog",
            Permission.PrioritySpeaker         -> "PrioritySpeaker",
            Permission.Stream                  -> "Stream",
            Permission.ViewChannel             -> "ViewChannel",
            Permission.SendMessages            -> "SendMessages",
            Permission.SendTtsMessages         -> "SendTtsMessages",
            Permission.ManageMessages          -> "ManageMessages",
            Permission.EmbedLinks              -> "EmbedLinks",
            Permission.AttachFiles             -> "AttachFiles",
            Permission.ReadMessageHistory      -> "ReadMessageHistory",
            Permission.MentionEveryone         -> "MentionEveryone",
            Permission.UseExternalEmojis       -> "UseExternalEmojis",
            Permission.ViewGuildInsights       -> "ViewGuildInsights",
            Permission.Connect                 -> "Connect",
            Permission.Speak                   -> "Speak",
            Permission.MuteMembers             -> "MuteMembers",
            Permission.DeafenMembers           -> "DeafenMembers",
            Permission.MoveMembers             -> "MoveMembers",
            Permission.UseVad                  -> "UseVad",
            Permission.ChangeNickname          -> "ChangeNickname",
            Permission.ManageNicknames         -> "ManageNicknames",
            Permission.ManageRoles             -> "ManageRoles",
            Permission.ManageWebhooks          -> "ManageWebhooks",
            Permission.ManageEmojisAndStickers -> "ManageEmojisAndStickers",
            Permission.UseApplicationCommands  -> "UseApplicationCommands",
            Permission.RequestToSpeak          -> "RequestToSpeak",
            Permission.ManageEvents            -> "ManageEvents",
            Permission.ManageThreads           -> "ManageThreads",
            Permission.CreatePublicThreads     -> "CreatePublicThreads",
            Permission.CreatePrivateThreads    -> "CreatePrivateThreads",
            Permission.UseExternalStickers     -> "UseExternalStickers",
            Permission.SendMessagesInThreads   -> "SendMessagesInThreads",
            Permission.StartEmbeddedActivities -> "StartEmbeddedActivities"
          )

          val changes = permNames.flatMap {
            case (perm, name) =>
              val hadBefore = oldPermissions.hasPermissions(perm)
              val hasNow    = newPermissions.hasPermissions(perm)

              if (hadBefore && hasNow || !hadBefore && !hasNow) Nil
              else if (hadBefore && !hasNow) Seq(s"-$name")
              else Seq(s"+$name")
          }

          if (changes.nonEmpty) changes.mkString("\n") else "None"
        }

        def printPermissionOverwrite(newOverwrite: PermissionOverwrite, oldOverwrite: PermissionOverwrite): String = {
          val allow = printPermissions(newOverwrite.allow, oldOverwrite.allow)
          val deny  = printPermissions(newOverwrite.deny, oldOverwrite.deny)

          val noAllow = allow == "None"
          val noDeny  = deny == "None"

          if (noAllow && noDeny) "No changes"
          else if (noAllow) s"Deny:\n$deny"
          else if (noDeny) s"Allow:\n$deny"
          else {
            s"""|Allow:
                |$allow
                |
                |Deny:
                |$deny""".stripMargin
          }
        }

        def printGuildRequest(makeRequest: (Int, ImageFormat, GuildId, String) => ImageRequest)(hash: String): String =
          printRequest(makeRequest(1, ImageFormat.PNG, guild.id, hash))

        def changeFieldValue[B](change: Option[B], print: B => String): String =
          change.map(print).filter(_.nonEmpty).getOrElse("Unknown")

        def changeFields[B](
            change: AuditLogChange[B],
            name: String,
            print: B => String = (_: B).toString
        ): Seq[EmbedField] =
          Seq(
            EmbedField(s"Old $name", changeFieldValue(change.oldValue, print)),
            EmbedField(s"New $name", changeFieldValue(change.newValue, print))
          )

        def changeField[B](
            change: AuditLogChange[B],
            name: String,
            print: B => String = (_: B).toString
        ): Seq[EmbedField] =
          Seq(
            EmbedField(
              name.capitalize,
              s"${changeFieldValue(change.oldValue, print)} -> ${changeFieldValue(change.newValue, print)}"
            )
          )

        def allowDenyPermissionOverwriteFor(
            change: AuditLogChange[Permission],
            accessPerm: PermissionOverwrite => Permission
        ): String = {
          apiMessage match {
            case apiMessage: APIMessage.ChannelMessage =>
              val channelId  = apiMessage.channel.id.asChannelId[GuildChannel]
              val oldChannel = channelId.resolve(guild.id)(apiMessage.cache.previous)
              val newChannel = channelId.resolve(guild.id)(apiMessage.cache.current)

              def filterOverwrites(channel: Option[GuildChannel], optPerm: Option[Permission]) =
                channel.flatMap(
                  channel => optPerm.map(perm => channel.permissionOverwrites.filter(t => accessPerm(t._2) == perm))
                )

              val oldOverwritesOpt = filterOverwrites(oldChannel, change.oldValue)
              val newOverwritesOpt = filterOverwrites(newChannel, change.newValue)

              val overwrites = (oldOverwritesOpt, newOverwritesOpt) match {
                case (None, None)             => Nil
                case (None, Some(overwrites)) => overwrites.values.toSeq
                case (Some(overwrites), None) => overwrites.values.toSeq
                case (Some(oldOverwrites), Some(newOverwrites)) =>
                  val oldKeySet  = oldOverwrites.view.map(t => t._1 -> t._2.`type`).toSet
                  val newKeySet  = newOverwrites.view.map(t => t._1 -> t._2.`type`).toSet
                  val bothKeySet = oldKeySet & newKeySet

                  oldOverwrites.view.filter(t => bothKeySet(t._1, t._2.`type`)).values.toSeq
              }

              val pickedOverwrite =
                if (overwrites.length <= 1) overwrites.headOption
                else {
                  changes
                    .collect {
                      case AuditLogChange.Id(oldValue, newValue)
                          if oldValue.forall(id => overwrites.exists(_.id == id)) ||
                            newValue.forall(id => overwrites.exists(_.id == id)) =>
                        val possibleValues = oldValue.toSeq.flatMap(id => overwrites.filter(_.id == id)) ++
                          newValue.toSeq.flatMap(id => overwrites.filter(_.id == id))

                        //Just pick something if we're still left with multiple options at this point
                        possibleValues.headOption
                    }
                    .flatten
                    .headOption
                }

              pickedOverwrite.fold("<unknown>")(
                overwrite =>
                  overwrite.`type` match {
                    case PermissionOverwriteType.Role       => printRoleId(guild, RoleId(overwrite.id), mentionsWork = false)
                    case PermissionOverwriteType.Member     => printUserId(UserId(overwrite.id), mentionsWork = false)
                    case PermissionOverwriteType.Unknown(i) => "<unknown type>"
                  }
              )
          }
        }

        val standardFields = Seq(
          entry.reason.filter(_.nonEmpty).map(r => EmbedField("Reason", r)),
          findEntryCauseUser(Some(entry)).map(u => EmbedField("Event causer", printUser(u.id))),
          apiMessage match {
            case apiMessage: APIMessage.MessageMessage =>
              Some(
                EmbedField(
                  "Message owner",
                  apiMessage.message.authorUserId.fold(s"${apiMessage.message.authorUsername} (Webhook)")(printUser)
                )
              )
            case _ => None
          }
        ).flatMap(_.toSeq)
        val optionalInfoFields = entry.options.map(makeOptionalInfoFields).toSeq.flatten

        lazy val changesFields = changes.flatMap {
          case change: AuditLogChange.AfkChannelId => changeFields(change, "afk channel", printChannel)
          case change: AuditLogChange.AfkTimeout   => changeField(change, "afk timeout")
          case change: AuditLogChange.Allow =>
            Seq(
              EmbedField(
                s"Allowed permissions for ${allowDenyPermissionOverwriteFor(change, _.allow)}",
                printPermissions(change.oldValue.getOrElse(Permission.None), change.newValue.getOrElse(Permission.None))
              )
            )
          case _: AuditLogChange.ApplicationId            => Nil
          case change: AuditLogChange.Archived            => changeField(change, "archived")
          case _: AuditLogChange.Asset                    => Nil
          case change: AuditLogChange.AutoArchiveDuration => changeField(change, "auto archive duration")
          case change: AuditLogChange.Available           => changeField(change, "sticker availability")
          case change: AuditLogChange.AvatarHash =>
            entry.userId.toSeq.flatMap(
              userId =>
                changeFields(
                  change,
                  "avatar",
                  (hash: String) => printRequest(GetUserAvatarImage(1, ImageFormat.PNG, userId, hash))
                )
            )
          case change: AuditLogChange.BannerHash =>
            changeFields(change, "banner", printGuildRequest(GetGuildBannerImage))
          case change: AuditLogChange.Bitrate                    => changeField(change, "bitrate")
          case change: AuditLogChange.ChannelIdChanged           => changeField(change, "channel", printChannel)
          case change: AuditLogChange.Code                       => changeField(change, "invite code")
          case change: AuditLogChange.Color                      => changeField(change, "color", (color: Int) => s"#${color.toHexString}")
          case _: AuditLogChange.Deaf                            => Nil
          case change: AuditLogChange.DefaultAutoArchiveDuration => changeField(change, "default auto archive duration")
          case change: AuditLogChange.DefaultMessageNotification => changeField(change, "default message notification")
          case change: AuditLogChange.Deny =>
            Seq(
              EmbedField(
                s"Denied permissions for ${allowDenyPermissionOverwriteFor(change, _.deny)}",
                printPermissions(change.oldValue.getOrElse(Permission.None), change.newValue.getOrElse(Permission.None))
              )
            )
          case change: AuditLogChange.Description => changeFields(change, "description")
          case change: AuditLogChange.DiscoverySplashHash =>
            changeFields(change, "discovery splash", printGuildRequest(GetDiscoverySplashImage))
          case _: AuditLogChange.EnableEmoticons            => Nil
          case _: AuditLogChange.EntityType                 => Nil
          case _: AuditLogChange.ExpireBehavior             => Nil
          case _: AuditLogChange.ExpireGracePeriod          => Nil
          case change: AuditLogChange.ExplicitContentFilter => changeField(change, "explicit content filter")
          case change: AuditLogChange.FormatType            => changeField(change, "sticker format type")
          case _: AuditLogChange.GuildIdChange              => Nil
          case change: AuditLogChange.Hoist                 => changeField(change, "hoist")
          case change: AuditLogChange.IconHash =>
            targetId.toList.flatMap { targetId =>
              val role = guild.roles.get(RoleId(targetId))
              role.fold(changeFields(change, "guild icon", printGuildRequest(GetGuildIconImage))) { role =>
                changeFields(
                  change,
                  "role icon",
                  (hash: String) => printRequest(GetRoleIconImage(1, ImageFormat.PNG, role.id, hash))
                )
              }
            }
          case _: AuditLogChange.Id             => Nil
          case change: AuditLogChange.InviterId => changeField(change, "inviter", printUser)
          case change: AuditLogChange.Location  => changeField(change, "location")
          case change: AuditLogChange.Locked    => changeField(change, "locked")
          case change: AuditLogChange.MaxAge =>
            changeField(
              change,
              "max age",
              (int: Int) =>
                if (int < 7200) f"${int.toDouble / 60}%.2f minutes"
                else if (int < 172_800) f"${int.toDouble / 3600}%.2f hours"
                else f"${int.toDouble / 216_000}%.2f days"
            )
          case change: AuditLogChange.MaxUses     => changeField(change, "max uses")
          case change: AuditLogChange.Mentionable => changeField(change, "mentionable")
          case change: AuditLogChange.MfaLevel    => changeField(change, "MFA level")
          case _: AuditLogChange.Mute             => Nil
          case change: AuditLogChange.Name        => changeField(change, "name")
          case change: AuditLogChange.Nick        => changeField(change, "nickname")
          case change: AuditLogChange.NSFW        => changeField(change, "NSFW")
          case change: AuditLogChange.OwnerId     => changeFields(change, "owner", printUser)
          case change: AuditLogChange.PermissionOverwrites =>
            val oldValue = change.oldValue.getOrElse(Seq.empty).map(a => (a, "old"))
            val newValue = change.newValue.getOrElse(Seq.empty).map(a => (a, "new"))
            val values   = oldValue ++ newValue
            val overwriteMap = values.groupBy(t => t._1.`type` -> t._1.id).map {
              case (k @ (tpe, id), overwrites) =>
                val oldOverwrite = overwrites.find(_._2 == "old")
                val newOverwrite = overwrites.find(_._2 == "new")

                val dummy = PermissionOverwrite(id, tpe, Permission.None, Permission.None)

                k -> (oldOverwrite.fold(dummy)(_._1), newOverwrite.fold(dummy)(_._1))
            }

            overwriteMap.map {
              case ((tpe, id), (oldPerms, newPerms)) =>
                val targetStr = tpe match {
                  case PermissionOverwriteType.Role => LogStream.printRoleId(guild, RoleId(id), mentionsWork = false)
                  case _                            => LogStream.printUserId(UserId(id), mentionsWork = false)
                }

                EmbedField(
                  s"Changed permission overwrites: $targetStr",
                  printPermissionOverwrite(oldPerms, newPerms)
                )
            }
          case change: AuditLogChange.Permissions =>
            ???

            Seq(
              EmbedField(
                "Permissions",
                printPermissions(change.oldValue.getOrElse(Permission.None), change.newValue.getOrElse(Permission.None))
              )
            )
          case _: AuditLogChange.Position             => Nil
          case change: AuditLogChange.PreferredLocale => changeField(change, "preferred locale")
          case change: AuditLogChange.PrivacyLevel    => changeField(change, "privacy level")
          case change: AuditLogChange.PruneDeleteDays => changeField(change, "prune deletion", (i: Int) => s"$i days")
          case change: AuditLogChange.PublicUpdatesChannelId =>
            changeFields(change, "public updates channel", printChannel)
          case change: AuditLogChange.RateLimitPerUser => changeField(change, "ratelimit per user")
          case change: AuditLogChange.Region           => changeField(change, "region")
          case change: AuditLogChange.RulesChannelId   => changeFields(change, "rules channel", printChannel)
          case change: AuditLogChange.SplashHash =>
            changeFields(change, "splash", printGuildRequest(GetGuildSplashImage))
          case change: AuditLogChange.Status            => changeField(change, "status")
          case change: AuditLogChange.SystemChannelId   => changeField(change, "system channel", printChannel)
          case change: AuditLogChange.Tags              => changeField(change, "related sticker emoji")
          case change: AuditLogChange.Temporary         => changeField(change, "temporary")
          case change: AuditLogChange.Topic             => changeFields(change, "topic")
          case _: AuditLogChange.TypeInt                => Nil
          case _: AuditLogChange.TypeString             => Nil
          case change: AuditLogChange.UnicodeEmoji      => changeField(change, "role icon")
          case change: AuditLogChange.UserLimit         => changeField(change, "user limit")
          case change: AuditLogChange.Uses              => changeField(change, "uses")
          case change: AuditLogChange.VanityUrlCode     => changeField(change, "vanity url code")
          case change: AuditLogChange.VerificationLevel => changeField(change, "verification level")
          case _: AuditLogChange.WidgetChannelId        => Nil
          case _: AuditLogChange.WidgetEnabled          => Nil
          case add: AuditLogChange.$Add                 =>
            val newRoles = add.newValue.toSeq.flatten.map(role => s"${role.id.mention} (@${role.name} ${role.id})").mkString("\n")
            if(newRoles.isEmpty) Nil
            else List(EmbedField("Added roles", newRoles))
          case remove: AuditLogChange.$Remove           =>
            val oldRoles = remove.oldValue.toSeq.flatten.map(role => s"${role.id.mention} (@${role.name} ${role.id})").mkString("\n")
            if(oldRoles.isEmpty) Nil
            else List(EmbedField("Removed roles", oldRoles))
        }

        val changesFieldsIfWanted = if (printChanges) changesFields else Nil

        standardFields ++ optionalInfoFields ++ changesFieldsIfWanted
      }
  }

  def guildLogElement[A](
      apiMessage: APIMessage,
      guild: GatewayGuild,
      auditLogEvent: Seq[AuditLogEvent],
      title: AuditLog => String,
      color: Int,
      targetId: Option[SnowflakeType[A]],
      fields: AuditLog => Seq[EmbedField] = _ => Nil,
      filterAuditLogEntries: AuditLogEntry => Boolean = _ => true,
      printChanges: Boolean = true,
      makeOptionalInfoFields: AuditLog => OptionalAuditLogInfo => Seq[EmbedField] = _ => _ => Nil,
      removeIfNoFields: Boolean = true
  )(implicit c: CacheSnapshot): List[GuildLogElement] =
    List(
      GuildLogElement(
        apiMessage,
        guild.id,
        auditLogEvent,
        Instant.now(),
        implicit log => {
          val entry = getAuditLogEntry(log, auditLogEvent, targetId, filterAuditLogEntries)

          val causeUser = entry.flatMap(_.userId).flatMap(id => id.resolve.orElse(log.users.find(_.id == id)))
          val embedImage = entry
            .flatMap(_.changes.toList.flatten.collectFirst {
              case AuditLogChange.AvatarHash(_, Some(hash)) =>
                causeUser.map(
                  user => OutgoingEmbedImage(printRequest(GetUserAvatarImage(256, ImageFormat.WebP, user.id, hash)))
                )
            })
            .flatten

          OutgoingEmbed(
            title = Some(title(log)),
            author = causeUser.map(
              user =>
                OutgoingEmbedAuthor(
                  name = printUser(user, mentionsWork = false),
                  iconUrl = user.avatar.map(
                    avatarHash => printRequest(GetUserAvatarImage(64, ImageFormat.WebP, user.id, avatarHash))
                  )
                )
            ),
            image = embedImage,
            fields = fields(log) ++ auditLogFields(
              apiMessage,
              guild,
              log,
              auditLogEvent,
              targetId,
              filterAuditLogEntries,
              printChanges,
              makeOptionalInfoFields(log)
            ),
            color = Some(color),
            footer = targetId.map(id => OutgoingEmbedFooter(text = s"ID: ${id.asString}")),
            timestamp = Some(OffsetDateTime.now())
          )
        },
        removeIfNoFields
      )
    )

  def logStream: Flow[APIMessage, LogElement, NotUsed] = Flow[APIMessage].mapConcat {
    case apiMessage @ APIMessage.ChannelCreate(Some(guild), channel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.ChannelCreate),
        title = implicit log => s"Channel ${printChannel(channel, mentionsWork = false)} created",
        color = Color.Created,
        targetId = Some(channel.id),
        fields = implicit log =>
          Seq(
            EmbedField("Name", channel.name),
            EmbedField("Parent", channel.parentId.fold("None")(printChannelId(guild, _))),
            EmbedField("Type", channel.channelType.toString)
          )
      )

    case apiMessage @ APIMessage.ChannelUpdate(Some(guild), channel: GuildChannel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(
          AuditLogEvent.ChannelUpdate,
          AuditLogEvent.ChannelOverwriteCreate,
          AuditLogEvent.ChannelOverwriteUpdate,
          AuditLogEvent.ChannelOverwriteDelete
        ),
        title = implicit log => s"Channel ${printChannel(channel, mentionsWork = false)} updated",
        color = Color.Updated,
        targetId = Some(channel.id)
      )

    case apiMessage @ APIMessage.ChannelDelete(Some(guild), channel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.ChannelDelete),
        title = implicit log => s"Channel ${printChannel(channel, mentionsWork = false)} deleted",
        color = Color.Deleted,
        targetId = Some(channel.id)
      )

    case apiMessage @ APIMessage.ThreadCreate(guild, channel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.ThreadCreate),
        title = implicit log =>
          s"Thread ${printChannel(channel, mentionsWork = false)} created in ${printChannelId(guild, channel.parentChannelId, mentionsWork = false)}",
        color = Color.Created,
        targetId = Some(channel.id),
        fields = implicit log =>
          Seq(
            EmbedField("Name", channel.name),
            EmbedField("Creator", printUserId(channel.ownerId)),
            EmbedField("Type", channel.channelType.toString)
          )
      )

    case apiMessage @ APIMessage.ThreadUpdate(guild, channel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.ThreadUpdate),
        title = implicit log =>
          s"Thread ${printChannel(channel, mentionsWork = false)} updated in ${printChannelId(guild, channel.parentChannelId, mentionsWork = false)}",
        color = Color.Updated,
        targetId = Some(channel.id)
      )

    case apiMessage @ APIMessage.ThreadDelete(guild, threadId, parentId, _, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.ThreadDelete),
        title = implicit log =>
          s"Thread ${printChannelId(guild, threadId, mentionsWork = false)} deleted in ${printChannelId(guild, parentId, mentionsWork = false)}",
        color = Color.Deleted,
        targetId = Some(threadId)
      )

    case apiMessage @ APIMessage.ThreadMembersUpdate(guild, channel, addedMembers, removedMembers, cache, _) =>
      Nil //TODO

    case apiMessage @ APIMessage.ChannelPinsUpdate(Some(guild), channel, _, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild,
        Seq(AuditLogEvent.MessagePin, AuditLogEvent.MessageUnpin),
        implicit log => s"Pins updated in ${printChannelId(guild, GuildChannelId(channel), mentionsWork = false)}",
        Color.Updated,
        None
      )

    case apiMessage @ APIMessage.GuildUpdate(guild, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.GuildUpdate),
        title = implicit log => "Guild updated",
        color = Color.Updated,
        targetId = Some(guild.id)
      )

    case apiMessage @ APIMessage.GuildBanAdd(guild, user, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.MemberBanAdd),
        title = implicit log => s"Banned ${printUser(user, mentionsWork = false)}",
        color = Color.Deleted,
        targetId = Some(user.id)
      )

    case apiMessage @ APIMessage.GuildBanRemove(guild, user, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.MemberBanRemove),
        title = implicit log => s"Unbanned ${printUser(user, mentionsWork = false)}",
        color = Color.Created,
        targetId = Some(user.id)
      )

    case apiMessage @ APIMessage.GuildEmojiUpdate(guild, emojis, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      //TODO: Print the emojis
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.EmojiCreate, AuditLogEvent.EmojiUpdate, AuditLogEvent.EmojiDelete),
        title = implicit log => s"Emoji update",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildStickerUpdate(guild, stickers, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      //TODO: Print the stickers
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.StickerCreate, AuditLogEvent.StickerUpdate, AuditLogEvent.StickerDelete),
        title = implicit log => s"Sticker update",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildIntegrationsUpdate(guild, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(),
        title = implicit log => s"Integrations update",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildMemberAdd(member, guild, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(),
        title = implicit log => s"Member joined ${printUser(member.userId.resolve.get, mentionsWork = false)}",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildMemberRemove(user, guild, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(),
        title = implicit log => s"Member left ${printUser(user, mentionsWork = false)}",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildMemberUpdate(
          guild,
          roles,
          user,
          nick,
          joinedAt,
          premiumSince,
          deaf,
          mute,
          pending,
          cache,
          _
        ) =>
      Nil //TODO

    case apiMessage @ APIMessage.GuildRoleCreate(guild, role, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.RoleCreate),
        title = implicit log => s"Role ${printRole(role, mentionsWork = false)} created",
        color = Color.Created,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildRoleUpdate(guild, role, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.RoleUpdate),
        title = implicit log => s"Role ${printRole(role, mentionsWork = false)} updated",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildRoleDelete(guild, role, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.RoleDelete),
        title = implicit log => s"Role ${printRole(role, mentionsWork = false)} deleted",
        color = Color.Deleted,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildScheduledEventCreate(guild, scheduledEvent, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.GuildScheduledEventCreate),
        title = implicit log => s"Scheduled event ${scheduledEvent.name} created",
        color = Color.Created,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildScheduledEventUpdate(guild, scheduledEvent, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.GuildScheduledEventUpdate),
        title = implicit log => s"Scheduled event ${scheduledEvent.name} updated",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.GuildScheduledEventDelete(guild, scheduledEvent, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.GuildScheduledEventDelete),
        title = implicit log => s"Scheduled event ${scheduledEvent.name} deleted",
        color = Color.Deleted,
        targetId = None
      )

    case apiMessage @ APIMessage.InviteCreate(Some(guild), _, invite, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.InviteCreate),
        title = implicit log => "Invite created",
        color = Color.Created,
        targetId = None,
        filterAuditLogEntries = entry =>
          entry.changes.exists(_.exists {
            case AuditLogChange.Code(_, newValue) => newValue.contains(invite.code)
            case _                                => false
          })
      )

    case apiMessage @ APIMessage.InviteDelete(Some(guild), _, code, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.InviteDelete),
        title = implicit log => "Invite deleted",
        color = Color.Deleted,
        targetId = None,
        fields = implicit log => Seq(EmbedField("Code", code)),
        filterAuditLogEntries = entry =>
          entry.changes.exists(_.exists {
            case AuditLogChange.Code(oldValue, newValue) => oldValue.contains(code) || newValue.contains(code)
            case _                                       => false
          })
      )

    case apiMessage @ APIMessage.MessageUpdate(Some(guild), messageId, channelId, cache, _) =>
      implicit val c: CacheSnapshot = cache.current

      val oldMessage = messageId.resolve(channelId)(cache.previous)
      val newMessage = messageId.resolve(channelId)(cache.current)

      val oldOptContent = oldMessage.map(_.content).filter(_.nonEmpty)
      val newOptContent = newMessage.map(_.content).filter(_.nonEmpty)

      if (oldMessage.map(_.content) == newMessage.map(_.content)) Nil
      else
        guildLogElement(
          apiMessage = apiMessage,
          guild = guild,
          auditLogEvent = Seq(),
          title = implicit log =>
            s"Updated message in ${printChannelId(guild, GuildChannelId(channelId), mentionsWork = false)}",
          color = Color.Deleted,
          targetId = Some(messageId),
          fields = implicit log =>
            (oldOptContent, newOptContent) match {
              case (Some(oldContent), Some(newContent)) =>
                Seq(
                  EmbedField("Message diff", makeDiff(oldContent, newContent)),
                  jumpToMessageField(guild, channelId, messageId)
                )
              case _ =>
                Seq(
                  EmbedField("Old content", oldOptContent.getOrElse("<unknown>")),
                  EmbedField("New content", newOptContent.getOrElse("<unknown>")),
                  jumpToMessageField(guild, channelId, messageId)
                )
            }
        )

    case apiMessage @ APIMessage.MessageDelete(messageId, Some(guild), channelId, cache, _) =>
      implicit val c: CacheSnapshot = cache.current

      val message = messageId.resolve(channelId)(cache.previous)

      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.MessageDelete),
        title = implicit log =>
          s"Deleted message in ${printChannelId(guild, GuildChannelId(channelId), mentionsWork = false)}",
        color = Color.Deleted,
        targetId = Some(messageId),
        fields =
          implicit log => Seq(EmbedField("Content", message.map(_.content).filter(_.nonEmpty).getOrElse("<unknown>")))
      )

    case apiMessage @ APIMessage.MessageDeleteBulk(messageIds, Some(guild), channelId, cache, _) =>
      implicit val c: CacheSnapshot = cache.current

      //TODO: Message contents
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.MessageBulkDelete),
        title = implicit log =>
          s"Bulk deleted messages in ${printChannelId(guild, GuildChannelId(channelId), mentionsWork = false)}",
        color = Color.Deleted,
        targetId = None,
        fields = implicit log =>
          messageIds.map { id =>
            val message = id.resolve(cache.previous)
            val from    = message.fold("")(m => s" (${m.authorUsername})")
            EmbedField(s"${id.asString}$from", message.map(_.content).filter(_.nonEmpty).getOrElse("<unknown>"))
          }
      )

    case apiMessage @ APIMessage.MessageReactionRemoveAll(Some(guild), channelId, messageId, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Nil,
        title = implicit log =>
          s"Removed all reactions from message in ${printChannelId(guild, GuildChannelId(channelId), mentionsWork = false)}",
        color = Color.Deleted,
        targetId = None,
        fields = implicit log => Seq(jumpToMessageField(guild, channelId, messageId))
      )

    case apiMessage @ APIMessage.MessageReactionRemoveEmoji(Some(guild), channelId, messageId, emoji, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Nil,
        title = implicit log =>
          s"Removed emoji ${printEmoji(emoji)} from message in ${printChannelId(guild, GuildChannelId(channelId), mentionsWork = false)}",
        color = Color.Deleted,
        targetId = None,
        fields = implicit log => Seq(jumpToMessageField(guild, channelId, messageId))
      )

    case apiMessage @ APIMessage.PresenceUpdate(guild, user, presence, cache, _) => Nil //TODO

    case apiMessage @ APIMessage.UserUpdate(user, cache, _) => Nil //TODO

    case apiMessage @ APIMessage.WebhookUpdate(guild, channel, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.WebhookUpdate),
        title = implicit log => s"Webhook update in ${printChannel(channel, mentionsWork = false)}",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.IntegrationCreate(guild, integration, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationCreate),
        title = implicit log => s"Integration ${integration.name} added",
        color = Color.Created,
        targetId = Some(integration.id)
      )

    case apiMessage @ APIMessage.IntegrationUpdate(guild, integration, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationUpdate),
        title = implicit log => s"Integration ${integration.name} updated",
        color = Color.Updated,
        targetId = Some(integration.id)
      )

    case apiMessage @ APIMessage.IntegrationDelete(guild, id, _, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationDelete),
        title = implicit log => s"Integration removed",
        color = Color.Deleted,
        targetId = Some(id)
      )

    case apiMessage @ APIMessage.StageInstanceCreate(guild, stageInstance, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationCreate),
        title = implicit log =>
          s"Stage instance added for ${printChannelId(guild, stageInstance.channelId, mentionsWork = false)}",
        color = Color.Created,
        targetId = None
      )

    case apiMessage @ APIMessage.StageInstanceUpdate(guild, stageInstance, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationCreate),
        title = implicit log =>
          s"Stage instance updated for ${printChannelId(guild, stageInstance.channelId, mentionsWork = false)}",
        color = Color.Updated,
        targetId = None
      )

    case apiMessage @ APIMessage.StageInstanceDelete(guild, stageInstance, cache, _) =>
      implicit val c: CacheSnapshot = cache.current
      guildLogElement(
        apiMessage = apiMessage,
        guild = guild,
        auditLogEvent = Seq(AuditLogEvent.IntegrationCreate),
        title = implicit log =>
          s"Stage instance deleted for ${printChannelId(guild, stageInstance.channelId, mentionsWork = false)}",
        color = Color.Deleted,
        targetId = None
      )

    case _ => Nil
  }

}
