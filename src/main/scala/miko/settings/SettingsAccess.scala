package miko.settings

import java.nio.file.{Files, Path}

import ackcord.data.GuildId
import io.circe._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import play.api.Environment
import scalacache.Cache
import scalacache.CatsEffect.modes.async
import zio.blocking._
import zio.interop.catz._
import zio.{RIO, Task}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class SettingsAccess(implicit cache: Cache[GuildSettings], environment: Environment) {

  private val logger = LoggerFactory.getLogger(classOf[SettingsAccess])

  private def guildSettingsFile(guildId: GuildId): Path =
    environment.rootPath.toPath.resolve("configs").resolve(s"${guildId.asString}.json")

  private def getGuildSettingsUncached(guildId: GuildId): RIO[Blocking, GuildSettings] = {
    val file          = guildSettingsFile(guildId)
    val notExists     = effectBlocking(Files.notExists(file))
    val createDefault = createNewConfig(guildId)

    val readConfig = effectBlocking(parseConfig(Files.readAllLines(file).asScala.mkString("\n"))).map {
      case Left(e) =>
        logger.error("Failed to parse config", e)
        GuildSettings()
      case Right(value) => value
    }

    createDefault.whenM(notExists) *> readConfig
  }

  private def parseConfig(s: String): Either[Error, GuildSettings] = {
    for {
      json    <- parser.parse(s)
      version <- json.hcursor.get[String]("version")
      config <- version match {
        case "1" => json.as[GuildSettings]
      }
    } yield config
  }

  private def createNewConfig(guildId: GuildId): RIO[Blocking, Path] = {
    val file = guildSettingsFile(guildId)
    effectBlocking {
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
  ): RIO[Blocking, Path] = {
    getGuildSettings(guildId).flatMap { settings =>
      effectBlocking(
        Files.write(
          guildSettingsFile(guildId),
          Seq(settingsUpdate(settings).asJson.deepMerge(Json.obj("version" -> "1".asJson)).noSpaces).asJava
        )
      )
    }
  }

  def getGuildSettings(guildId: GuildId): RIO[Blocking, GuildSettings] =
    cache.cachingF(guildId)(Some(30.minutes))(getGuildSettingsUncached(guildId))

  def updateGuildSettings(guildId: GuildId, settings: GuildSettings => GuildSettings): RIO[Blocking, Unit] =
    updateGuildSettingsFile(guildId, settings) *> cache.remove[Task](guildId).unit

}
