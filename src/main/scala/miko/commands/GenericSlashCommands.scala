package miko.commands

import ackcord._
import ackcord.data._
import ackcord.syntax._
import ackcord.slashcommands._
import akka.NotUsed
import akka.stream.scaladsl.{Keep, Source}
import miko.settings.GuildSettings
import miko.util.{Crypto, PGPKeys}
import miko.voicetext.VoiceTextStreams
import zio.{RIO, ZIO}
import zio.blocking.Blocking

import java.text.NumberFormat

class GenericSlashCommands(vtStreams: VoiceTextStreams)(
    implicit components: MikoCommandComponents,
    blockingStreamable: Streamable[RIO[Blocking, *]]
) extends MikoSlashCommandController(components) {

  val cleanup: Command[GuildCommandInteraction, NotUsed] =
    GuildCommand
      .andThen(canExecute(CommandCategory.General, _.general.cleanup))
      .withExtra(CommandCategory.General.slashExtra)
      .command("cleanup", "Fixes missing channels and permissions") { implicit m =>
        sendMessage("Starting cleanup").doAsync { implicit t =>
          for {
            _ <- OptFuture.fromFuture(
              vtStreams.cleanupGuild.runWith(Source.single((m.guild, m.cache)), requests.sinkIgnore[Any])._2
            )
            _ <- editOriginalMessage(content = JsonSome("Cleanup done"))
          } yield ()
        }
      }

  val shiftChannels: Command[GuildCommandInteraction, NotUsed] =
    GuildCommand
      .andThen(canExecute(CommandCategory.General, _.general.shiftChannels))
      .withExtra(CommandCategory.General.slashExtra)
      .command(
        "shiftChannels",
        "Manually triggers the process to create new voice channels when existing ones are in use"
      ) { implicit m =>
        sendMessage("Starting channel shift").doAsync { implicit t =>
          for {
            _ <- OptFuture.fromFuture(
              vtStreams.shiftChannelsGuildFlow.runWith(Source.single((m.guild, m.cache)), requests.sinkIgnore[Any])._2
            )
            _ <- editOriginalMessage(content = JsonSome("Channel shift done"))
          } yield ()
        }
      }

  val genKeys: Command[GuildCommandInteraction, (String, Option[Boolean])] =
    GuildCommand
      .andThen(CommandTransformer.needPermission(Permission.Administrator))
      .named("genKeys", "Generates new public and private keys to use for encrypting info about this guild")
      .withParams(
        string("password", "Password for accessing the logs") ~ bool(
          "force-new",
          "Force a new key to be created even if one already exists"
        ).notRequired
      )
      .handle { implicit m =>
        val password    = m.args._1
        val forceNewKey = m.args._2.exists(identity)

        sendMessage("Processing...").doAsync { implicit t =>
          val process = settings.getGuildSettings(m.guild.id).flatMap { guildSettings =>
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
                    //noinspection ComparingUnrelatedTypes
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

              ZIO
                .fromFuture { _ =>
                  if (priv.length > 2000) {
                    sendAsyncEmbed(
                      embeds = Seq(
                        OutgoingEmbed(
                          fields = priv
                            .grouped(1000)
                            .zipWithIndex
                            .map(_.swap)
                            .map(t => EmbedField(t._1.toString, t._2, None))
                            .toSeq
                        )
                      )
                    ).value
                  } else {
                    sendAsyncMessage(priv).value
                  }
                }
                .flatMap { response =>
                  val msgId = response.get.id
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
                    sendAsyncMessage(
                      """|Keys have been generated. You can now enable saveDestructable.
                         |You can now delete the message you sent which contains the password.
                         |If you want, you can also delete the key itself, and store it somewhere more secure.
                         |You'll then have to supply it yourself when getting logs.""".stripMargin.replace("\n", " ")
                    ).value
                  }
                )
                .unit
            } else if (!canGenKey) {
              ZIO.fromFuture { _ =>
                sendAsyncMessage("You already have a key. Use `--force-new` to generate a new key.").value
              }.unit
            } else {
              ZIO.fromFuture { _ =>
                sendAsyncMessage(
                  "This channel is not private. Only admins should be able to read the messages in this channel"
                ).value
              }.unit
            }
          }

          OptFuture.fromFuture(zioRuntime.unsafeRunToFuture(process))
        }
      }

  val info: Command[ResolvedCommandInteraction, NotUsed] =
    Command
      .andThen(canExecute(CommandCategory.General, _.general.info))
      .withExtra(CommandCategory.General.slashExtra)
      .command("info", "Get basic info about the bot") { m =>
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

        sendEmbed(embeds = Seq(embed))
      }
}
