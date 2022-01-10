package miko.logging

import ackcord.data.SnowflakeType
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
          settingsAccess.getGuildSettings(logElement.forGuild).map(_.modLog).map(_ -> logElement).unsafeToFuture()
        case logElement: LogStream.UserUpdateLogElement => ???
      }
      .filter(t => t._1.channelId.nonEmpty)
      .map {
        case t @ (_, logElement) =>
          val events = logElement.auditLogEvent
          GetGuildAuditLog(
            logElement.forGuild,
            GetGuildAuditLogData(
              actionType = Option.when(events.size == 1)(events.head),
              before = Some(SnowflakeType.fromInstant[Any](logElement.whenHappened)),
              limit = Some(if (events.size == 1) 3 else 15)
            )
          ) -> t
      }
      .via(requests.flowSuccess(ignoreFailures = false))
      .mapConcat {
        case (auditLog, (modLogSettings, logElement)) =>
          val filteredAuditLog = auditLog.copy(
            auditLogEntries = auditLog.auditLogEntries.filter(
              entry =>
                !modLogSettings.ignoredAuditLogEvents.contains(entry.actionType) &&
                  entry.id.creationDate.compareTo(logElement.whenHappened.minusSeconds(3)) > 0
            )
          )
          val embed = logElement.makeEmbed(filteredAuditLog)

          if(logElement.removeIfEmpty && embed.fields.isEmpty && embed.description.isEmpty) Nil
          else List(CreateMessage.mkEmbed(modLogSettings.channelId.get, embed) -> ())
      }
      .via(requests.flowSuccess(ignoreFailures = false))
      .to(Sink.ignore)

}
