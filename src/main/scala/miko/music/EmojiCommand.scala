package miko.music

import ackcord.data.{ActionRow, Button, ButtonStyle, PartialEmoji, TextButtonStyle}
import miko.music.GuildMusicHandler.MusicCommand

case class EmojiCommand(
    unicode: String,
    shortcode: String,
    command: MusicCommand,
    description: String,
    identifier: String,
    buttonStyle: TextButtonStyle = ButtonStyle.Secondary
)
object EmojiCommand {

  private[music] val commands = Seq(
    EmojiCommand("\u23EE\uFE0F", "track_previous", MusicCommand.Prev, "Previous track", "tp"),
    EmojiCommand("\u23EA", "rewind", MusicCommand.Seek(-10, useOffset = true), "Seek -10 seconds", "sm"),
    EmojiCommand("\u23EF\uFE0F", "play_pause", MusicCommand.Pause, "Toggle pause", "pa"),
    EmojiCommand("\u23E9", "fast_forward", MusicCommand.Seek(10, useOffset = true), "Seek +10 seconds", "s+"),
    EmojiCommand("\u23ED\uFE0F", "track_next", MusicCommand.Next, "Next track", "tn"),
    EmojiCommand("\uD83D\uDD00", "twisted_rightwards_arrows", MusicCommand.Shuffle, "Shuffle playlist", "sh"),
    EmojiCommand("\uD83D\uDD01", "repeat", MusicCommand.ToggleLoop, "Toggle loop", "lp"),
    EmojiCommand("\u23F9\uFE0F", "stop_button", GuildMusicHandler.MusicCommand.Stop, "Stop music", "st", ButtonStyle.Danger)
  )

  def actionRows(identifierPrefix: String): Seq[ActionRow] =
    commands
      .grouped(5)
      .toSeq
      .map { seq =>
        ActionRow.of(
          seq.map { c =>
            Button.textEmoji(
              PartialEmoji(name = Some(c.unicode)),
              identifier = s"${identifierPrefix}_${c.identifier}",
              style = c.buttonStyle
            )
          }: _*
        )
      }
}
