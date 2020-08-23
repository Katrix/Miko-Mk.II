package miko.commands

import java.text.NumberFormat

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
import miko.settings.GuildSettings.Commands.Permissions.CommandPermission
import miko.settings.{GuildSettings, NamedPermission}
import miko.util.{Crypto, PGPKeys}
import miko.voicetext.VoiceTextStreams
import pprint.{PPrinter, Tree, Util}
import zio.blocking.Blocking
import zio.{RIO, ZIO}

import scala.util.Try

class GenericCommands(vtStreams: VoiceTextStreams)(
    implicit components: MikoCommandComponents,
    blockingStreamable: Streamable[RIO[Blocking, *]]
) extends MikoCommandController(components) {

  //Meta command stuff

  //TODO: Help command

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

  val cleanup: NamedDescribedCommand[NotUsed] =
    GuildCommand
      .namedParser(named(Seq("cleanup"), CommandCategory.General, _.general.cleanup))
      .described("Cleanup", "Fixes missing channels and permissions", extra = CommandCategory.General.extra)
      .toSink {
        Flow[GuildCommandMessage[NotUsed]]
          .map(m => m.guild -> m.cache)
          .via(vtStreams.cleanupGuild)
          .to(requests.sinkIgnore[Any])
      }

  val shiftChannels: NamedDescribedCommand[NotUsed] =
    GuildCommand
      .namedParser(named(Seq("shiftChannels"), CommandCategory.General, _.general.shiftChannels))
      .described(
        "Shift channels",
        "Manually triggers the process to create new voice channels when existing ones are in use",
        extra = CommandCategory.General.extra
      )
      .toSink {
        Flow[GuildCommandMessage[NotUsed]]
          .map(m => m.guild -> m.cache)
          .via(vtStreams.shiftChannelsGuildFlow)
          .to(requests.sinkIgnore[Any])
      }

  val genKeys: NamedDescribedCommand[(String, Boolean)] = GuildCommand
    .andThen(CommandBuilder.needPermission(Permission.Administrator))
    .namedParser(
      namedCustomPerm(
        Seq("genKeys"),
        CommandCategory.General,
        CommandPermission.HasPermission(Seq(NamedPermission.Administrator))
      )
    )
    .described(
      "Gen keys",
      "Generates new public and private keys to use for encrypting info about this guild",
      extra = CommandCategory.General.extra
    )
    .parsing(
      (
        MessageParser[String],
        MessageParser.optional(MessageParser.literal("--force-new")).map(_.isDefined)
      ).tupled
    )
    .streamed[RIO[Blocking, *]] { implicit m =>
      val (password, forceNewKey) = m.parsed

      settings.getGuildSettings(m.guild.id).flatMap { guildSettings =>
        val hasExistingKey = guildSettings.guildEncryption.publicKey.nonEmpty

        val allowsAreAdmins = m.textChannel.permissionOverwrites.values.forall {
          case PermissionOverwrite(id, tpe, allow, _) =>
            def hasAdminFor[K, V](
                subjects: SnowflakeMap[K, V],
                construct: UserOrRoleId => SnowflakeType[K]
            )(perms: V => Permission) =
              subjects.get(construct(id)).forall(perms(_).hasPermissions(Permission.Administrator))

            val (hasAdmin, isOwner) = tpe match {
              case PermissionOverwriteType.Member =>
                (hasAdminFor(m.guild.members, UserId(_))(_.permissions(m.guild)), m.guild.ownerId == id)

              case PermissionOverwriteType.Role =>
                (hasAdminFor(m.guild.roles, RoleId(_))(_.permissions), false)
              case _ => (false, false)
            }

            !allow.hasPermissions(Permission.ViewChannel) || (isOwner || hasAdmin)
        }

        val everyoneIsDeny =
          m.textChannel.permissionOverwrites
            .get(m.guild.everyoneRole.id)
            .exists(_.deny.hasPermissions(Permission.ViewChannel))

        val canGenKey = !hasExistingKey || forceNewKey

        if (canGenKey && allowsAreAdmins && everyoneIsDeny) {
          val PGPKeys(pub, priv) = Crypto.generateKeys(m.guild.name, password)

          val req = if (priv.length > 2000) {
            m.textChannel.sendMessage(
              embed = Some(
                OutgoingEmbed(
                  fields = priv
                    .grouped(1000)
                    .zipWithIndex
                    .map(_.swap)
                    .map(t => EmbedField(t._1.toString, t._2, None))
                    .toSeq
                )
              )
            )
          } else {
            m.textChannel.sendMessage(priv)
          }

          ZIO
            .fromFuture(_ => requests.singleFutureSuccess(req)(Requests.RequestProperties(retry = true)))
            .flatMap { response =>
              val msgId = response.id
              settings
                .updateGuildSettings(
                  m.guild.id,
                  _.copy(
                    guildEncryption =
                      GuildSettings.GuildEncryption(publicKey = Some(pub), Some(m.textChannel.id), Some(msgId))
                  )
                )
            }
            .zip(
              ZIO.fromFuture { _ =>
                requests.singleFutureSuccess(
                  m.textChannel.sendMessage(
                    """|Keys have been generated. You can now enable saveDestructable.
                       |You can now delete the message you sent which contains the password.
                       |If you want, you can also delete the key itself, and store it somewhere more secure.
                       |You'll then have to supply it yourself when getting logs.""".stripMargin.replace("\n", " ")
                  )
                )(Requests.RequestProperties(retry = true))
              }
            )
            .unit
        } else if (!canGenKey) {
          ZIO.fromFuture { _ =>
            requests.singleFuture(
              m.textChannel.sendMessage("You already have a key. Use `--force-new` to generate a new key.")
            )
          }.unit
        } else {
          ZIO.fromFuture { _ =>
            requests.singleFuture(
              m.textChannel.sendMessage(
                "This channel is not private. Only admins should be able to read the messages in this channel"
              )
            )
          }.unit
        }
      }
    }

  val info: NamedDescribedCommand[NotUsed] = Command
    .namedParser(named(Seq("info"), CommandCategory.General, _.general.info))
    .described("Info", "Get basic info about the bot", extra = CommandCategory.General.extra)
    .withRequest { m =>
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

      m.textChannel.sendMessage(embed = Some(embed))
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
}
