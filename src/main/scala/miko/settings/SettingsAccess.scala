package miko.settings

import ackcord.data.GuildId
import cats.effect.IO
import io.circe._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import play.api.Environment
import scalacache.Cache

import java.nio.file.{Files, Path}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class SettingsAccess(implicit cache: Cache[IO, GuildId, GuildSettings], environment: Environment) {

  private val logger = LoggerFactory.getLogger(classOf[SettingsAccess])

  private def guildSettingsFile(guildId: GuildId): Path =
    environment.rootPath.toPath.resolve("configs").resolve(s"${guildId.asString}.json")

  private def getGuildSettingsUncached(guildId: GuildId): IO[GuildSettings] = {
    val file          = guildSettingsFile(guildId)
    val notExists     = IO.blocking(Files.notExists(file))
    val createDefault = createNewConfig(guildId)

    val readConfig = IO.blocking(parseConfig(Files.readAllLines(file).asScala.mkString("\n"))).map {
      case Left(e) =>
        logger.error(s"Failed to parse config for ${guildId.asString}", e)
        GuildSettings()
      case Right(value) => value
    }

    notExists.ifM(ifTrue = createDefault, ifFalse = IO.unit) *> readConfig
  }

  private def parseConfig(s: String): Either[Error, GuildSettings] = {
    for {
      json    <- parser.parse(s)
      version <- json.hcursor.get[String]("version")
      config <- version match {
        case "1" => json.as[GuildSettings]
        case _   => Left(DecodingFailure("Invalid config version", json.hcursor.history))
      }
    } yield config
  }

  private def createNewConfig(guildId: GuildId): IO[Path] = {
    val file = guildSettingsFile(guildId)
    IO.blocking {
      Files.createDirectories(file.getParent)
      Files.write(
        file,
        Seq(GuildSettings().asJson.deepMerge(Json.obj("version" -> "1".asJson)).noSpaces).asJava
      )
    }
  }

  //TODO: Use settingsUpdate better and remove race conditions here
  private def updateGuildSettingsFile(
      guildId: GuildId,
      settingsUpdate: GuildSettings => GuildSettings
  ): IO[Path] = {
    getGuildSettings(guildId).flatMap { settings =>
      IO.blocking(
        Files.write(
          guildSettingsFile(guildId),
          Seq(settingsUpdate(settings).asJson.deepMerge(Json.obj("version" -> "1".asJson)).noSpaces).asJava
        )
      )
    }
  }

  def getGuildSettings(guildId: GuildId): IO[GuildSettings] = {
    cache.cachingF(guildId)(Some(30.minutes))(getGuildSettingsUncached(guildId))
  }

  def updateGuildSettings(guildId: GuildId, settings: GuildSettings => GuildSettings): IO[Unit] =
    updateGuildSettingsFile(guildId, settings) *> cache.remove(guildId)

}
