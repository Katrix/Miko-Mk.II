package miko

import java.security.Security
import java.util.concurrent.{ExecutorService, Executors}

import ackcord.CacheSnapshot

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.mvc.{ControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.api.{Application, ApplicationLoader, BuiltInComponentsFromContext, LoggerConfigurator}
import play.filters.HttpFiltersComponents
import controllers.AssetsComponents
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.CoordinatedShutdown
import akka.util.Timeout
import cats.effect.{Async, Blocker, ContextShift, IO}
import com.zaxxer.hikari.HikariDataSource
import doobie.hikari.HikariTransactor
import ackcord.requests.Requests
import miko.settings._
import miko.util.Implicits._
import miko.util.SGFCPool
import miko.web.WebEvents
import miko.web.controllers.{GuestController, WebController}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import play.filters.csp.{CSPConfig, CSPFilter, DefaultCSPProcessor, DefaultCSPResultProcessor}
import play.filters.gzip.{GzipFilter, GzipFilterConfig}
import scalacache.CatsEffect.modes.async
import scalacache.caffeine._

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

  implicit lazy val typedSystem: ActorSystem[Nothing]             = actorSystem.toTyped
  implicit lazy val impControllerComponents: ControllerComponents = controllerComponents

  implicit lazy val config: MikoConfig = MikoConfig()
  implicit lazy val requests: Requests = {
    implicit val timeout: Timeout = 1.minute
    Await.result(mikoRoot.ask[Requests](MikoRoot.GetRequests)(timeout, scheduler), 1.minutes)
  }
  implicit lazy val webEvents: WebEvents = WebEvents.create

  implicit lazy val guildSettingsCache: CaffeineCache[GuildSettings] = CaffeineCache[GuildSettings]
  implicit lazy val commandPermissionsCache: CaffeineCache[Set[CommandPermission]] =
    CaffeineCache[Set[CommandPermission]]

  implicit val cs: ContextShift[IO] = IO.contextShift(typedSystem.executionContext)

  val ece: ExecutorService = Executors.newFixedThreadPool(32)
  val ete: ExecutorService = Executors.newCachedThreadPool()

  private def asyncNewHikariTransactor[M[_]: Async: ContextShift](
      driverClassName: String,
      url: String,
      user: String,
      pass: String,
      connectEC: ExecutionContext,
      transactEC: ExecutionContext
  ) = {
    import cats.syntax.all._
    import doobie._

    for {
      _ <- Async[M].delay(Class.forName(driverClassName))
      xa <- Async[M].delay(
        Transactor.fromDataSource[M](new HikariDataSource, connectEC, Blocker.liftExecutionContext(transactEC))
      )
      _ <- xa.configure { ds =>
        Async[M].delay {
          ds.setJdbcUrl(url)
          ds.setUsername(user)
          ds.setPassword(pass)
        }
      }
    } yield xa
  }

  implicit lazy val dbIoTransactor: HikariTransactor[IO] = {
    val ce = ExecutionContext.fromExecutor(ece)
    val te = ExecutionContext.fromExecutor(ete)

    import cats.effect._
    asyncNewHikariTransactor[IO]("org.postgresql.Driver", config.dbUrl, config.dbUsername, config.dbPassword, ce, te)
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

  lazy val webController   = new WebController(cacheStorage)
  lazy val guestController = new GuestController

  val mikoRoot: ActorRef[MikoRoot.Command] = typedSystem.systemActorOf(MikoRoot[IO](shutdown), "MikoRoot")

  //Runs evolutions
  locally {
    println("Running evolutions")
    applicationEvolutions
  }

  override val router: Router = new _root_.router.Routes(httpErrorHandler, webController, guestController, assets)

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
