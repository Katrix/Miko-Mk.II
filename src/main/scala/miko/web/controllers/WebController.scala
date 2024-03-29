package miko.web.controllers

import ackcord.OptFuture
import ackcord.data._
import ackcord.data.raw.RawMessage
import ackcord.requests.{GetCurrentUser, OAuth, RequestResponse}
import ackcord.util.GuildRouter
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.scaladsl.{Flow, GraphDSL, Sink, Source}
import akka.stream.{FlowShape, SourceShape}
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import controllers.AssetsFinder
import io.circe.syntax._
import io.circe.{Json, parser}
import miko.MikoConfig
import miko.commands.MikoHelpCommand
import miko.db.DBAccess
import miko.music.GuildMusicHandler
import miko.services.ClientMessage
import miko.settings.{PublicGuildSettings, SettingsAccess}
import miko.web.WebEvents
import miko.web.controllers.MikoBaseController.HasMaybeAuthRequest
import miko.web.models.GuildViewInfo
import play.api.http.websocket.TextMessage
import play.api.mvc._
import play.api.{Environment, Mode}
import play.filters.csrf.CSRF
import play.twirl.api.{Html, StringInterpolation}
import views.html.helper.CSPNonce

import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import scala.annotation.unused
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.Try

class WebController(
    assetsFinder: AssetsFinder,
    helpCommand: MikoHelpCommand,
    environment: Environment
)(
    implicit components: MikoControllerComponents,
    webEvents: WebEvents,
    mikoConfig: MikoConfig,
    settings: SettingsAccess,
    db: DBAccess[IO],
    IORuntime: IORuntime
) extends AbstractMikoController(components) { outer =>

  private val self = routes.WebController

  private def indexHtml(implicit request: RequestHeader with HasMaybeAuthRequest): Html = {
    val tokenPart =
      CSRF.getToken.fold(Html(""))(token => html"<script ${CSPNonce.attr} >var csrf = '${token.value}'</script>")
    val authPart =
      request.maybeInfo.fold(Html(""))(_ => html"<script ${CSPNonce.attr}>var isAuthenticated = true</script>")
    val clientIdPart =
      html"""<script ${CSPNonce.attr}>
               window.EXTERNAL_CONFIG = {
                 clientId: '${mikoConfig.clientId}',
                 backendUrl: '${self.index.absoluteURL()}',
                 wsMusicBackendUrl: '${"TODO" /*self.musicWebsocket.absoluteURL()*/}',
                 production: ${environment.mode == Mode.Prod}
               }
             </script>"""

    html"""
          <!doctype html>
          <html lang="en">
          <head>
          	<meta charset="utf-8">
          	<meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no">
          
          	<meta property="og:title">
          	<meta property="og:type" content="website">
          	<meta property="og:description">
          	<meta property="og:image">
          	<meta property="og:url">
          
          	<link rel="canonical">

            <link rel="stylesheet" type="text/css" href="${assetsFinder.path("build/vendors.css")}" />
            <link rel="stylesheet" type="text/css" href="${assetsFinder.path("build/app.css")}" />
          
          	<title>Miko Mk.II</title>
            $tokenPart
            $authPart
            $clientIdPart
          </head>
          <body>
          <div id="app"></div>

            <script src="${assetsFinder.path("build/vendors.js")}"></script>
            <script src="${assetsFinder.path("build/app.js")}"></script>
          </body>
          </html>"""
  }

  def index: Action[AnyContent] = MaybeAuthenticatedAction { implicit request =>
    Ok(indexHtml)
  }

  //noinspection ScalaUnusedSymbol
  def guildIndex(@unused guild: String): Action[AnyContent] = MaybeAuthenticatedAction { implicit request =>
    Ok(indexHtml)
  }

  def logout: Action[AnyContent] = MaybeAuthenticatedAction {
    Redirect(self.index).withNewSession
  }

  def codeGrant: Action[AnyContent] = Action { implicit request =>
    val state = UUID.randomUUID()
    val uri = OAuth.codeGrantUri(
      mikoConfig.clientId,
      Seq(OAuth.Scope.Identify),
      state.toString,
      self.authenticateOAuth(None, None).absoluteURL(),
      OAuth.PromptType.Consent
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
      } yield for {
        token <- OAuth.tokenExchange(
          mikoConfig.clientId,
          mikoConfig.clientSecret,
          OAuth.GrantType.AuthorizationCode,
          code,
          self.authenticateOAuth(None, None).absoluteURL(),
          Seq(OAuth.Scope.Identify)
        )
        response <- requests
          .copy(settings = requests.settings.copy(credentials = Some(OAuth2BearerToken(token.accessToken))))
          .singleFuture(GetCurrentUser)
      } yield response match {
        case response: RequestResponse[User] =>
          Redirect(self.index).withSession(
            "userId"       -> response.data.id.asString,
            "accessToken"  -> token.accessToken,
            "expiresIn"    -> token.expiresIn.toString,
            "refreshToken" -> token.refreshToken
          )
        case _ =>
          BadGateway("Something went wrong. If this continues to happen, message me and I'll look at it")
      }

      res.getOrElse(Future.successful(BadRequest))
  }

  def getAvailibleGuils: Action[AnyContent] = AuthenticatedAction { request =>
    println(request.info.cache.guildMap.map(_._2.members))
    println(request.info.cache.guildMap.map(_._2.members.contains(request.info.userId)))
    println(request.info.userId)
    Ok(Json.obj("guilds" := request.info.cache.guildMap.collect {
      case (id, guild) if guild.members.contains(request.info.userId) =>
        Json.obj(
          "id" := id,
          "name" := guild.name,
          "icon" := guild.icon
        )
    }))
  }

  def getGuildData(guildIdStr: String): Action[AnyContent] = GuildAction(guildIdStr) { request =>
    val info = request.info
    def encodeGuildChannel(channel: GuildChannel) =
      Json.obj("id" := channel.id, "name" := channel.name, "position" := channel.position)

    def encodeChannelType[A <: GuildChannel: ClassTag] = info.guild.channels.values.collect {
      case channel: A => encodeGuildChannel(channel)
    }

    Ok(
      Json.obj(
        "inVoiceChat" := info.userInVChannel,
        "isAdmin" := info.isAdmin,
        "textChannels" := encodeChannelType[TextGuildChannel],
        "voiceChannels" := encodeChannelType[VoiceGuildChannel],
        "categories" := encodeChannelType[GuildCategory],
        "roles" := info.guild.roles.map {
          case (id, role) => Json.obj("id" := id, "name" := role.name, "position" := role.position)
        }
      )
    )
  }

  def getSettings(guildId: String): Action[AnyContent] = AdminGuildAction(guildId).async { request =>
    settings.getGuildSettings(request.info.guildId).map(s => Ok(s.asPublic.asJson)).unsafeToFuture()
  }

  def updateSettings(guildId: String): Action[PublicGuildSettings] =
    AdminGuildAction(guildId).async(parseCirce.decodeJson[PublicGuildSettings]) { request =>
      settings.updateGuildSettings(GuildId(guildId), request.body.toAll(_)).as(NoContent).unsafeToFuture()
    }

  private def createFakeMessage(info: GuildViewInfo) = {
    val firstChannelId = info.guild.channels.toSeq.sortBy(_._2.id.creationDate).collectFirst {
      case (_, channel: TextGuildChannel)
          if info.member
            .channelPermissionsId(info.guild, channel.id)
            .hasPermissions(Permission.SendMessages ++ Permission.ViewChannel) =>
        channel.id
    }

    firstChannelId.map { channelId =>
      GuildGatewayMessage(
        id = SnowflakeType.fromInstant[Message](Instant.now()),
        channelId = channelId,
        guildId = info.guildId,
        authorId = RawSnowflake(info.member.userId),
        isAuthorUser = true,
        authorUsername = info.user.username,
        member = Some(info.member),
        content = "Dummy",
        timestamp = OffsetDateTime.now(),
        editedTimestamp = None,
        tts = false,
        mentionEveryone = false,
        mentions = Nil,
        mentionRoles = Nil,
        mentionChannels = Nil,
        attachments = Nil,
        embeds = Nil,
        reactions = Nil,
        nonce = None,
        pinned = false,
        messageType = MessageType.Default,
        activity = None,
        application = None,
        messageReference = None,
        flags = None,
        stickers = None,
        referencedMessage = None,
        applicationId = None,
        stickerItems = None,
        interaction = None,
        components = Nil,
        threadId = None
      )
    }
  }

  def getCommandData(guildIdStr: String): Action[AnyContent] = GuildAction(guildIdStr).async { request =>
    val info    = request.info
    val message = createFakeMessage(info)

    val commandData = message.fold[Future[Map[Option[String], Seq[Json]]]](Future.successful(Map.empty)) { message =>
      Future
        .traverse(helpCommand.getActiveCommands) { entry =>
          entry.prefixParser
            .aliases(request.info.cache, message)
            .zip(entry.prefixParser.canExecute(request.info.cache, message))
            .map {
              case (aliases, canExecute) =>
                (
                  canExecute,
                  Json.obj(
                    "name" := entry.description.name,
                    "aliases" := aliases,
                    "usage" := entry.description.usage,
                    "description" := entry.description.description
                  ),
                  entry.description.extra.get("category")
                )
            }
        }
        .map(_.collect { case (canExecute, info, category) if canExecute => (info, category) }.groupMap(_._2)(_._1))
    }

    settings.getGuildSettings(info.guildId).unsafeToFuture().zip(commandData).map {
      case (settings, commandData) =>
        Ok(
          Json.obj(
            "categories" := Json.arr(
              Json.obj(
                "name" := "General",
                "prefixes" := settings.commands.prefixes.general,
                "commands" := commandData.get(Some("general"))
              ),
              Json.obj(
                "name" := "Music",
                "prefixes" := settings.commands.prefixes.music,
                "commands" := commandData.get(Some("music"))
              ),
              Json.obj(
                "name" := "Uncategorized",
                "prefixes" := Seq.empty[String],
                "commands" := commandData.get(None)
              )
            )
          )
        )
    }
  }

  def musicWebsocket(guildIdStr: String, voiceChannelIdStr: String): WebSocket = WebSocket { implicit requestHeader =>
    import cats.instances.future._
    val eitherRequest =
      EitherT(maybeAuthedAction.refine(Request(requestHeader, ())): Future[Either[Result, MaybeAuthedRequest[Unit]]])
        .flatMapF(authedAction.refineResult)
        .flatMapF(authedGuildRefiner(guildIdStr).refineResult)
        .subflatMap { guildRequest =>
          Try(NormalVoiceGuildChannelId(voiceChannelIdStr)).toEither.left
            .map(_ => NotFound)
            .filterOrElse(guildRequest.info.userInVChannel.contains, NotFound)
            .map((guildRequest, _))
        }

    eitherRequest.map {
      case (guildRequest, voiceChannelId) =>
        val guildId = guildRequest.info.guild.id

        val flowGraph = GraphDSL.create() { implicit builder =>
          import GraphDSL.Implicits._

          val serverEvents: SourceShape[TextMessage] =
            builder.add(
              webEvents.subscribe
                .filter(_.applicableUsers.contains(guildRequest.info.user.id))
                .filter(_.guildId == guildId)
                .map(wrapper => wrapper.event.includeWebIdIf(wrapper.webIdFor.contains(guildRequest.info.user.id)))
                .map(_.asJson.noSpaces)
                .map(s => TextMessage(s))
            )

          val clientMessages: FlowShape[play.api.http.websocket.Message, ClientMessage] = builder.add(
            Flow[play.api.http.websocket.Message]
              .flatMapConcat {
                case TextMessage(text) => Source.single(parser.parse(text).flatMap(_.as[ClientMessage]))
                case _                 => ???
              }
              .flatMapConcat {
                case Left(e)      => Source.failed(e)
                case Right(value) => Source.single(value)
              }
          )

          var webId = -1

          def musicCmdInfo: GuildMusicHandler.MusicCmdInfo = {
            webId = webId + 1
            GuildMusicHandler.MusicCmdInfo(None, voiceChannelId, None, Some(webId), Some(guildRequest.info.user.id))
          }

          def sendMessage(embeds: Seq[OutgoingEmbed], rows: Seq[ActionRow]): OptFuture[RawMessage] = ???

          val musicClientMessage = builder.add(
            Flow[ClientMessage]
              .map {
                case ClientMessage.SetPosition(position) =>
                  GuildMusicHandler.MusicCommand.Seek(position, useOffset = false)
                case ClientMessage.SetPaused(paused) => GuildMusicHandler.MusicCommand.SetPaused(paused)
                case ClientMessage.UpdateVolume(volume, defVolume) =>
                  GuildMusicHandler.MusicCommand.VolumeBoth(volume, defVolume)
                case ClientMessage.SetTrackPlaying(idx)      => GuildMusicHandler.MusicCommand.SetPlaying(idx)
                case ClientMessage.QueueTrack(url)           => GuildMusicHandler.MusicCommand.Queue(url)
                case ClientMessage.MoveTrack(fromIdx, toIdx) => GuildMusicHandler.MusicCommand.MoveTrack(fromIdx, toIdx)
                case ClientMessage.RemoveTrack(idx)          => GuildMusicHandler.MusicCommand.RemoveTrack(idx)
              }
              .map(GuildMusicHandler.GuildMusicCommandWrapper(_, sendMessage, musicCmdInfo))
              .map(GuildRouter.SendToGuildActor(guildId, _))
              .to(Sink.foreach(???))
          )

          clientMessages ~> musicClientMessage

          FlowShape(clientMessages.in, serverEvents.out)
        }

        Flow.fromGraph(flowGraph)
    }.value
  }
}
