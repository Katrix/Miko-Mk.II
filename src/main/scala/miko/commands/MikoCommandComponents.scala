package miko.commands

import ackcord.requests.Requests
import miko.MikoConfig
import miko.settings.SettingsAccess
import zio.ZEnv

case class MikoCommandComponents(
    requests: Requests,
    config: MikoConfig,
    settingsAccess: SettingsAccess,
    runtime: zio.Runtime[ZEnv]
)
