package miko.commands

import ackcord.CacheSnapshot
import ackcord.commands._
import ackcord.data.{GuildChannel, Message}
import akka.NotUsed
import akka.stream.scaladsl.Flow
import cats.effect.unsafe.IORuntime
import miko.MikoConfig
import miko.settings.GuildSettings.Commands.Permissions.{CommandPermission, CommandPermissionMerge}
import miko.settings.{GuildSettings, NamedPermission, SettingsAccess}

import scala.concurrent.Future

abstract class MikoCommandController(components: MikoCommandComponents) extends CommandController(components.requests) {

  def config: MikoConfig            = components.config
  def settings: SettingsAccess      = components.settingsAccess
  implicit def ioRuntime: IORuntime = components.runtime

  def botOwnerFilter[M[A] <: CommandMessage[A]]: CommandFunction[M, M] = new CommandFunction[M, M] {
    override def flow[A]: Flow[M[A], Either[Option[CommandError], M[A]], NotUsed] = Flow[M[A]].map { m =>
      if (m.message.authorUserId.exists(config.botOwners.contains(_))) Right(m)
      else Left(Some(CommandError("Only bot owners can use this command", m.textChannel, m.cache)))
    }
  }

  def getGuildSettings(c: CacheSnapshot, m: Message): Future[GuildSettings] =
    m.guild(c)
      .map(_.id)
      .fold[Future[GuildSettings]](Future.failed(new NoSuchElementException("No guild settings for message")))(
        guildId => settings.getGuildSettings(guildId).unsafeToFuture()
      )

  private def checkPermissions(m: Message, permission: CommandPermission)(implicit c: CacheSnapshot): Boolean =
    permission match {
      case CommandPermission.Allow    => true
      case CommandPermission.Disallow => false
      case CommandPermission.HasPermission(permission) =>
        m.guild.exists { guild =>
          m.guildMember.exists { member =>
            member
              .channelPermissionsId(guild, m.channelId.asChannelId[GuildChannel])
              .hasPermissions(NamedPermission.toPermission(permission))
          }
        }

      case CommandPermission.HasRole(role)      => m.guildMember.exists(_.roleIds.contains(role))
      case CommandPermission.InChannel(channel) => m.channelId == channel
      case CommandPermission.IsUser(user)       => m.authorUserId.contains(user)
      case CommandPermission.And(permissions)   => permissions.forall(checkPermissions(m, _))
      case CommandPermission.Or(permissions)    => permissions.exists(checkPermissions(m, _))
      case CommandPermission.Xor(permissions)   => permissions.count(checkPermissions(m, _)) == 1
    }

  def named(
      aliases: Seq[String],
      category: CommandCategory,
      getPermissions: GuildSettings.Commands.Permissions => CommandPermission
  ): StructuredPrefixParser =
    PrefixParser.structuredAsync(
      getGuildSettings(_, _).map(_.commands.requiresMention),
      getGuildSettings(_, _).map(s => category.getPrefixes(s.commands.prefixes)),
      (_, _) => Future.successful(aliases),
      canExecute = (c, m) =>
        getGuildSettings(c, m).map(_.commands.permissions).map { permissions =>
          val categoryPermissions   = category.categoryPermission(permissions)
          val categoryMergeStrategy = category.permissionMergeStrategy(permissions)
          val commandPermission     = getPermissions(permissions)

          val permissionToTest = categoryMergeStrategy match {
            case CommandPermissionMerge.And => CommandPermission.And(Seq(categoryPermissions, commandPermission))
            case CommandPermissionMerge.Or  => CommandPermission.Or(Seq(categoryPermissions, commandPermission))
            case CommandPermissionMerge.Xor => CommandPermission.Xor(Seq(categoryPermissions, commandPermission))
          }

          checkPermissions(m, permissionToTest)(c)
        }
    )

  def namedCustomPerm(
      aliases: Seq[String],
      category: CommandCategory,
      perms: CommandPermission
  ): StructuredPrefixParser = PrefixParser.structuredAsync(
    getGuildSettings(_, _).map(_.commands.requiresMention),
    getGuildSettings(_, _).map(s => category.getPrefixes(s.commands.prefixes)),
    (_, _) => Future.successful(aliases),
    canExecute = (c, m) => Future.successful(checkPermissions(m, perms)(c))
  )

  val BotOwnerCommand: CommandBuilder[UserCommandMessage, NotUsed]             = Command.andThen(botOwnerFilter)
  val BotOwnerGuildCommand: CommandBuilder[GuildMemberCommandMessage, NotUsed] = GuildCommand.andThen(botOwnerFilter)
}
