package miko.commands

import java.text.NumberFormat
import java.util.regex.Pattern

import ackcord._
import ackcord.commands._
import ackcord.data._
import ackcord.requests.{CreateMessage, CreateMessageData, CreateMessageFile}
import ackcord.syntax._
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.model.ContentTypes
import akka.stream.scaladsl.Flow
import cats.syntax.all._
import fansi.Attrs
import miko.settings.GuildSettings.Commands.Permissions.CommandPermission
import miko.settings.{GuildSettings, NamedPermission}
import miko.util.{Crypto, PGPKeys}
import miko.voicetext.VoiceTextStreams
import play.api.ApplicationLoader.DevContext
import pprint.{PPrinter, Tree, Util}
import zio.blocking.Blocking
import zio.{RIO, ZIO}

import scala.util.Try
import scala.util.control.NonFatal

class GenericCommands(vtStreams: VoiceTextStreams, devContext: Option[DevContext])(
    implicit components: MikoCommandComponents,
    blockingStreamable: Streamable[RIO[Blocking, *]]
) extends MikoCommandController(components) {

  def kill(shutdown: CoordinatedShutdown): NamedDescribedCommand[NotUsed] =
    BotOwnerCommand
      .namedParser(
        namedCustomPerm(
          Seq("kill", "die"),
          CommandCategory.General,
          CommandPermission.Or(config.botOwners.map(CommandPermission.IsUser))
        )
      )
      .described("Kill", "Kills the bot", extra = CommandCategory.General.extra)
      .withSideEffects { _ =>
        shutdown
          .run(CoordinatedShutdown.JvmExitReason)
          .onComplete { _ =>
            sys.exit()
          }(scala.concurrent.ExecutionContext.global)
      }

  private def pprintAdditionalHandlers: PartialFunction[Any, Tree] = {
    case x: Product =>
      val className = x.getClass.getName
      // see source code for pprint.treeify()
      val shouldNotPrettifyCaseClass = x.productArity == 0 || (x.productArity == 2 && Util.isOperator(x.productPrefix)) || className
        .startsWith(pprint.tuplePrefix) || className == "scala.Some"

      if (shouldNotPrettifyCaseClass)
        pprint.treeify(x)
      else {
        pprint.Tree.Apply(
          x.productPrefix,
          x.productElementNames.zip(x.productIterator).flatMap {
            case (k, v) =>
              val prettyValue: Tree = pprintAdditionalHandlers.lift(v).getOrElse(pprint2.treeify(v))
              Seq(pprint.Tree.Infix(Tree.Literal(k), "=", prettyValue))
          }
        )
      }
  }

  lazy val pprint2: PPrinter = pprint.copy(
    colorLiteral = Attrs.Empty,
    colorApplyPrefix = Attrs.Empty,
    additionalHandlers = pprintAdditionalHandlers
  )

  val debug: NamedDescribedCommand[(String, Option[String])] =
    BotOwnerGuildCommand
      .namedParser(
        namedCustomPerm(
          Seq("debug"),
          CommandCategory.General,
          CommandPermission.Or(config.botOwners.map(CommandPermission.IsUser))
        )
      )
      .described("Debug", "Gets the underlying discord object in the cache", extra = CommandCategory.General.extra)
      .parsing(MessageParser.stringParser.product(MessageParser.optional(MessageParser.stringParser)))
      .withSideEffects { implicit m =>
        val (tpe, identifier) = m.parsed

        def singleObject[Id, Obj](name: String, createId: RawSnowflake => Id)(
            resolve: Id => Option[Obj]
        ): Either[String, fansi.Str] = {
          val snowflakeIdentifier = identifier
            .toRight("No id specified")
            .flatMap(id => Try(RawSnowflake(id)).toEither.leftMap(_ => "Invalid id"))
          snowflakeIdentifier.map(createId).flatMap(resolve(_).toRight(s"$name not found")).map(obj => pprint2(obj))
        }

        val res = tpe match {
          case "user"    => singleObject("User", UserId.apply)(_.resolve)
          case "member"  => singleObject("Member", UserId.apply)(_.resolveMember(m.guild.id))
          case "role"    => singleObject("Role", RoleId.apply)(_.resolve)
          case "channel" => singleObject("Channel", ChannelId.apply)(_.resolve)
          case "guild" =>
            if (identifier.isDefined) singleObject("Guild", GuildId.apply)(_.resolve) else Right(pprint2(m.guild))
          case "voice_state" => singleObject("VoiceState", UserId.apply)(m.guild.voiceStateFor)
          case _             => Left("Unknown debug object")
        }

        res match {
          case Right(str) =>
            val message = str.plainText

            if (message.length > 2000) {
              requests.singleIgnore(
                CreateMessage(
                  m.textChannel.id,
                  CreateMessageData(
                    files = Seq(CreateMessageFile.StringFile(ContentTypes.`text/plain(UTF-8)`, message, "message.txt"))
                  )
                )
              )
            } else {
              requests.singleIgnore(m.textChannel.sendMessage(message))
            }
          case Left(err) => requests.singleIgnore(m.textChannel.sendMessage(s"Failed to get debug info: $err"))
        }
      }

  import scala.tools.reflect.ToolBox

  private lazy val mirror  = scala.reflect.runtime.universe.runtimeMirror(this.getClass.getClassLoader)
  private lazy val toolbox = mirror.mkToolBox()

  val eval: NamedDescribedCommand[MessageParser.RemainingAsString] =
    BotOwnerCommand
      .namedParser(
        namedCustomPerm(
          Seq("eval"),
          CommandCategory.General,
          CommandPermission.Or(config.botOwners.map(CommandPermission.IsUser))
        )
      )
      .described("Eval", "Evaluates and prints some Scala expression", "<code...>", CommandCategory.General.extra)
      .parsing[MessageParser.RemainingAsString]
      .withRequest { m =>
        val surroundingCode =
          s"""|import _root_.ackcord._
              |import _root_.ackcord.data._
              |import _root_.ackcord.syntax._
              |(m: _root_.ackcord.commands.UserCommandMessage[_root_.ackcord.commands.MessageParser.RemainingAsString]) => {
              |  ${m.parsed.remaining}
              |}
              |""".stripMargin

        val str = try {
          pprint2(
            toolbox
              .eval(toolbox.parse(surroundingCode))
              .asInstanceOf[UserCommandMessage[MessageParser.RemainingAsString] => Any](m)
          ).plainText
        } catch {
          case NonFatal(e) => e.getMessage
        }

        if (str.length > 2000) {
          CreateMessage(
            m.textChannel.id,
            CreateMessageData(
              files = Seq(CreateMessageFile.StringFile(ContentTypes.`text/plain(UTF-8)`, str, "message.txt"))
            )
          )
        } else {
          m.textChannel.sendMessage(s"```$str```")
        }
      }

  private val codeRegex = Pattern.compile("""```scala\R(.+)\R```$""", Pattern.DOTALL)

  def execute(
      commandConnector: CommandConnector,
      helpCommand: MikoHelpCommand
  ): NamedDescribedCommand[MessageParser.RemainingAsString] =
    BotOwnerCommand
      .namedParser(
        namedCustomPerm(
          Seq("execute"),
          CommandCategory.General,
          CommandPermission.Or(config.botOwners.map(CommandPermission.IsUser))
        )
      )
      .described(
        "Execute",
        "Executes Scala expression",
        "<args...>\\n ```scala\\n<code...>\\n```",
        CommandCategory.General.extra
      )
      .parsing[MessageParser.RemainingAsString]
      .withSideEffects { m =>
        val matcher = codeRegex.matcher(m.message.content.replaceFirst("m!execute", "").trim)

        Option
          .when(matcher.find())(matcher.toMatchResult)
          .fold(requests.singleIgnore(m.textChannel.sendMessage("Syntax error"))) { matchObj =>
            val args = m.parsed.remaining.substring(0, matchObj.start)
            val code = matchObj.group(1)

            val surroundingCode =
              s"""|import _root_.ackcord._
                  |import _root_.ackcord.data._
                  |import _root_.ackcord.syntax._
                  |import _root_.ackcord.commands._
                  |
                  |(
                  |  m: UserCommandMessage[String],
                  |  requests: Requests,
                  |  commandConnector: CommandConnector,
                  |  helpCommand: miko.commands.MikoHelpCommand,
                  |  commandComponents: miko.commands.MikoCommandComponents,
                  |) =>
                  |  {
                  |    $code
                  |  }
                  |""".stripMargin

            val res = try {
              toolbox
                .eval(toolbox.parse(surroundingCode))
                .asInstanceOf[
                  (
                      UserCommandMessage[String],
                      Requests,
                      CommandConnector,
                      MikoHelpCommand,
                      MikoCommandComponents
                  ) => Any
                ](
                  UserCommandMessage.Default(
                    m.user,
                    CommandMessage.Default(requests, m.cache, m.textChannel, m.message, parsed = args)
                  ),
                  requests,
                  commandConnector,
                  helpCommand,
                  components
                )
                .toString
            } catch {
              case NonFatal(e) =>
                e.getMessage
            }

            requests.singleIgnore(m.textChannel.sendMessage(s"```$res```"))
          }
      }

  val reload: NamedDescribedCommand[NotUsed] = BotOwnerCommand
    .namedParser(
      namedCustomPerm(
        Seq("reload"),
        CommandCategory.General,
        CommandPermission.Or(config.botOwners.map(CommandPermission.IsUser))
      )
    )
    .described(
      "Reload",
      "Reloads the app if anything has changed in a dev environment",
      extra = CommandCategory.General.extra
    )
    .withSideEffects { _ =>
      println(devContext.map(_.buildLink.reload()))
      devContext.foreach(_.buildLink.forceReload())
    }
}
