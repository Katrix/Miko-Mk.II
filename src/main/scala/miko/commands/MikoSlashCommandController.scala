package miko.commands

import ackcord.slashcommands.CacheSlashCommandController
import akka.actor.typed.ActorSystem
import miko.MikoConfig
import miko.settings.SettingsAccess
import zio.ZEnv

class MikoSlashCommandController(components: MikoCommandComponents)
    extends CacheSlashCommandController(components.requests) {

  def config: MikoConfig            = components.config
  def settings: SettingsAccess      = components.settingsAccess
  def zioRuntime: zio.Runtime[ZEnv] = components.runtime

  implicit def system: ActorSystem[Nothing] = components.requests.system
}
