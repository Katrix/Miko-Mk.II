package miko.music

import GuildMusicHandler.MusicCommand

case class EmojiCommand(unicode: String, shortcode: String, command: MusicCommand, description: String)
object EmojiCommand {

  private[music] val commands = Seq(
    EmojiCommand("\u23EE\uFE0F", "track_previous", MusicCommand.Prev, "&prev"),
    EmojiCommand("\u23EA", "rewind", MusicCommand.Seek(-10, useOffset = true), "&seek -10"),
    EmojiCommand("\u23EF\uFE0F", "play_pause", MusicCommand.Pause, "&p"),
    EmojiCommand("\u23E9", "fast_forward", MusicCommand.Seek(10, useOffset = true), "&seek +10"),
    EmojiCommand("\u23ED\uFE0F", "track_next", MusicCommand.Next, "&n"),
    EmojiCommand("\uD83D\uDD00", "twisted_rightwards_arrows", MusicCommand.Shuffle, "&shuffle"),
    EmojiCommand("\uD83D\uDD01", "repeat", MusicCommand.ToggleLoop, "&loop"),
    EmojiCommand("\u23F9\uFE0F", "stop_button", GuildMusicHandler.MusicCommand.Stop, "&s")
  )

  private[music] val commandsByUnicode = commands.groupMapReduce(_.unicode)(identity)((_, snd) => snd)
}
