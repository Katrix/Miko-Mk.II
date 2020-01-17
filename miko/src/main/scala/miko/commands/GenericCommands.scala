package miko.commands

import java.nio.file.{Files, Paths}
import java.text.NumberFormat

import ackcord._
import ackcord.data._
import ackcord.commands._
import ackcord.syntax._
import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.stream.scaladsl.{Flow, Sink}
import cats.effect.{Bracket, IO, Resource}
import cats.syntax.all._
import doobie.util.transactor.Transactor
import miko.MikoConfig
import miko.db.DBMemoizedAccess
import miko.settings.GuildSettings
import miko.util.{Crypto, PGPKeys}
import miko.voicetext.VoiceTextStreams
import pprint.{PPrinter, Tree, Util}
import scalacache.{Cache, Mode}

import scala.util.Try

class GenericCommands[G[_]: Transactor: Mode: Streamable](vtStreams: VoiceTextStreams[G])(
    implicit config: MikoConfig,
    requests: Requests,
    guildSettingsCache: Cache[GuildSettings],
    F: Bracket[G, Throwable]
) extends MikoCommandController(requests) {

  //Meta command stuff

  //TODO: Help command

  def kill(shutdown: CoordinatedShutdown): Command[NotUsed] = BotOwnerCommand.withSideEffects { _ =>
    shutdown
      .run(CoordinatedShutdown.JvmExitReason)
      .onComplete { _ =>
        sys.exit()
      }(scala.concurrent.ExecutionContext.global)
  }

  val cleanup: Command[NotUsed] = GuildCommand.streamed {
    Flow[GuildCommandMessage[NotUsed]]
      .map(m => m.guild -> m.cache)
      .via(vtStreams.cleanupGuild)
      .to(requests.sinkIgnore[Any])
  }

  val shiftChannels: Command[NotUsed] = GuildCommand.streamed {
    Flow[GuildCommandMessage[NotUsed]]
      .map(m => m.guild -> m.cache)
      .via(vtStreams.shiftChannelsGuild)
      .to(requests.sinkIgnore[Any])
  }

  val genKeys: Command[(String, Boolean)] = GuildCommand
    .andThen(CommandBuilder.needPermission(Permission.Administrator))
    .parsing(
      (
        MessageParser[String],
        MessageParser.optional(MessageParser.literal("--force-new")).map(_.isDefined)
      ).tupled
    )
    .streamed {
      Flow[GuildCommandMessage[(String, Boolean)]]
        .flatMapConcat { implicit m =>
          //TODO: Custom error for parsers with name and stuff
          val (password, forceNewKey) = m.parsed
          Streamable[G].toSource(
            DBMemoizedAccess.getGuildSettings(m.guild.id).map((_, password, m.tChannel, m.guild, forceNewKey))
          )
        }
        .flatMapConcat {
          case (guildSettings, password, tChannel, guild, forceNewKey) =>
            val hasExistingKey = guildSettings.publicKey.nonEmpty

            val allowsAreAdmins = tChannel.permissionOverwrites.values.forall {
              case PermissionOverwrite(id, tpe, allow, _) =>
                def hasAdminFor[K, V](
                    subjects: SnowflakeMap[K, V],
                    construct: Long => SnowflakeType[K]
                )(perms: V => Permission) =
                  subjects.get(construct(id)).forall(perms(_).hasPermissions(Permission.Administrator))

                val (hasAdmin, isOwner) = tpe match {
                  case PermissionOverwriteType.Member =>
                    (hasAdminFor(guild.members, UserId.apply)(_.permissions(guild)), guild.ownerId == id)

                  case PermissionOverwriteType.Role =>
                    (hasAdminFor(guild.roles, RoleId.apply)(_.permissions), false)
                }

                !allow.hasPermissions(Permission.ViewChannel) || (isOwner || hasAdmin)
            }

            val everyoneIsDeny = tChannel.permissionOverwrites
              .get(guild.everyoneRole.id)
              .exists(_.deny.hasPermissions(Permission.ViewChannel))

            val canGenKey = !hasExistingKey || forceNewKey

            if (canGenKey && allowsAreAdmins && everyoneIsDeny) {
              val PGPKeys(pub, priv) = Crypto.generateKeys(guild.name, password)

              val req = if (priv.length > 2000) {
                tChannel.sendMessage(
                  embed = Some(
                    OutgoingEmbed(
                      fields = priv
                        .grouped(1000)
                        .zipWithIndex
                        .map(_.swap)
                        .map(t => EmbedField(t._1.toString, t._2))
                        .toSeq
                    )
                  )
                )
              } else {
                tChannel.sendMessage(priv)
              }

              //TODO: Enable retry again when it's fixed

              requests
                .singleSuccess(req) //(RequestHelper.RequestProperties(retry = true))
                .flatMapConcat { response =>
                  val msgId = response.id
                  Streamable[G].toSource(DBMemoizedAccess.updateKey(guild.id, pub, tChannel.id, msgId))
                }
                .concat {
                  requests.single(
                    tChannel.sendMessage(
                      """|Keys have been generated. You can now enable saveDestructable.
                         |You can now delete the message you sent which contains the password.
                         |If you want, you can also delete the key itself, and store it somewhere more secure.
                         |You'll then have to supply it yourself when getting logs.""".stripMargin.replace("\n", " ")
                    )
                  ) //(RequestHelper.RequestProperties(retry = true))
                }
            } else if (!canGenKey) {
              requests.single(tChannel.sendMessage("You already have a key. Use `--force-new` to generate a new key."))
            } else {
              requests.single(
                tChannel.sendMessage(
                  "This channel is not private. Only admins should be able to read the messages in this channel"
                )
              )
            }
        }
        .to(Sink.ignore)
    }

  val info: Command[NotUsed] = Command.withRequest { m =>
    val mb          = 1024d * 1024d
    val format      = NumberFormat.getInstance()
    val totalMemory = sys.runtime.totalMemory()
    val freeMemory  = sys.runtime.freeMemory()
    val maxMemory   = sys.runtime.maxMemory()
    val usedMemory  = totalMemory - freeMemory

    def infoField(title: String, content: String) = EmbedField(title, content, Some(true))

    val embed = OutgoingEmbed(
      title = Some("Miko Mk.II Info"),
      fields = Seq(
        infoField("Author", "Katrix#9696"),
        infoField("Framework", "AckCord"),
        infoField("Total Memory", s"${format.format(totalMemory / mb)} MB"),
        infoField("Free Memory", s"${format.format(freeMemory / mb)} MB"),
        infoField("Max Memory", s"${format.format(maxMemory / mb)} MB"),
        infoField("Used Memory", s"${format.format(usedMemory / mb)} MB"),
        infoField("Owner(s)", config.botOwners.mkString("\n")),
        infoField("Help command", s"`@${m.cache.botUser.username} !help`")
      )
    )

    m.tChannel.sendMessage(embed = Some(embed))
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

  lazy val pprint2: PPrinter = pprint.copy(additionalHandlers = pprintAdditionalHandlers)

  val debug: Command[(String, Option[String])] =
    BotOwnerGuildCommand
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
          case "user"        => singleObject("User", UserId.apply)(_.resolve)
          case "member"      => singleObject("Member", UserId.apply)(_.resolveMember(m.guild.id))
          case "role"        => singleObject("Role", RoleId.apply)(_.resolve)
          case "channel"     => singleObject("Channel", ChannelId.apply)(_.resolve)
          case "guild"       => Right(pprint2(m.guild))
          case "voice_state" => singleObject("VoiceState", UserId.apply)(m.guild.voiceStateFor)
          case _             => Left("Unknown debug object")
        }

        res match {
          case Right(str) =>
            val message = str.plainText

            if (message.length > 2000) {
              //TODO: Allow sending raw bytes in files in AckCord
              Resource
                .make(IO(Files.createTempFile(Paths.get("tempFiles"), "message", ".txt")))(f => IO(Files.delete(f)))
                .use(file => IO(requests.singleIgnore(m.tChannel.sendMessage(files = Seq(file)))))
                .unsafeRunSync()

            } else {
              requests.singleIgnore(m.tChannel.sendMessage(message))
            }
          case Left(err) => requests.singleIgnore(m.tChannel.sendMessage(s"Failed to get debug info: $err"))
        }
      }
}
