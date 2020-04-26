package miko.web.controllers

import java.util.UUID

import ackcord.CacheSnapshot

import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.mvc._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import doobie.util.transactor.Transactor
import ackcord.data.User
import ackcord.requests.OAuth
import ackcord.requests.{Requests, RequestResponse}
import ackcord.requests.GetCurrentUser
import ackcord.util.GuildRouter
import akka.stream.{FlowShape, SourceShape}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, Sink, Source}
import akka.util.Timeout
import cats.data.EitherT
import miko.{CacheStorage, MikoConfig}
import miko.db.DBMemoizedAccess
import miko.dummy.{CommandDummyData, MusicDummyData}
import miko.services.{
  ClientMessage,
  ClientMusicMessage,
  MusicSeeked,
  MusicSetDefaultVolume,
  MusicSetVolume,
  MusicToggleLoop,
  MusicTogglePaused,
  ServerMessage
}
import miko.settings.GuildSettings
import miko.web.WebEvents
import miko.web.forms.WebSettings
import miko.web.models.{GuildViewInfo, ViewInfo}
import scalacache.Cache
import scalacache.CatsEffect.modes.async
import io.circe._
import io.circe.syntax._
import miko.music.GuildMusicHandler
import miko.util.SGFCPool
import play.api.http.websocket.{Message, TextMessage}
import play.twirl.api.Html

