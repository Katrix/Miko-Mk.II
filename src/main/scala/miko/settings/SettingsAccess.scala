package miko.settings

import ackcord.data.GuildId
import cats.effect.IO
import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine}
import com.google.common.cache.LoadingCache
import io.circe._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import play.api.Environment
import scalacache.Entry
import scalacache.caffeine.CaffeineCache

import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._
import scala.jdk.StreamConverters._

class SettingsAccess(
    private val cache: CaffeineCache[IO, GuildId, GuildSettings],
    private val allKnownGuilds: TrieMap[GuildId, Boolean],
    private val unsafe: SettingsAccess.UnsafeSettingsAccess
) {

  //TODO: Use settingsUpdate better and remove race conditions here
  private def updateGuildSettingsFile(
      guildId: GuildId,
      settingsUpdate: GuildSettings => GuildSettings
  ): IO[Path] = {
    for {
      settings <- getGuildSettings(guildId)
      res <- IO.blocking(
        Files.write(
          unsafe.guildSettingsFile(guildId),
          Seq(settingsUpdate(settings).asJson.deepMerge(Json.obj("version" -> "1".asJson)).noSpaces).asJava
        )
      )
    } yield res
  }

  def getGuildSettings(guildId: GuildId): IO[GuildSettings] =
    cache.cachingF(guildId)(Some(30.minutes))(IO.blocking(unsafe.getGuildSettingsUncached(guildId)))

  def updateGuildSettings(guildId: GuildId, settings: GuildSettings => GuildSettings): IO[Unit] =
    updateGuildSettingsFile(guildId, settings) *> cache.remove(guildId)

  def getAllSettings: IO[Map[GuildId, GuildSettings]] =
    IO(allKnownGuilds)
      .flatMap { s =>
        IO.blocking(
          cache.underlying
            .asInstanceOf[LoadingCache[GuildId, scalacache.Entry[GuildSettings]]]
            .getAll(s.keySet.asJava)
        )
      }
      .map(res => res.asScala.toMap.map(t => t._1 -> t._2.value))
}
object SettingsAccess {
  private class UnsafeSettingsAccess(private val allKnownGuilds: TrieMap[GuildId, Boolean])(
      implicit environment: Environment
  ) {
    private val logger = LoggerFactory.getLogger(classOf[SettingsAccess.UnsafeSettingsAccess])

    def guildSettingsFile(guildId: GuildId): Path =
      environment.rootPath.toPath.resolve("configs").resolve(s"${guildId.asString}.json")

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

    def getGuildSettingsUncached(guildId: GuildId): GuildSettings = {
      val file = guildSettingsFile(guildId)

      if (Files.notExists(file)) {
        createNewConfig(guildId)
      }

      parseConfig(Files.readAllLines(file).asScala.mkString("\n")) match {
        case Left(e) =>
          logger.error(s"Failed to parse config for ${guildId.asString}", e)
          GuildSettings()
        case Right(value) => value
      }
    }

    private def createNewConfig(guildId: GuildId): Path = {
      val file = guildSettingsFile(guildId)
      Files.createDirectories(file.getParent)
      val res = Files.write(
        file,
        Seq(GuildSettings().asJson.deepMerge(Json.obj("version" -> "1".asJson)).noSpaces).asJava
      )

      allKnownGuilds.put(guildId, true)
      res
    }
  }

  def apply()(implicit environment: Environment): IO[SettingsAccess] = {
    val allKnownGuilds = TrieMap.empty[GuildId, Boolean]
    val addKnownGuilds = IO.blocking(
      allKnownGuilds ++= Files
        .list(environment.rootPath.toPath.resolve("configs"))
        .filter(_.endsWith(".json"))
        .filter(Files.isRegularFile(_))
        .map(p => GuildId(p.getFileName.toString))
        .toScala(Set)
        .map(_ -> true)
    )

    val unsafeAccess = new UnsafeSettingsAccess(allKnownGuilds)
    //noinspection ConvertExpressionToSAM
    val cache = CaffeineCache[IO, GuildId, GuildSettings](
      Caffeine.newBuilder
        .expireAfterAccess(30.minutes.toJava)
        .build[GuildId, scalacache.Entry[GuildSettings]](
          new CacheLoader[GuildId, scalacache.Entry[GuildSettings]] {
            override def load(key: GuildId): Entry[GuildSettings] = scalacache.Entry(unsafeAccess.getGuildSettingsUncached(key), None)
          }
        )
    )

    addKnownGuilds.map(_ => new SettingsAccess(cache, allKnownGuilds, unsafeAccess))
  }
}
