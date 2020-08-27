package miko

import java.security.Security
import java.util.concurrent.{ExecutorService, Executors}

import ackcord.data.{GuildMember, User}
import ackcord.requests.Requests
import ackcord.{CacheSnapshot, Streamable}
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.effect.Blocker
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
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, HikariCPComponents}
import play.api.mvc.{ControllerComponents, EssentialFilter}
import play.api.routing.Router
import play.api._
import play.filters.HttpFiltersComponents
import play.filters.csp.{CSPConfig, CSPFilter, DefaultCSPProcessor, DefaultCSPResultProcessor}
import play.filters.gzip.{GzipFilter, GzipFilterConfig}
import scalacache.caffeine._
import zio.blocking.Blocking
import zio.interop.catz._
import zio.{RIO, Task, ZIO}

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

  implicit val zioRuntime: zio.Runtime[zio.ZEnv] = zio.Runtime.default

  implicit val taskStreamable: Streamable[Task] = new Streamable[Task] {
    override def toSource[A](fa: Task[A]): Source[A, NotUsed] = Source.future(zioRuntime.unsafeRunToFuture(fa))
  }
  implicit val blockingStreamable: Streamable[RIO[Blocking, *]] = new Streamable[RIO[Blocking, *]] {
    override def toSource[A](fa: RIO[Blocking, A]): Source[A, NotUsed] = Source.future(zioRuntime.unsafeRunToFuture(fa))
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

  implicit lazy val guildSettingsCache: CaffeineCache[GuildSettings] = CaffeineCache[GuildSettings]
  implicit lazy val memberCache: CaffeineCache[(User, GuildMember)]  = CaffeineCache[(User, GuildMember)]

  implicit lazy val settings: SettingsAccess = new SettingsAccess()
  implicit lazy val db: DBAccess[Task]       = new DBAccess[Task]()

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
      _        <- ZIO.effect(Class.forName(driverClassName))
      executor <- zio.blocking.blockingExecutor
      xa <- ZIO.effect(
        Transactor.fromDataSource[Task](
          new HikariDataSource,
          connectEC,
          Blocker.liftExecutionContext(executor.asEC)
        )
      )
      _ <- xa.configure { ds =>
        ZIO.effect {
          ds.setJdbcUrl(url)
          ds.setUsername(user)
          ds.setPassword(pass)
        }
      }
    } yield xa
  }

  implicit lazy val taskTransactor: HikariTransactor[Task] = {
    val ce = ExecutionContext.fromExecutor(ece)

    zioRuntime.unsafeRun(
      asyncNewHikariTransactor("org.postgresql.Driver", config.dbUrl, config.dbUsername, config.dbPassword, ce)
    )
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
    MikoControllerComponents(cacheStorage, requests, memberCache, httpErrorHandler, zioRuntime, controllerComponents)

  lazy val webController = new WebController(assetsFinder, helpCommand)

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
