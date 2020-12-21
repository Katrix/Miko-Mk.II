package miko.music

import ackcord.slashcommands._
import ackcord.syntax._
import ackcord.util.GuildRouter
import akka.NotUsed
import akka.actor.typed.ActorRef
import cats.Id
import miko.commands.{CommandCategory, MikoCommandComponents, MikoSlashCommandController}

class MusicCommands(musicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]])(
    implicit components: MikoCommandComponents
) extends MikoSlashCommandController(components) {

  val inVoiceChannelWithBot: CommandTransformer[VoiceChannelCommandInteraction, VoiceChannelCommandInteraction] =
    new CommandTransformer[VoiceChannelCommandInteraction, VoiceChannelCommandInteraction] {
      override def filter[A](
          from: VoiceChannelCommandInteraction[A]
      ): Either[Option[String], VoiceChannelCommandInteraction[A]] = {
        val botUser       = from.cache.botUser
        val botVChannelId = from.guild.voiceStateFor(botUser.id).flatMap(_.channelId)

        val botIsInVChannel = botVChannelId.isDefined
        //noinspection ComparingUnrelatedTypes
        val isBotInSameVChannel = botVChannelId.contains(from.voiceChannel.id)

        if (isBotInSameVChannel) Right(from)
        else if (botIsInVChannel) Left(Some("You are in a different voice channel"))
        else Left(Some("No music is playing"))
      }
    }

  import GuildMusicHandler.{MusicCommand => GuildMusicCommand}

  val MusicCommand: CommandBuilder[VoiceChannelCommandInteraction, NotUsed] =
    GuildVoiceCommand.andThen(inVoiceChannelWithBot)

  def cmdInfo(m: VoiceChannelCommandInteraction[_]): GuildMusicHandler.MusicCmdInfo =
    GuildMusicHandler.MusicCmdInfo(Some(m.textChannel), m.voiceChannel.id, Some(m.cache))

  def musicCommand(
      command: GuildMusicCommand,
      m: VoiceChannelCommandInteraction[_]
  ): GuildRouter.SendToGuildActor[GuildMusicHandler.GuildMusicCommandWrapper] =
    GuildRouter.SendToGuildActor(
      m.guild.id,
      GuildMusicHandler.GuildMusicCommandWrapper(
        command,
        cmdInfo(m)
      )
    )

  private val pause: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.pause))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("pause", "Pause the music playing") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Pause, m)
        acknowledge()
      }

  private val volume: Command[VoiceChannelCommandInteraction, Id[Int]] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.volume))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("volume", "Set the volume music is played at")
      .withParams(int("Volume", "The new volume"))
      .handle { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Volume(m.args), m)
        acknowledge()
      }

  private val defVolume: Command[GuildCommandInteraction, Id[Int]] =
    GuildCommand
      .andThen(canExecute(CommandCategory.Music, _.music.defVolume))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("defVol", "Set the volume music is played at when the bot first joins the channel")
      .withParams(int("Volume", "The new default volume"))
      .handle { implicit m =>
        musicHandler ! GuildRouter.SendToGuildActor(
          m.guild.id,
          GuildMusicHandler.SetDefaultVolume(m.args, Some(m.textChannel), Some(m.cache))
        )
        acknowledge()
      }

  private val stop: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.stop))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("stop", "Stops the music completely") { implicit m =>
        println("Sending stop")
        musicHandler ! musicCommand(GuildMusicCommand.Stop, m)
        acknowledge()
      }

  private val nowPlaying: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.nowPlaying))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("nowplaying", "Checks what track is currently playing") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.NowPlaying, m)
        acknowledge()
      }

  private val queueUrl: Command[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.queue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("url", "Adds a new track to the playlist of tracks to play by an url")
      .withParams(string("url", "The url to add"))
      .handle { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(m.args), m)
        acknowledge()
      }

  private val ytQueue: Command[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.ytQueue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named(
        "youtube",
        "Queues the first result from searching for the topic on youtube"
      )
      .withParams(string("Search", "What to search for"))
      .handle { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(s"ytsearch:${m.args}"), m)
        acknowledge()
      }

  private val scQueue: Command[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.scQueue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named(
        "soundcloud",
        "Queues the first result from searching for the topic on Soundcloud"
      )
      .withParams(string("Search", "What to search for"))
      .handle { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(s"scsearch:${m.args}"), m)
        acknowledge()
      }

  private val next: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.next))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("next", "Skips to the next track") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Next, m)
        acknowledge()
      }

  private val prev: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.prev))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("prev", "Plays the previous track") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Prev, m)
        acknowledge()
      }

  private val clear: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.clear))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("clear", "Clears the current playlist, while keeping the bot in the room") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Clear, m)
        acknowledge()
      }

  private val shuffle: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.shuffle))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("shuffle", "Shuffles the playlist") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Shuffle, m)
        acknowledge()
      }

  private val gui: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.gui))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("gui", "Brings up the button gui") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Gui, m)
        acknowledge()
      }

  private val seek: Command[VoiceChannelCommandInteraction, (Id[Int], Option[String])] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.seek))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("seek", "Seeks to a duration in the track")
      .withParams(
        int("duration", "The amount to seek") ~
          string("offset", "Offset the location from the current one").notRequired
            .withChoices(Map("+" -> "Offset positive", "-" -> "Offset negative"))
      )
      .handle { m =>
        val (position, offset) = m.args match {
          case (dur, Some("-")) => (dur * -1, true)
          case (dur, Some("+")) => (dur, true)
          case (dur, None)      => (dur, false)
          case (dur, _)         => (dur, false)
        }

        musicHandler ! musicCommand(GuildMusicCommand.Seek(position, offset), m)
        acknowledge()
      }

  private val loop: Command[VoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.loop))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("loop", "Toggles looping mode on and off") { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.ToggleLoop, m)
        acknowledge()
      }

  val musicCommand: CommandGroup =
    Command
      .withExtra(CommandCategory.Music.slashExtra)
      .group("music", "Music commands")(
        pause,
        volume,
        defVolume,
        stop,
        nowPlaying,
        Command.group("queue", "Queue a track")(
          queueUrl,
          ytQueue,
          scQueue
        ),
        Command.group("navigate", "Navigate around the track")(
          next,
          prev,
          seek
        ),
        Command.group("sub", "More commands which need to be nested")(
          shuffle,
          gui,
          loop,
          clear
        )
      )
}
