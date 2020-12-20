package miko.voicetext

import java.text.Normalizer
import java.text.Normalizer.Form
import java.util.Locale

import ackcord._
import ackcord.data._
import ackcord.requests.{Requests => _, _}
import ackcord.syntax._
import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink, Source}
import miko.db.DBAccess
import miko.settings.GuildSettings.VoiceText.{VTPermissionGroup, VTPermissionSet, VTPermissionValue}
import miko.settings.{GuildSettings, SettingsAccess}
import miko.util.MiscHelper
import org.slf4j.{Logger, LoggerFactory}
import zio.blocking.Blocking
import zio.{RIO, Task}

import scala.concurrent.duration._

class VoiceTextStreams(
    implicit requests: Requests,
    taskStreamable: Streamable[Task],
    blockingStreamable: Streamable[RIO[Blocking, *]],
    settings: SettingsAccess,
    db: DBAccess[Task]
) {
  import VoiceTextStreams._

  val log: Logger = LoggerFactory.getLogger("VoiceTextStreams")

  private val numberAtEnd = """ ([1-9]+)$""".r.unanchored

  private def isSaveDesctructableChannel(
      guildId: GuildId,
      channelId: GuildChannelId
  )(implicit c: CacheSnapshot) =
    if (channelId.resolve(guildId).exists(_.name.endsWith("-voice")))
      guildSettings(guildId).filter(_.voiceText.destructive.saveDestroyed)
    else Source.empty

  private def saveDestructableArgs(
      msg: GuildGatewayMessage
  )(implicit c: CacheSnapshot): Option[(User, TextGuildChannel, Option[GuildCategory])] =
    for {
      channel <- msg.channelId.resolve(msg.guildId)
      if channel.name.endsWith("-voice")
      category = channel.parentId.flatMap(_.resolve(channel.guildId)).flatMap(_.asCategory)
      user <- msg.authorUserId.flatMap(_.resolve)
    } yield (user, channel, category)

  private def saveDesctructableCommon(
      msg: GuildGatewayMessage,
      c: CacheSnapshot
  ): Source[(User, TextGuildChannel, Option[GuildCategory], String), NotUsed] =
    saveDestructableArgs(msg)(c)
      .map {
        case (user, channel, category) =>
          guildSettings(channel.guildId)
            .filter(_.voiceText.destructive.saveDestroyed)
            .mapConcat(_.guildEncryption.publicKey.toList)
            .map(key => (user, channel, category, key))
      }
      .getOrElse(Source.empty)

  private def guildSettings(guildId: GuildId): Source[GuildSettings, NotUsed] =
    blockingStreamable.toSource(settings.getGuildSettings(guildId))

  def saveDestructable: Flow[APIMessage, Int, NotUsed] =
    Flow[APIMessage]
      .collect {
        case APIMessage.MessageCreate(_, msg: GuildGatewayMessage, CacheState(c, _)) =>
          saveDesctructableCommon(msg, c).flatMapConcat {
            case (user, channel, category, key) =>
              taskStreamable.toSource(db.insertVTMsg(msg, channel, category, user, key))
          }

        case APIMessage.MessageUpdate(_, msg: GuildGatewayMessage, CacheState(c, _)) =>
          saveDesctructableCommon(msg, c).flatMapConcat {
            case (user, channel, category, key) =>
              taskStreamable.toSource(db.updateVTMsg(msg, channel, category, user, key))
          }

        case APIMessage.MessageDelete(messageId, Some(guild), channelId, CacheState(c, _)) =>
          isSaveDesctructableChannel(guild.id, channelId.asChannelId[GuildChannel])(c).flatMapConcat { _ =>
            taskStreamable.toSource(db.deleteVTMsg(messageId))
          }
      }
      .flatMapMerge(100, identity)

  def channelEnterLeave: Sink[(GuildId, Option[VoiceGuildChannelId], UserId, CacheSnapshot, CacheSnapshot), NotUsed] =
    Flow[(GuildId, Option[VoiceGuildChannelId], UserId, CacheSnapshot, CacheSnapshot)]
      .flatMapConcat {
        case (guildId, channelIdOpt, userId, current, previous) =>
          guildSettings(guildId).filter(_.voiceText.enabled).flatMapConcat { implicit settings =>
            implicit val cache: CacheSnapshot = current

            val res = for {
              guild     <- guildId.resolve
              prevGuild <- guildId.resolve(previous)
              member    <- guild.memberById(userId)
              prevChannelIdOpt = prevGuild.voiceStateFor(userId).flatMap(_.channelId)
              if channelIdOpt != prevChannelIdOpt
            } yield {
              val channelOpt     = channelIdOpt.flatMap(_.resolve(guildId))
              val prevChannelOpt = prevChannelIdOpt.flatMap(_.resolve(guildId))

              val removeAndExitF = prevChannelOpt
                .map { prevChannel =>
                  val (remaining, removeReqs) = removeIfEmpty(prevChannel, guild)
                  val exitReqs                = remaining.map(userExitTChannel(member, _, prevChannel, guild))

                  Source(exitReqs) ++ removeReqs
                }
                .getOrElse(Source.empty)

              val enterRoomF = channelOpt.map(userEnterVChannel(member, _, guild)).getOrElse(Source.empty)

              removeAndExitF ++ enterRoomF
            }

            res.getOrElse(Source.empty)
          }
      }
      .to(requests.sinkIgnore[Any])

  def channelUpdate: Sink[(VoiceGuildChannel, CacheSnapshot, CacheSnapshot), NotUsed] =
    Flow[(VoiceGuildChannel, CacheSnapshot, CacheSnapshot)]
      .flatMapConcat {
        case (newChannel, current, previous) =>
          val guildId = newChannel.guildId
          guildSettings(guildId).filter(_.voiceText.enabled).mapConcat { implicit settings =>
            val requestsOpt = for {
              guild      <- guildId.resolve(current)
              prevGuild  <- guildId.resolve(previous)
              oldChannel <- prevGuild.voiceChannelById(newChannel.id)
              if newChannel.name != oldChannel.name
            } yield getTextChannel(oldChannel, guild).map { channel =>
              channel.modify(
                name = JsonSome(getTextVoiceChannelName(newChannel))
              )
            }

            requestsOpt.toList.flatten
          }
      }
      .to(requests.sinkIgnore)

  def cleanup: Sink[CacheSnapshot, NotUsed] =
    Flow[CacheSnapshot]
      .groupedWithin(10000, 1.minute)
      .mapConcat(_.lastOption.toList)
      .via(cleanupImpl)
      .to(Sink.ignore)

  def shiftChannels: Sink[CacheSnapshot, NotUsed] =
    Flow[CacheSnapshot]
      .groupedWithin(25, 5.minute)
      .mapConcat(_.lastOption.toList)
      .via(shiftChannelsImpl)
      .to(Sink.ignore)

  def cleanupImpl: Flow[CacheSnapshot, RequestAnswer[_], NotUsed] =
    Flow[CacheSnapshot]
      .mapConcat(c => c.guildMap.values.map(_ -> c).toVector)
      .via(cleanupGuild)
      .via(RequestStreams.removeContext(requests.flow[Any, NotUsed]))

  def cleanupGuild: Flow[(Guild, CacheSnapshot), Request[_], NotUsed] =
    Flow[(Guild, CacheSnapshot)]
      .flatMapMerge(requests.settings.parallelism, {
        case (guild, c) => guildSettings(guild.id).map(settings => (guild, c, settings))
      })
      .filter(_._3.voiceText.enabled)
      .mapConcat {
        case (guild, cache, settingsObj) =>
          implicit val c: CacheSnapshot        = cache
          implicit val settings: GuildSettings = settingsObj

          for {
            vChannel <- guild.voiceChannels
            if canHaveTextChannel(vChannel, guild)
          } yield {
            val (remaining, removeReqs) = removeIfEmpty(vChannel, guild)

            removeReqs ++ Source(remaining).flatMapMerge(
              requests.settings.parallelism,
              fixUsersInChannel(vChannel, _, guild)
            )
          }
      }
      .flatMapMerge(requests.settings.parallelism, identity)

  def shiftChannelsImpl: Flow[CacheSnapshot, RequestAnswer[Any], NotUsed] =
    Flow[CacheSnapshot]
      .mapConcat { implicit c =>
        log.info("Starting channel shift")
        c.guildMap.values.map(_ -> c).toVector
      }
      .via(shiftChannelsGuildFlow)
      .via(RequestStreams.removeContext(requests.flow[Any, NotUsed]))

  def shiftChannelsGuildFlow: Flow[(Guild, CacheSnapshot), Request[_], NotUsed] =
    Flow[(Guild, CacheSnapshot)].flatMapMerge(
      requests.settings.parallelism, {
        case (guild, _) =>
          guildSettings(guild.id)
            .filter(_.voiceText.dynamicallyResizeChannels > 0)
            .mapConcat(_ => shiftChannelsGuild(guild))
      }
    )

  def fixUsersInChannel(vChannel: VoiceGuildChannel, tChannel: TextGuildChannel, guild: Guild)(
      implicit c: CacheSnapshot,
      settings: GuildSettings
  ): Source[Request[_], NotUsed] = {
    val permGroup = permGroupForChannel(vChannel)

    val inVoiceChannel = vChannel.connectedMembers(guild)

    val (textConnectedIter, allowIter, disallowIter) =
      tChannel.permissionOverwrites
        .filter(_._2.`type` == PermissionOverwriteType.Member)
        .flatMap {
          case (userId, overwrite) =>
            guild.memberById(UserId(userId)).map { user =>
              (user, user -> overwrite.allow, user -> overwrite.deny)
            }
        }
        .unzip3

    val textConnected   = textConnectedIter.toSeq
    val currentAllow    = allowIter.toMap
    val currentDisallow = disallowIter.toMap

    val exitRoomRequestsSeq = textConnected
      .filterNot(inVoiceChannel.contains)
      .filter(MiscHelper.canHandlerMember(guild, _))
      .map { member =>
        log.info(
          "Removed invalid user {} from room {}",
          member.user.map(_.username).getOrElse(""),
          tChannel.name
        )
        userExitTChannel(member, tChannel, vChannel, guild)
      }

    val exitRoomRequests = Source(exitRoomRequestsSeq)

    val fixedUsersSeq = inVoiceChannel.map { inVoice =>
      inVoice.user.fold(Source.empty[Request[_]]) { inVoiceUser =>
        lazy val userChannelPerms = userPerms(inVoiceUser.id, permGroup, _.inside)

        if (!textConnected.contains(inVoice)) {
          log.info(
            "Found channel {} with without user {}. Fixed",
            vChannel.name,
            inVoiceUser.username
          )
          userEnterVChannel(inVoice, vChannel, guild)
        } else if (!currentAllow
                     .get(inVoice)
                     .contains(userChannelPerms.allowNative) || !currentDisallow
                     .get(inVoice)
                     .contains(userChannelPerms.denyNative)) {
          log.info("Found user {} with wrong permissions. Fixed", inVoiceUser.username)
          Source.single(applyPerms(inVoice, tChannel, guild, userChannelPerms))
        } else Source.empty
      }
    }

    val fixedUsers = Source(fixedUsersSeq).flatMapMerge(requests.settings.parallelism, identity)
    exitRoomRequests ++ fixedUsers
  }

  def shiftChannelsGuild(guild: Guild): Seq[Request[_]] = {
    val shiftingCategories = guild.categories.filter(_.name.endsWith(" #"))
    log.info("Shifting {}", shiftingCategories.map(_.name))

    val groupedByCategory: Map[String, Seq[VoiceGuildChannel]] = shiftingCategories.map { cat =>
      val channels =
        cat
          .voiceChannels(guild)
          .flatMap { ch =>
            ch.name match {
              case numberAtEnd(number) => Some(ch -> number.toInt)
              case _                   => None
            }
          }
          .sortBy(_._2)
          .map(_._1)

      cat.name -> channels
    }.toMap

    log.info("Grouped by category {}", groupedByCategory.map(t => t._1 -> t._2.map(_.name)))

    val categoryFiltered: Map[String, (Seq[VoiceGuildChannel], Seq[VoiceGuildChannel])] =
      groupedByCategory.flatMap {
        case (cat, vChannels) =>
          def hasCorrectName(channel: VoiceGuildChannel, num: String) =
            channel.name == cat.replace("#", num)

          val allNamesCorrect =
            vChannels.zipWithIndex.forall(t => hasCorrectName(t._1, (t._2 + 1).toString))

          // Not strictly needed I think. Just a early quick check
          vChannels.size match {
            case 1 if allNamesCorrect => None
            case 2 if vChannels.head.connectedUsers(guild).nonEmpty && allNamesCorrect =>
              None
            case _ =>
              val (empty, nonEmpty) = vChannels.partition(_.connectedUsers(guild).isEmpty)

              if (empty.isEmpty && allNamesCorrect) None
              else Some((cat, (nonEmpty.sortBy(_.position), empty)))
          }
      }

    log.info("Category to filtered {}", categoryFiltered.map {
      case (cat, (keep, remove)) =>
        (cat, (keep.map(_.name), remove.map(_.name)))
    })

    categoryFiltered.flatMap {
      case (cat, (nonEmpty, empty)) =>
        //If there are still non empty channels, we can use one of those as the
        //base (num 1) channel, else we need to save one for later
        val (toRemove, toRename) =
          if (nonEmpty.nonEmpty)
            (empty, nonEmpty)
          else
            (empty.tail, empty.head +: nonEmpty)

        val startPos = toRename.head.position

        val deleteRequests = toRemove.map { ch =>
          log.info("Deleting unused channel {}", ch.name)
          ch.delete
        }

        val renameRequests = toRename.zipWithIndex.map {
          case (ch, num) =>
            val name = cat.replace("#", (num + 1).toString)
            log.info(s"Renaming and shifting channel {}", ch.name)
            ch.modify(name = JsonSome(name))
        }

        val changePosRequest = guild.modifyChannelPositions(
          SnowflakeMap.from(toRename.zipWithIndex.map(t => t._1.id -> (startPos + t._2)))
        )

        (deleteRequests ++ renameRequests) :+ changePosRequest: Seq[Request[_]]
    }.toVector
  }

  def removeIfEmpty(channel: VoiceGuildChannel, guild: Guild)(
      implicit settings: GuildSettings
  ): (Seq[TextGuildChannel], Source[DeleteCloseChannel, NotUsed]) = {
    val tChannels              = getTextChannel(channel, guild)
    val (removable, remaining) = filterRemovableChannels(channel, guild, tChannels)

    val reqs = removable.map(_.delete)

    (remaining, Source(reqs))
  }

  private def filterRemovableChannels(
      vChannel: VoiceGuildChannel,
      guild: Guild,
      channels: Seq[TextGuildChannel]
  )(implicit settings: GuildSettings): (Seq[TextGuildChannel], Seq[TextGuildChannel]) =
    if (settings.voiceText.destructive.enabled && vChannel.connectedUsers(guild).isEmpty)
      channels.partition(textChannel => !settings.voiceText.destructive.blacklist.contains(textChannel.id))
    else
      (Nil, channels)

  def userExitTChannel(
      member: GuildMember,
      tChannel: TextGuildChannel,
      vChannel: VoiceGuildChannel,
      guild: Guild
  )(
      implicit c: CacheSnapshot,
      settings: GuildSettings
  ): Request[NotUsed] =
    applyPerms(member, tChannel, guild, userPerms(member.userId, permGroupForChannel(vChannel), _.outside))

  def applyPerms(
      member: GuildMember,
      tChannel: TextGuildChannel,
      guild: Guild,
      perm: VTPermissionValue
  )(implicit c: CacheSnapshot): Request[NotUsed] =
    if (MiscHelper.canHandlerMember(guild, member) && perm.isNone)
      tChannel.deleteChannelPermissionsUser(member.userId)
    else
      tChannel.editChannelPermissionsUser(member.userId, perm.allowNative, perm.denyNative)

  def userEnterVChannel(member: GuildMember, channel: VoiceGuildChannel, guild: Guild)(
      implicit
      c: CacheSnapshot,
      settings: GuildSettings
  ): Source[Request[_], NotUsed] = {
    val s1 = getOrCreateTextChannel(channel, guild, Some(member)).map {
      case Right(tChannel) =>
        applyPerms(member, tChannel, guild, userPerms(member.userId, permGroupForChannel(channel), _.inside))
      case Left(req) => req
    }

    if (channel
          .categoryFromGuild(guild)
          .exists(_.voiceChannels(guild).lengthIs < settings.voiceText.dynamicallyResizeChannels)) {
      s1 ++ Source(createNextRoom(channel, guild).toList)
    } else s1
  }

  def permGroupForChannel(channel: VoiceGuildChannel)(implicit settings: GuildSettings): VTPermissionGroup = {
    val permSettings = settings.voiceText.perms
    permSettings.overrideChannel
      .get(channel.id)
      .orElse(channel.parentId.flatMap(permSettings.overrideCategory.get))
      .getOrElse(permSettings.global)
  }

  def userPerms(
      userId: UserId,
      group: VTPermissionGroup,
      getValue: VTPermissionSet => VTPermissionValue
  ): VTPermissionValue =
    getValue(group.users.getOrElse(userId, group.everyone))

  def getOrCreateTextChannel(channel: VoiceGuildChannel, guild: Guild, creator: Option[GuildMember])(
      implicit settings: GuildSettings
  ): Source[Either[CreateGuildChannel, TextGuildChannel], NotUsed] =
    getTextChannel(channel, guild) match {
      case Seq() if !canHaveTextChannel(channel, guild) => Source.empty
      case Seq() =>
        val group = permGroupForChannel(channel)

        val outsidePerm = group.everyone.outside
        val insidePerm  = group.everyone.inside

        val outsideUserOverwrites = group.users.map(t => t._2.outside.toOverwrite(t._1, PermissionOverwriteType.Member))
        val rolesOverwrites       = group.roles.map(t => t._2.toOverwrite(t._1, PermissionOverwriteType.Role))

        val creatorOverwrite =
          creator.filter(member => !insidePerm.isNone || group.users.contains(member.userId)).map { member =>
            userPerms(member.userId, group, _.inside).toOverwrite(member.userId, PermissionOverwriteType.Member)
          }

        val everyoneOverwrite =
          if (outsidePerm.isNone) None
          else
            Some(
              PermissionOverwrite(
                guild.everyoneRole.id,
                PermissionOverwriteType.Role,
                outsidePerm.allowNative,
                outsidePerm.denyNative
              )
            )

        val overwrites = creatorOverwrite.toSeq ++ everyoneOverwrite.toSeq ++ outsideUserOverwrites ++ rolesOverwrites

        val req = guild.createTextChannel(
          name = getTextVoiceChannelName(channel),
          permissionOverwrites = if (overwrites.nonEmpty) JsonSome(overwrites) else JsonUndefined,
          category = JsonOption.fromOptionWithUndefined(channel.parentId),
          nsfw = JsonSome(channel.nsfw)
        )

        Source.single(Left(req))
      case seq => Source(seq.toIndexedSeq).map(Right.apply)
    }

  def createNextRoom(current: VoiceGuildChannel, guild: Guild): Option[CreateGuildChannel] =
    for {
      cat <- current.categoryFromGuild(guild)
      if cat.name.endsWith("#")
      matched <- numberAtEnd.findFirstMatchIn(current.name)
      number = matched.matched.drop(1).toInt
      name   = cat.name.replace("#", (number + 1).toString)
      res <- if (guild.voiceChannelsByName(name).exists(_.parentId.contains(cat.id))) None
      else
        Some(
          guild.createVoiceChannel(
            name = name,
            bitrate = JsonSome(current.bitrate),
            userLimit = JsonSome(current.userLimit),
            category = JsonSome(cat.id),
            nsfw = JsonSome(current.nsfw)
          )
        )

    } yield res
}
object VoiceTextStreams {

  def canHaveTextChannel(channel: VoiceGuildChannel, guild: Guild)(implicit settings: GuildSettings): Boolean =
    !guild.afkChannelId.contains(channel.id) &&
      !settings.voiceText.blacklist.channels.contains(channel.id) &&
      channel.parentId.forall(!settings.voiceText.blacklist.categories.contains(_))

  def makeTextChannelName(string: String): String = {
    val lowercase  = string.toLowerCase(Locale.ROOT).replace(' ', '-')
    val normalized = Normalizer.normalize(lowercase, Form.NFKD)
    normalized.replaceAll("[^A-Za-z0-9-]", "")
  }

  def getTextVoiceChannelName(channel: VoiceGuildChannel): String = s"${makeTextChannelName(channel.name)}-voice"

  def getTextChannel(channel: VoiceGuildChannel, guild: Guild)(
      implicit settings: GuildSettings
  ): Seq[TextGuildChannel] = {
    val allChannels =
      if (!canHaveTextChannel(channel, guild)) Seq.empty
      else guild.textChannelsByName(getTextVoiceChannelName(channel))

    channel.categoryFromGuild(guild).fold(allChannels)(cat => allChannels.filter(_.parentId.contains(cat.id)))
  }
}
