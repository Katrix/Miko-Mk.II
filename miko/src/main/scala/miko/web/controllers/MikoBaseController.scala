package miko.web.controllers

import ackcord.CacheSnapshot

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import play.api.i18n.I18nSupport
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import ackcord.data.{GuildId, Permission, RawSnowflake, UserId}
import miko.CacheStorage
import miko.util.SGFCPool
import miko.web.controllers.MikoBaseController.PublicActionRefiner
import miko.web.models.{GuildViewInfo, ViewInfo}
import play.api.mvc._

import scala.util.control.NonFatal

trait MikoBaseController extends BaseController with I18nSupport { outer =>

  type AuthedRequest[A] = MikoBaseController.AuthedRequest[A]
  val AuthedRequest: MikoBaseController.AuthedRequest.type = MikoBaseController.AuthedRequest
  type GuildRequest[A] = MikoBaseController.GuildRequest[A]
  val GuildRequest: MikoBaseController.GuildRequest.type = MikoBaseController.GuildRequest

  def cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]]
  implicit def actorSystem: ActorSystem[Nothing]

  implicit lazy val executionContext: ExecutionContext = defaultExecutionContext

  def isAuthed[A](request: Request[A]): Boolean = request.session.get("userId").isDefined

  val AuthedAction: PublicActionRefiner[Request, AuthedRequest] =
    new PublicActionRefiner[Request, AuthedRequest] {
      override protected def executionContext: ExecutionContext = outer.executionContext

      override protected def refine[A](
          request: Request[A]
      ): Future[Either[Result, AuthedRequest[A]]] = {
        implicit val ec: ExecutionContext = executionContext
        request.session
          .get("userId")
          .fold[Future[Either[Result, AuthedRequest[A]]]](Future.successful(Left(Unauthorized))) { userId =>
            implicit val timeout: Timeout = Timeout(10.seconds)

            cacheStorage.ask[CacheSnapshot](SGFCPool.Msg(_, CacheStorage.GetLatestCache)).map { cache =>
              cache.getUser(UserId(RawSnowflake(userId))).toRight(NotFound).map { user =>
                AuthedRequest(ViewInfo(user, cache), request)
              }
            }
          }
      }
    }

  def authedGuildRefiner(
      rawGuildId: String
  ): PublicActionRefiner[AuthedRequest, GuildRequest] =
    new PublicActionRefiner[AuthedRequest, GuildRequest] {
      override protected def executionContext: ExecutionContext = outer.executionContext

      override protected def refine[A](
          request: AuthedRequest[A]
      ): Future[Either[Result, GuildRequest[A]]] = {

        val isNumber = try {
          rawGuildId.toLong
          true
        } catch {
          case NonFatal(_) => false
        }

        val info    = request.info
        val guildInfo = for {
          guildId <- if (isNumber) Some(GuildId(RawSnowflake(rawGuildId))) else None
          guild   <- info.cache.getGuild(guildId)
          member  <- guild.members.get(info.user.id)
        } yield
          GuildViewInfo(
            isGuest = false,
            guildId,
            guild.voiceStates.get(member.userId).flatMap(_.channelId).isDefined,
            member.permissions(guild).hasPermissions(Permission.Administrator),
            guild,
            info.user,
            member,
            info.cache
          )

        guildInfo.fold[Future[Either[Result, GuildRequest[A]]]](Future.successful(Left(NotFound))) { info =>
          Future.successful(Right(GuildRequest(info, request)))
        }
      }
    }

  def adminFilter: ActionFilter[GuildRequest] = new ActionFilter[GuildRequest] {
    override protected def executionContext: ExecutionContext = outer.executionContext

    override protected def filter[A](request: GuildRequest[A]): Future[Option[Result]] =
      if (request.info.isAdmin) Future.successful(None) else Future.successful(Some(Unauthorized))
  }

  def AuthenticatedAction: ActionBuilder[AuthedRequest, AnyContent] = Action.andThen(AuthedAction)

  def GuildAction(rawGuildId: String): ActionBuilder[GuildRequest, AnyContent] =
    AuthenticatedAction.andThen(authedGuildRefiner(rawGuildId))

  def AdminGuildAction(rawGuildId: String): ActionBuilder[GuildRequest, AnyContent] =
    GuildAction(rawGuildId).andThen(adminFilter)
}
object MikoBaseController {
  case class AuthedRequest[A](info: ViewInfo, request: Request[A])           extends WrappedRequest[A](request)
  case class GuildRequest[A](info: GuildViewInfo, request: AuthedRequest[A]) extends WrappedRequest[A](request)

  trait PublicActionRefiner[-R[_], +P[_]] extends ActionRefiner[R, P] {

    def refineResult[A](request: R[A]): Future[Either[Result, P[A]]] = refine(request)
  }
}
