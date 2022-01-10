package miko

import ackcord.data.{GuildId, GuildMember, User, UserId}
import ackcord.requests.Requests
import ackcord.{CacheSnapshot, Streamable}
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.benmanes.caffeine.cache.Caffeine
import com.zaxxer.hikari.HikariDataSource
import controllers.AssetsComponents
import doobie.hikari.HikariTransactor
import miko.commands.MikoHelpCommand
import miko.db.DBAccess
import miko.settings._
import miko.util.SGFCPool
import miko.web.WebEvents
import miko.web.controllers.{MikoControllerComponents, WebController}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import play.api._
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.mvc.{ControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.filters.HttpFiltersComponents
import play.filters.csp.{CSPConfig, CSPFilter, DefaultCSPProcessor, DefaultCSPResultProcessor}
import play.filters.gzip.{GzipFilter, GzipFilterConfig}
import scalacache.caffeine._

import java.security.Security
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class MikoApplication extends ApplicationLoader {
  Security.addProvider(new BouncyCastleProvider)

  override def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader)
      .foreach(_.configure(context.environment, context.initialConfiguration, Map.empty))
    new MikoComponents(context).application
  }
}

class MikoComponents(context: ApplicationLoader.Context)
    extends BuiltInComponentsFromContext(context)
    with HttpFiltersComponents
    with AssetsComponents
    with EvolutionsComponents
    with DBComponents
    with HikariCPComponents {

  implicit val ioRuntime: IORuntime = cats.effect.unsafe.implicits.global

  implicit val ioStreamable: Streamable[IO] = new Streamable[IO] {
    override def toSource[A](fa: IO[A]): Source[A, NotUsed] = Source.future(fa.unsafeToFuture())
  }

  override lazy val httpFilters: Seq[EssentialFilter] = {
    val filters              = super.httpFilters ++ enabledFilters
    val enabledFiltersConfig = configuration.get[Seq[String]]("play.filters.enabled")
    val enabledFiltersCode   = filters.map(_.getClass.getName)

    val notEnabledFilters = enabledFiltersConfig.diff(enabledFiltersCode)

    if (notEnabledFilters.nonEmpty) {
      LoggerFactory
        .getLogger(this.getClass)
        .warn(s"Found filters enabled in the config but not in code: $notEnabledFilters")
    }

    filters
  }

  lazy val enabledFilters: Seq[EssentialFilter] = {
    val baseFilters = Seq(
      new CSPFilter(new DefaultCSPResultProcessor(new DefaultCSPProcessor(CSPConfig.fromConfiguration(configuration)))),
      new GzipFilter(GzipFilterConfig.fromConfiguration(configuration))
    )

    val filterSeq = Seq(
      true                         -> baseFilters,
      context.devContext.isDefined -> Nil
    )

    filterSeq.collect {
      case (true, seq) => seq
    }.flatten
  }

  implicit lazy val impEnvironment: Environment = environment

  implicit lazy val typedSystem: ActorSystem[Nothing]             = actorSystem.toTyped
  implicit lazy val impControllerComponents: ControllerComponents = controllerComponents

  implicit lazy val config: MikoConfig = MikoConfig()
  implicit lazy val requests: Requests = {
    implicit val timeout: Timeout = 1.minute
    Await.result(mikoRoot.ask[Requests](MikoRoot.GetRequests)(timeout, scheduler), 1.minutes)
  }
  implicit lazy val webEvents: WebEvents = WebEvents.create

  private def makeCache[K, V]: CaffeineCache[IO, K, V] = CaffeineCache[IO, K, V](Caffeine.newBuilder.build[K, scalacache.Entry[V]]())

  implicit lazy val guildSettingsCache: CaffeineCache[IO, GuildId, GuildSettings] = makeCache
  implicit lazy val memberCache: CaffeineCache[IO, UserId, (User, GuildMember)]   = makeCache

  implicit lazy val settings: SettingsAccess = new SettingsAccess()
  implicit lazy val db: DBAccess[IO]         = new DBAccess[IO]()

  val ece: ExecutorService = Executors.newFixedThreadPool(32)

  private def asyncNewHikariTransactor(
      driverClassName: String,
      url: String,
      user: String,
      pass: String,
      connectEC: ExecutionContext
  ) = {
    import doobie._

    for {
      _ <- IO.blocking(Class.forName(driverClassName))
      xa = Transactor.fromDataSource[IO](
        new HikariDataSource,
        connectEC
      )
      _ <- xa.configure { ds =>
        IO.blocking {
          ds.setJdbcUrl(url)
          ds.setUsername(user)
          ds.setPassword(pass)
        }
      }
    } yield xa
  }

  implicit lazy val taskTransactor: HikariTransactor[IO] = {
    val ce = ExecutionContext.fromExecutor(ece)
    asyncNewHikariTransactor("org.postgresql.Driver", config.dbUrl, config.dbUsername, config.dbPassword, ce)
      .unsafeRunSync()
  }

  lazy val shutdown: CoordinatedShutdown = CoordinatedShutdown(actorSystem)

  lazy val cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]] = {
    implicit val timeout: Timeout = 1.minute
    Await.result(
      mikoRoot
        .ask[ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]]](MikoRoot.GetCacheStorage)(timeout, scheduler),
      1.minute
    )
  }

  implicit lazy val mikoComponents: MikoControllerComponents =
    MikoControllerComponents(cacheStorage, requests, memberCache, httpErrorHandler, ioRuntime, controllerComponents)

  lazy val webController = new WebController(assetsFinder, helpCommand, environment)

  lazy val helpCommand: MikoHelpCommand = {
    implicit val timeout: Timeout = 1.minute
    Await.result(mikoRoot.ask[MikoHelpCommand](MikoRoot.GetHelpCommands)(timeout, scheduler), 1.minutes)
  }

  val mikoRoot: ActorRef[MikoRoot.Command] = typedSystem.systemActorOf(MikoRoot(shutdown, devContext), "MikoRoot")

  //Runs evolutions
  locally {
    println("Running evolutions")
    applicationEvolutions
  }

  override val router: Router = new _root_.router.Routes(httpErrorHandler, webController, assets)

  //TODO: Use coordinated shutdown
  /*
  applicationLifecycle.addStopHook(() => {
    println("Stopping Discord bot")
    implicit val timeout: Timeout = Timeout(1.minute)
    import scalacache.modes.scalaFuture._

    for {
      _ <- shutdownHandler ? DiscordShard.StopShard
      _ <- dbIoTransactor.configure(ds => Async[IO].delay(ds.close())).unsafeToFuture()
      _ <- guildSettingsCache.close[Future]()
      _ <- vtSettingsCache.close[Future]()
      _ <- commandPermissionsCache.close[Future]()
    } yield {
      ece.shutdown()
      ete.shutdown()
    }
  })
 */
}
