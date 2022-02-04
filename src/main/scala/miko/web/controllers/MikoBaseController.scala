package miko.web.controllers

import ackcord.data.SnowflakeType.SnowflakeType
import ackcord.data.{GuildId, GuildMember, Permission, RawSnowflake, User, UserId}
import ackcord.requests.GetUser
import ackcord.syntax._
import ackcord.{CacheSnapshot, Requests}
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import miko.CacheStorage
import miko.util.SGFCPool
import miko.web.controllers.MikoBaseController.PublicActionRefiner
import miko.web.models.{GuildViewInfo, ViewInfo}
import play.api.i18n.I18nSupport
import play.api.mvc._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

trait MikoBaseController extends BaseController with I18nSupport with CircePlayController { outer =>

  type MaybeAuthedRequest[A] = MikoBaseController.MaybeAuthedRequest[A]
  val MaybeAuthedRequest: MikoBaseController.MaybeAuthedRequest.type = MikoBaseController.MaybeAuthedRequest
  type AuthedRequest[A] = MikoBaseController.AuthedRequest[A]
  val AuthedRequest: MikoBaseController.AuthedRequest.type = MikoBaseController.AuthedRequest
  type GuildRequest[A] = MikoBaseController.GuildRequest[A]
  val GuildRequest: MikoBaseController.GuildRequest.type = MikoBaseController.GuildRequest

  def cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]]
  def requests: Requests

  implicit def actorSystem: ActorSystem[Nothing] = requests.system

  implicit lazy val executionContext: ExecutionContext = defaultExecutionContext

  implicit def memberCache: scalacache.Cache[IO, UserId, (User, GuildMember)]

  implicit def ioRuntime: IORuntime

  def isAuthed[A](request: Request[A]): Boolean = request.session.get("userId").isDefined

  val maybeAuthedAction: ActionTransformer[Request, MaybeAuthedRequest] =
    new ActionTransformer[Request, MaybeAuthedRequest] {
      override protected def executionContext: ExecutionContext = outer.executionContext

      override protected def transform[A](request: Request[A]): Future[MaybeAuthedRequest[A]] = {
        implicit val ec: ExecutionContext = executionContext

        request.session
          .get("userId")
          .fold[Future[MaybeAuthedRequest[A]]](Future.successful(MaybeAuthedRequest(None, request))) { userId =>
            implicit val timeout: Timeout = Timeout(10.seconds)

            cacheStorage.ask[CacheSnapshot](SGFCPool.Msg(_, CacheStorage.GetLatestCache)).map { cache =>
              MaybeAuthedRequest(Some(ViewInfo(UserId(userId), cache)), request)
            }
          }
      }
    }

  val authedAction: PublicActionRefiner[MaybeAuthedRequest, AuthedRequest] =
    new PublicActionRefiner[MaybeAuthedRequest, AuthedRequest] {
      override protected def executionContext: ExecutionContext = outer.executionContext

      override def refineResult[A](
          request: MaybeAuthedRequest[A]
      ): Future[Either[Result, AuthedRequest[A]]] =
        request.maybeInfo.fold[Future[Either[Result, AuthedRequest[A]]]](Future.successful(Left(Unauthorized))) {
          info =>
            Future.successful(Right(AuthedRequest(info, request.request)))
        }
    }

  def authedGuildRefiner(
      rawGuildId: String
  ): PublicActionRefiner[AuthedRequest, GuildRequest] =
    new PublicActionRefiner[AuthedRequest, GuildRequest] {
      override protected def executionContext: ExecutionContext = outer.executionContext

      override def refineResult[A](
          request: AuthedRequest[A]
      ): Future[Either[Result, GuildRequest[A]]] = {
        implicit val ec: ExecutionContext = executionContext

        val isNumber = try {
          rawGuildId.toLong
          true
        } catch {
          case NonFatal(_) => false
        }

        val info = request.info
        val guildInfo = for {
          guildId <- if (isNumber) Some(GuildId(RawSnowflake(rawGuildId))) else None
          guild   <- info.cache.getGuild(guildId)
        } yield {
          memberCache
            .cachingF(info.userId)(Some(2.minutes)) {
              IO.fromFuture {
                IO {
                  val member = guild.members
                    .get(info.userId)
                    .fold(requests.singleFutureSuccess(guild.fetchGuildMember(info.userId)).map(_.toGuildMember(guild.id)))(
                      Future.successful
                    )

                  val user = info.cache
                    .getUser(info.userId)
                    .fold(requests.singleFutureSuccess(GetUser(info.userId)))(Future.successful)

                  user.zip(member)
                }
              }
            }
            .map { case (user, member) =>
              GuildViewInfo(
                guildId,
                guild.voiceStates.get(member.userId).flatMap(_.channelId),
                member.permissions(guild).hasPermissions(Permission.Administrator),
                guild,
                member,
                user,
                info.cache
              )
            }.unsafeToFuture()
        }

        guildInfo.fold[Future[Either[Result, GuildRequest[A]]]](Future.successful(Left(NotFound))) { info =>
          info.map(info => Right(GuildRequest(info, request)))
        }
      }
    }

  def adminFilter: ActionFilter[GuildRequest] = new ActionFilter[GuildRequest] {
    override protected def executionContext: ExecutionContext = outer.executionContext

    override protected def filter[A](request: GuildRequest[A]): Future[Option[Result]] =
      if (request.info.isAdmin) Future.successful(None) else Future.successful(Some(Unauthorized))
  }

  def MaybeAuthenticatedAction: ActionBuilder[MaybeAuthedRequest, AnyContent] = Action.andThen(maybeAuthedAction)

  def AuthenticatedAction: ActionBuilder[AuthedRequest, AnyContent] = MaybeAuthenticatedAction.andThen(authedAction)

  def GuildAction(rawGuildId: String): ActionBuilder[GuildRequest, AnyContent] =
    AuthenticatedAction.andThen(authedGuildRefiner(rawGuildId))

  def AdminGuildAction(rawGuildId: String): ActionBuilder[GuildRequest, AnyContent] =
    GuildAction(rawGuildId).andThen(adminFilter)

  implicit def snowflakeJsonEncoder[A]: io.circe.Encoder[SnowflakeType[A]] =
    io.circe.Encoder.encodeString.contramap(_.asString)
}
object MikoBaseController {
  trait PublicActionRefiner[-R[_], +P[_]] extends ActionRefiner[R, P] {
    def refineResult[A](request: R[A]): Future[Either[Result, P[A]]]

    override protected def refine[A](request: R[A]): Future[Either[Result, P[A]]] = refineResult(request)
  }

  sealed trait HasMaybeAuthRequest {
    def maybeInfo: Option[ViewInfo]
  }

  case class AuthedRequest[A](info: ViewInfo, request: Request[A])
      extends WrappedRequest[A](request)
      with HasMaybeAuthRequest {
    override def maybeInfo: Option[ViewInfo] = Some(info)
  }
  case class MaybeAuthedRequest[A](maybeInfo: Option[ViewInfo], request: Request[A])
      extends WrappedRequest[A](request)
      with HasMaybeAuthRequest
  case class GuildRequest[A](info: GuildViewInfo, request: AuthedRequest[A]) extends WrappedRequest[A](request)
}
