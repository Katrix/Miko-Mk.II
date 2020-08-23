package miko

import ackcord.data.{RawSnowflake, UserId}
import akka.actor.typed.ActorSystem
import com.typesafe.config.Config

import scala.jdk.CollectionConverters._

class MikoConfig(val config: Config) {
  lazy val token: String        = config.getString("miko.token")
  lazy val clientId: String     = config.getString("miko.client-id")
  lazy val clientSecret: String = config.getString("miko.client-secret")
  lazy val botOwners: Seq[UserId] =
    config.getStringList("miko.bot-owners").asScala.toSeq.map((RawSnowflake(_: String)).andThen(UserId.apply))

  lazy val slaveTokens: Seq[String] = config.getStringList("miko.slave-tokens").asScala.toSeq

  lazy val dbUrl: String      = config.getString("miko.db.url")
  lazy val dbUsername: String = config.getString("miko.db.username")
  lazy val dbPassword: String = config.getString("miko.db.password")

  lazy val useDummyData: Boolean = config.getBoolean("miko.use-dummy-data")

  lazy val webSecretKey: String = config.getString("miko.web.secret-key")
  lazy val webInterface: String = config.getString("miko.web.interface")
  lazy val webPort: Int         = config.getInt("miko.web.port")
}
object MikoConfig {
  def apply(config: Config): MikoConfig                          = new MikoConfig(config)
  def apply()(implicit system: ActorSystem[Nothing]): MikoConfig = new MikoConfig(system.settings.config)
}
