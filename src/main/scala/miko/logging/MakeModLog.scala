package miko.logging

import ackcord.commands.MessageParser
import ackcord.data.{EmbedField, SnowflakeType, UserId}
import ackcord.requests.{CreateMessage, GetGuildAuditLog, GetGuildAuditLogData}
import ackcord.{APIMessage, Requests}
import akka.NotUsed
import akka.stream.scaladsl.Sink
import cats.effect.unsafe.IORuntime
import miko.settings.SettingsAccess

object MakeModLog {

  implicit val requestProperties: Requests.RequestProperties = Requests.RequestProperties.retry

  def makeModLog(requests: Requests, settingsAccess: SettingsAccess)(
      implicit IORuntime: IORuntime
  ): Sink[APIMessage, NotUsed] =
    LogStream.logStream
      .mapAsync(4) {
        case logElement: LogStream.GuildLogElement =>
          settingsAccess.getGuildSettings(logElement.forGuild).map(_.modLog).map(settings => List((settings, logElement, logElement.forGuild))).unsafeToFuture()
        case logElement: LogStream.UserLogElement =>
          settingsAccess.getAllSettings.map(_.map(t => (t._2.modLog, logElement, t._1)).toSeq).unsafeToFuture()
      }
      .mapConcat(identity)
      .filter(t => t._1.channelId.nonEmpty)
      .filter(t => t._2.apiMessage.cache.current.getGuild(t._3).isDefined)
      .filter {
        case (modLogSettings, logElement, _) =>
          logElement.apiMessage match {
            case apiMessage: APIMessage.ChannelMessage =>
              !modLogSettings.ignoredChannels.contains(apiMessage.channel.id)
            case apiMessage: APIMessage.TextChannelIdMessage =>
              !modLogSettings.ignoredChannels.contains(apiMessage.channelId)
            case apiMessage: APIMessage.MessageMessage =>
              !modLogSettings.ignoredChannels.contains(apiMessage.message.channelId)
            case _ => true
          }
      }
      .map {
        case t @ (_, logElement, guildId) =>
          val events = logElement.auditLogEvent
          GetGuildAuditLog(
            guildId,
            GetGuildAuditLogData(
              actionType = Option.when(events.size == 1)(events.head),
              before = Some(SnowflakeType.fromInstant[Any](logElement.whenHappened)),
              limit = Some(if (events.size == 1) 3 else 15)
            )
          ) -> t
      }
      .via(requests.flowSuccess(ignoreFailures = false))
      .mapConcat {
        case (auditLog, (modLogSettings, logElement, guildId)) =>
          val filteredAuditLog = auditLog.copy(
            auditLogEntries = auditLog.auditLogEntries.filter(
              entry =>
                !modLogSettings.ignoredAuditLogEvents.contains(entry.actionType) &&
                  entry.id.creationDate.compareTo(logElement.whenHappened.minusSeconds(3)) > 0
            )
          )
          val embed = logElement match {
            case logElement: LogStream.GuildLogElement => logElement.makeEmbed(filteredAuditLog)
            case logElement: LogStream.UserLogElement =>
              val guild = guildId.resolve(logElement.apiMessage.cache.current).get
              logElement.makeEmbed(guild)(filteredAuditLog)
          }

          val userIdParser = MessageParser.userRegex.unanchored

          val eventCauser = embed.fields.collectFirst {
            case EmbedField("Event causer", userIdParser(strId), _) => UserId(strId)
          }

          if (eventCauser.exists(modLogSettings.ignoredUsers.contains)) Nil
          else if (logElement.removeIfEmpty && embed.fields.isEmpty && embed.description.isEmpty) Nil
          else List(CreateMessage.mkEmbed(modLogSettings.channelId.get, embed) -> ())
      }
      .via(requests.flowSuccess(ignoreFailures = false))
      .to(Sink.ignore)

}
