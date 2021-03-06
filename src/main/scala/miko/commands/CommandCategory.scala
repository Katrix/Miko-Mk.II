package miko.commands

import miko.settings.GuildSettings

sealed abstract case class CommandCategory(
    getPrefixes: GuildSettings.Commands.Prefixes => Seq[String],
    categoryPermission: GuildSettings.Commands.Permissions => GuildSettings.Commands.Permissions.CommandPermission,
    permissionMergeStrategy: GuildSettings.Commands.Permissions => GuildSettings.Commands.Permissions.CommandPermissionMerge,
    extra: Map[String, String],
    slashExtra: Map[String, String]
)
object CommandCategory {
  object General
      extends CommandCategory(
        _.general,
        _.general.categoryWide,
        _.general.categoryMergeOperation,
        Map("category" -> "general"),
        Map("category" -> "general", "is_slash" -> "true")
      )

  object Music
      extends CommandCategory(
        _.music,
        _.music.categoryWide,
        _.music.categoryMergeOperation,
        Map("category" -> "music"),
        Map("category" -> "music", "is_slash" -> "true")
      )
}