class WebController(val cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]])(
    implicit requests: Requests,
    webEvents: WebEvents,
    mikoConfig: MikoConfig,
    xa: Transactor[IO],
    cs: ContextShift[IO],
    guildSettingsCache: Cache[GuildSettings],
    controllerComponents: ControllerComponents
) extends AbstractController(controllerComponents)
    with MikoBaseController { outer =>

  implicit val actorSystem: ActorSystem[Nothing] = requests.system

  private val self = routes.WebController

  def entry: Action[AnyContent] = Action { implicit request =>
    if (isAuthed(request)) Redirect(self.selectGuild())
    else Ok(Html(miko.web.views.entry()))
  }

  def logout: Action[AnyContent] = AuthenticatedAction { implicit request =>
    Ok(Html(miko.web.views.entry())).withNewSession
  }

  def codeGrant: Action[AnyContent] = Action { implicit request =>
    val state = UUID.randomUUID()
    val uri = OAuth.codeGrantUri(
      mikoConfig.clientId,
      Seq(OAuth.Scope.Identify),
      state.toString,
      self.authenticateOAuth(None, None).absoluteURL,
      OAuth.PromptType.NonePrompt
    )
    Redirect(uri.toString).withSession("State" -> state.toString)
  }

  def authenticateOAuth(optCode: Option[String], optState: Option[String]): Action[AnyContent] = Action.async {
    implicit request =>
      val res = for {
        code         <- optCode
        state        <- optState
        sessionState <- request.session.get("State")
        if state == sessionState
      } yield {
        OAuth
          .tokenExchange(
            mikoConfig.clientId,
            mikoConfig.clientSecret,
            OAuth.GrantType.AuthorizationCode,
            code,
            self.authenticateOAuth(None, None).absoluteURL,
            Seq(OAuth.Scope.Identify)
          )
          .flatMap { token =>
            requests
              .copy(credentials = OAuth2BearerToken(token.accessToken))
              .singleFuture(GetCurrentUser) //TODO: RetryFuture is bugged
              .map(_ -> token)
          }
          .map {
            case (response: RequestResponse[User], token) =>
              Redirect(self.selectGuild()).withSession(
                "userId"       -> response.data.id.asString,
                "accessToken"  -> token.accessToken,
                "expiresIn"    -> token.expiresIn.toString,
                "refreshToken" -> token.refreshToken
              )
            case _ =>
              BadGateway("Something went wrong. If this continues to happen, message me and I'll look at it")
          }
      }

      res.getOrElse(Future.successful(BadRequest))
  }

  def selectGuild: Action[AnyContent] = AuthenticatedAction { implicit request =>
    implicit val info: ViewInfo = request.info
    Ok(Html(miko.web.views.selectGuild()))
  }

  def guildHome(rawGuildId: String): Action[AnyContent] = GuildAction(rawGuildId) { implicit request =>
    implicit val info: GuildViewInfo = request.info
    Ok(Html(miko.web.views.guildHome()))
  }

  def help(rawGuildId: String): Action[AnyContent] = GuildAction(rawGuildId) { implicit request =>
    implicit val info: GuildViewInfo = request.info
    Ok(Html(miko.web.views.help(CommandDummyData)))
  }

  def music(rawGuildId: String): Action[AnyContent] = GuildAction(rawGuildId) { implicit request =>
    implicit val info: GuildViewInfo = request.info
    Ok(Html(miko.web.views.music(MusicDummyData)))
  }

  def settings(rawGuildId: String): Action[AnyContent] = AdminGuildAction(rawGuildId).async { implicit request =>
    implicit val info: GuildViewInfo = request.info
    DBMemoizedAccess
      .getGuildSettings[IO](info.guild.id)
      .map { guildSettings =>
        Ok(Html(miko.web.views.settings(guildSettings, WebSettings.settingsForm)))
      }
      .unsafeToFuture()
  }

  def submitSettings(rawGuildId: String): Action[AnyContent] = AdminGuildAction(rawGuildId).async { implicit request =>
    implicit val info: GuildViewInfo = request.info
    val form                         = WebSettings.settingsForm.bindFromRequest()

    val res = if (form.hasErrors) {
      DBMemoizedAccess
        .getGuildSettings[IO](info.guild.id)
        .map(guildSettings => BadRequest(Html(miko.web.views.settings(guildSettings, form))))
    } else {
      DBMemoizedAccess
        .updateSettings[IO](info.guildId, form.value.get)
        .as(Redirect(routes.WebController.settings(rawGuildId)).flashing("success" -> "Settings updated"))
    }

    res.unsafeToFuture()
  }

  def log(rawGuildId: String): Action[AnyContent] = TODO

  def websocket = WebSocket { implicit requestHeader =>
    import cats.instances.future._
    EitherT(AuthedAction.refineResult(Request(requestHeader, ()))).map { guildRequest =>
      import miko.MikoProtocol._
      implicit val timeout: Timeout = Timeout(10.seconds)

      val flowGraph = GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val serverEvents: SourceShape[TextMessage] =
          builder.add(
            webEvents.subscribe
              .filter(_.applicableUsers.contains(guildRequest.info.user.id))
              .map(_.event: ServerMessage)
              .map(_.asJson.noSpaces)
              .map(s => TextMessage(s))
          )

        val allClientMessages: FlowShape[Message, ClientMessage] = builder.add(
          Flow[Message]
            .map {
              case TextMessage(text) => parser.parse(text).flatMap(_.as[ClientMessage])
              case _                 => ???
            }
            .flatMapConcat {
              case Left(e)      => Source.failed(e)
              case Right(value) => Source.single(value)
            }
        )

        val partitionClientMessages = builder.add(
          Partition[ClientMessage](1, {
            case _: ClientMusicMessage => 0
          })
        )
        val musicClientMessage = builder.add(
          Flow[ClientMessage]
            .collectType[ClientMusicMessage]
            .map {
              case MusicTogglePaused(guildId) =>
                GuildRouter.SendToGuildActor(
                  guildId,
                  GuildMusicHandler.GuildMusicCommandWrapper(GuildMusicHandler.MusicCommand.Pause, ???)
                )
              case MusicSetVolume(guildId, volume) =>
                GuildRouter.SendToGuildActor(
                  guildId,
                  GuildMusicHandler.GuildMusicCommandWrapper(GuildMusicHandler.MusicCommand.Volume(volume), ???)
                )
              case MusicSetDefaultVolume(guildId, volume) =>
                GuildRouter.SendToGuildActor(
                  guildId,
                  GuildMusicHandler.SetDefaultVolume(volume, None, None)
                )
              case MusicSeeked(guildId, position) =>
                GuildRouter.SendToGuildActor(
                  guildId,
                  GuildMusicHandler
                    .GuildMusicCommandWrapper(GuildMusicHandler.MusicCommand.Seek(position, useOffset = false), ???)
                )
              case MusicToggleLoop(guildId) =>
                GuildRouter.SendToGuildActor(
                  guildId,
                  GuildMusicHandler.GuildMusicCommandWrapper(GuildMusicHandler.MusicCommand.ToggleLoop, ???)
                )
            }
            .to(Sink.foreach(???))
        )

        val sentMessages = builder.add(Merge[Message](1))

        allClientMessages ~> partitionClientMessages ~> musicClientMessage
        serverEvents ~> sentMessages

        FlowShape(allClientMessages.in, sentMessages.out)
      }

      Flow.fromGraph(flowGraph)
    }.value
  }
}
