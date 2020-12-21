package miko.commands

import ackcord.CacheSnapshot
import ackcord.data.{Guild, GuildChannel}
import ackcord.slashcommands.{CacheCommandInteraction, CacheSlashCommandController, CommandTransformer, GuildCommandInteraction}
import akka.actor.typed.ActorSystem
import miko.MikoConfig
import miko.settings.GuildSettings.Commands.Permissions.{CommandPermission, CommandPermissionMerge}
import miko.settings.{GuildSettings, NamedPermission, SettingsAccess}
import zio.ZEnv

class MikoSlashCommandController(components: MikoCommandComponents)
    extends CacheSlashCommandController(components.requests) {

  def config: MikoConfig            = components.config
  def settings: SettingsAccess      = components.settingsAccess
  def zioRuntime: zio.Runtime[ZEnv] = components.runtime

  implicit def system: ActorSystem[Nothing] = components.requests.system

  private def getGuildSettingsSync(c: CacheSnapshot, g: Guild): GuildSettings =
    zioRuntime.unsafeRun(settings.getGuildSettings(g.id))

  //noinspection ComparingUnrelatedTypes
  private def checkPermissions(m: GuildCommandInteraction[_], permission: CommandPermission)(
      implicit c: CacheSnapshot
  ): Boolean =
    permission match {
      case CommandPermission.Allow    => true
      case CommandPermission.Disallow => false
      case CommandPermission.HasPermission(permission) =>
        m.member
          .channelPermissionsId(m.guild, m.channelId.asChannelId[GuildChannel])
          .hasPermissions(NamedPermission.toPermission(permission))

      case CommandPermission.HasRole(role)      => m.member.roleIds.contains(role)
      case CommandPermission.InChannel(channel) => m.channelId == channel
      case CommandPermission.IsUser(userId)     => m.user.id == userId
      case CommandPermission.And(permissions)   => permissions.forall(checkPermissions(m, _))
      case CommandPermission.Or(permissions)    => permissions.exists(checkPermissions(m, _))
      case CommandPermission.Xor(permissions)   => permissions.count(checkPermissions(m, _)) == 1
    }

  def canExecute[M[A] <: CacheCommandInteraction[A]](
      category: CommandCategory,
      getPermissions: GuildSettings.Commands.Permissions => CommandPermission
  ): CommandTransformer[M, M] = new CommandTransformer[M, M] {
    override def filter[A](from: M[A]): Either[Option[String], M[A]] =
      from match {
        case from2: GuildCommandInteraction[A] =>
          val settings    = getGuildSettingsSync(from2.cache, from2.guild)
          val permissions = settings.commands.permissions

          val categoryPermissions   = category.categoryPermission(permissions)
          val categoryMergeStrategy = category.permissionMergeStrategy(permissions)
          val commandPermission     = getPermissions(permissions)

          val permissionToTest = categoryMergeStrategy match {
            case CommandPermissionMerge.And => CommandPermission.And(Seq(categoryPermissions, commandPermission))
            case CommandPermissionMerge.Or  => CommandPermission.Or(Seq(categoryPermissions, commandPermission))
            case CommandPermissionMerge.Xor => CommandPermission.Xor(Seq(categoryPermissions, commandPermission))
          }

          if (checkPermissions(from2, permissionToTest)(from2.cache)) {
            Right(from)
          } else {
            Left(Some("No permission to use this command"))
          }
        case _ => Right(from)
      }
  }
}
