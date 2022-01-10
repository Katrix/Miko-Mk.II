package miko.commands

import ackcord.requests.Requests
import cats.effect.unsafe.IORuntime
import miko.MikoConfig
import miko.settings.SettingsAccess

case class MikoCommandComponents(
    requests: Requests,
    config: MikoConfig,
    settingsAccess: SettingsAccess,
    runtime: IORuntime
)
