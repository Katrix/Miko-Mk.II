package miko.music

import ackcord.commands.{VoiceGuildMemberCommandMessage, _}
import ackcord.syntax._
import ackcord.util.GuildRouter
import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Flow
import cats.syntax.all._
import miko.commands.{CommandCategory, MikoCommandComponents, MikoCommandController}

class MusicCommands(musicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]])(
    implicit components: MikoCommandComponents
) extends MikoCommandController(components) {

  val inVoiceChannelWithBot: CommandFunction[VoiceGuildMemberCommandMessage, VoiceGuildMemberCommandMessage] =
    new CommandFunction[VoiceGuildMemberCommandMessage, VoiceGuildMemberCommandMessage] {
      override def flow[A]: Flow[VoiceGuildMemberCommandMessage[A], Either[
        Option[CommandError],
        VoiceGuildMemberCommandMessage[A]
      ], NotUsed] = {
        Flow[VoiceGuildMemberCommandMessage[A]].map { implicit m =>
          val botUser       = m.cache.botUser
          val botVChannelId = m.guild.voiceStateFor(botUser.id).flatMap(_.channelId)

          val botIsInVChannel     = botVChannelId.isDefined
          val isBotInSameVChannel = botVChannelId.contains(m.voiceChannel.id)

          if (isBotInSameVChannel) Right(m)
          else if (botIsInVChannel) Left(Some(CommandError.mk[A]("You are in a different voice channel", m)))
          else Left(Some(CommandError.mk[A]("No music is playing", m)))
        }
      }
    }

  import GuildMusicHandler.{MusicCommand => GuildMusicCommand}

  val MusicCommand: CommandBuilder[VoiceGuildMemberCommandMessage, NotUsed] =
    GuildVoiceCommand.andThen(inVoiceChannelWithBot)

  def cmdInfo(m: VoiceGuildMemberCommandMessage[_]): GuildMusicHandler.MusicCmdInfo =
    GuildMusicHandler.MusicCmdInfo(Some(m.textChannel), m.voiceChannel.id, Some(m.cache))

  def musicCommand(
      command: GuildMusicCommand,
      m: VoiceGuildMemberCommandMessage[_]
  ): GuildRouter.SendToGuildActor[GuildMusicHandler.GuildMusicCommandWrapper] =
    GuildRouter.SendToGuildActor(
      m.guild.id,
      GuildMusicHandler.GuildMusicCommandWrapper(
        command,
        cmdInfo(m)
      )
    )

  val pause: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("pause", "p"), CommandCategory.Music, _.music.pause))
      .described("Pause", "Pause the music playing", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Pause, m)
      }

  val volume: NamedDescribedCommand[Int] =
    MusicCommand
      .namedParser(named(Seq("volume", "vol"), CommandCategory.Music, _.music.volume))
      .described("Volume", "Set the volume music is played at", "<volume>", extra = CommandCategory.Music.extra)
      .parsing[Int]
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Volume(m.parsed), m)
      }

  val defVolume: NamedDescribedCommand[Int] =
    GuildCommand
      .namedParser(named(Seq("defaultVolume", "defvol"), CommandCategory.Music, _.music.defVolume))
      .described(
        "Default Volume",
        "Set the volume music is played at when the bot first joins the channel",
        "<volume>",
        extra = CommandCategory.Music.extra
      )
      .parsing[Int]
      .withSideEffects { implicit m =>
        musicHandler ! GuildRouter
          .SendToGuildActor(
            m.guild.id,
            GuildMusicHandler.SetDefaultVolume(m.parsed, Some(m.textChannel), Some(m.cache))
          )
      }

  val stop: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("stop", "s"), CommandCategory.Music, _.music.stop))
      .described("Stop", "Stops the music completely", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Stop, m)
      }

  val nowPlaying: NamedDescribedCommand[NotUsed] = MusicCommand
    .namedParser(named(Seq("nowPlaying", "np"), CommandCategory.Music, _.music.nowPlaying))
    .described("Now playing", "Checks what track is currently playing", extra = CommandCategory.Music.extra)
    .withSideEffects { implicit m =>
      musicHandler ! musicCommand(GuildMusicCommand.NowPlaying, m)
    }

  val queue: NamedDescribedCommand[String] =
    GuildVoiceCommand
      .namedParser(named(Seq("queue", "q"), CommandCategory.Music, _.music.queue))
      .described(
        "Queue",
        "Adds a new track to the playlist of tracks to play",
        "<url to add>",
        extra = CommandCategory.Music.extra
      )
      .parsing[String]
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(m.parsed), m)
      }

  val next: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("next", "n"), CommandCategory.Music, _.music.next))
      .described("Next", "Skips to the next track", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Next, m)
      }

  val prev: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("prev", "p"), CommandCategory.Music, _.music.prev))
      .described("Prev", "Plays the previous track", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Prev, m)
      }

  val clear: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("clear"), CommandCategory.Music, _.music.clear))
      .described(
        "Clear",
        "Clears the current playlist, while keeping the bot in the room",
        extra = CommandCategory.Music.extra
      )
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Clear, m)
      }

  val shuffle: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("shuffle"), CommandCategory.Music, _.music.shuffle))
      .described("Shuffle", "Shuffles the playlist", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Shuffle, m)
      }

  val ytQueue: NamedDescribedCommand[MessageParser.RemainingAsString] =
    GuildVoiceCommand
      .namedParser(named(Seq("youtubeQueue", "ytQueue", "ytq"), CommandCategory.Music, _.music.ytQueue))
      .described(
        "Youtube queue",
        "Queues the first result from searching for the topic on youtube",
        "<search...>",
        extra = CommandCategory.Music.extra
      )
      .parsing[MessageParser.RemainingAsString]
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(s"ytsearch:${m.parsed.remaining}"), m)
      }

  val scQueue: NamedDescribedCommand[MessageParser.RemainingAsString] =
    GuildVoiceCommand
      .namedParser(named(Seq("soundcloudQueue", "scQueue", "scq"), CommandCategory.Music, _.music.scQueue))
      .described(
        "Soundcloud queue",
        "Queues the first result from searching for the topic on Soundcloud",
        "<search...>",
        extra = CommandCategory.Music.extra
      )
      .parsing[MessageParser.RemainingAsString]
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Queue(s"scsearch:${m.parsed.remaining}"), m)
      }

  val gui: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("gui"), CommandCategory.Music, _.music.gui))
      .described("GUI", "Brings up the button gui", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Gui, m)
      }

  val seek: NamedDescribedCommand[(Option[String], Long)] =
    MusicCommand
      .namedParser(named(Seq("seek"), CommandCategory.Music, _.music.seek))
      .described("Seek", "Seeks to a duration in the track", "[+|-]<duration>", extra = CommandCategory.Music.extra)
      .parsing(
        (
          MessageParser
            .optional(MessageParser.orElseWith(MessageParser.startsWith("+"), MessageParser.startsWith("-"))(_.merge)),
          MessageParser[Long]
        ).tupled
      )
      .withSideEffects { m =>
        val (position, offset) = m.parsed match {
          case (Some("-"), dur) => (dur * -1, true)
          case (Some("+"), dur) => (dur, true)
          case (None, dur)      => (dur, false)
          case (_, dur)         => (dur, false)
        }

        musicHandler ! musicCommand(GuildMusicCommand.Seek(position, offset), m)
      }

  val progress: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("progress"), CommandCategory.Music, _.music.progress))
      .described("Progress", "Views the current progress of the track", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.Progress, m)
      }

  val loop: NamedDescribedCommand[NotUsed] =
    MusicCommand
      .namedParser(named(Seq("loop"), CommandCategory.Music, _.music.loop))
      .described("Loop", "Toggles looping mode on and off", extra = CommandCategory.Music.extra)
      .withSideEffects { implicit m =>
        musicHandler ! musicCommand(GuildMusicCommand.ToggleLoop, m)
      }
}
