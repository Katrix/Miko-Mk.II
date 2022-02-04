package miko.music

import ackcord.data.{GatewayGuild, GuildMember, NormalVoiceGuildChannel, Permission, TextGuildChannel}
import ackcord.interactions._
import ackcord.interactions.commands._
import ackcord.syntax._
import ackcord.util.GuildRouter
import ackcord.{CacheSnapshot, OptFuture}
import akka.NotUsed
import akka.actor.typed.ActorRef
import cats.Id
import miko.commands.{CommandCategory, MikoCommandComponents, MikoSlashCommandController}

class MusicCommands(musicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]])(
    implicit components: MikoCommandComponents
) extends MikoSlashCommandController(components) {

  case class NormalVoiceChannelCommandInteraction[A](
      commandInvocationInfo: CommandInvocationInfo[A],
      textChannel: TextGuildChannel,
      guild: GatewayGuild,
      member: GuildMember,
      memberPermissions: Permission,
      voiceChannel: NormalVoiceGuildChannel,
      cache: CacheSnapshot
  ) extends VoiceChannelCommandInteraction[A]

  val inVoiceChannelWithBot
      : DataInteractionTransformer[VoiceChannelCommandInteraction, NormalVoiceChannelCommandInteraction] =
    new DataInteractionTransformer[VoiceChannelCommandInteraction, NormalVoiceChannelCommandInteraction] {
      override def filter[A](
          from: VoiceChannelCommandInteraction[A]
      ): Either[Option[String], NormalVoiceChannelCommandInteraction[A]] = {
        val botUser       = from.cache.botUser
        val botVChannelId = from.guild.voiceStateFor(botUser.id).flatMap(_.channelId)

        val botIsInVChannel = botVChannelId.isDefined
        //noinspection ComparingUnrelatedTypes
        val isBotInSameVChannel = botVChannelId.contains(from.voiceChannel.id)

        if (isBotInSameVChannel) {
          from.voiceChannel match {
            case channel: NormalVoiceGuildChannel =>
              Right(
                NormalVoiceChannelCommandInteraction(
                  from.commandInvocationInfo,
                  from.textChannel,
                  from.guild,
                  from.member,
                  from.memberPermissions,
                  channel,
                  from.cache
                )
              )

            case _ => Left(Some("Only normal voice channels are supported"))
          }
        } else if (botIsInVChannel) Left(Some("You are in a different voice channel"))
        else Left(Some("No music is playing"))
      }
    }

  import GuildMusicHandler.{MusicCommand => GuildMusicCommand}

  val MusicCommand: SlashCommandBuilder[NormalVoiceChannelCommandInteraction, NotUsed] =
    GuildVoiceCommand.andThen(inVoiceChannelWithBot)

  def musicCommandMsg(
      command: GuildMusicCommand,
      m: NormalVoiceChannelCommandInteraction[_]
  ): InteractionResponse =
    musicCommandMsgWithChannel(command, m, m.voiceChannel)

  def musicCommandMsgWithChannel(
      command: GuildMusicCommand,
      m: VoiceChannelCommandInteraction[_],
      channel: NormalVoiceGuildChannel
  ): InteractionResponse = {
    async { implicit t =>
      OptFuture.unit.map { _ =>
        musicHandler ! GuildRouter.SendToGuildActor(
          m.guild.id,
          GuildMusicHandler.GuildMusicCommandWrapper(
            command,
            (embeds, components) => sendAsyncEmbed(embeds, components = components),
            GuildMusicHandler.MusicCmdInfo(Some(m.textChannel), channel.id, Some(m.cache), None, None)
          )
        )
      }
    }(m)
  }

  private val pause: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.pause))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("pause", "Pause the music playing")(implicit m => musicCommandMsg(GuildMusicCommand.Pause, m))

  private val volume: SlashCommand[NormalVoiceChannelCommandInteraction, Id[Int]] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.volume))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("volume", "Set the volume music is played at")
      .withParams(int("volume", "The new volume"))
      .handle(implicit m => musicCommandMsg(GuildMusicCommand.Volume(m.args), m))

  private val defVolume: SlashCommand[GuildCommandInteraction, Id[Int]] =
    GuildCommand
      .andThen(canExecute(CommandCategory.Music, _.music.defVolume))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("defvol", "Set the volume music is played at when the bot first joins the channel")
      .withParams(int("volume", "The new default volume"))
      .handle { implicit m =>
        async { implicit t =>
          OptFuture.unit.map { _ =>
            musicHandler ! GuildRouter.SendToGuildActor(
              m.guild.id,
              GuildMusicHandler
                .SetDefaultVolume(m.args, (embed) => sendAsyncEmbed(Seq(embed)), Some(m.textChannel), Some(m.cache))
            )
          }
        }
      }

  private val stop: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.stop))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("stop", "Stops the music completely")(implicit m => musicCommandMsg(GuildMusicCommand.Stop, m))

  private val nowPlaying: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.nowPlaying))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("nowplaying", "Checks what track is currently playing")(
        implicit m => musicCommandMsg(GuildMusicCommand.NowPlaying, m)
      )

  private val playlist: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.playlist))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("playlist", "Prints the current playlist")(
        implicit m => musicCommandMsg(GuildMusicCommand.Playlist, m)
      )

  private val queueUrl: SlashCommand[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.queue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named("url", "Adds a new track to the playlist of tracks to play by an url")
      .withParams(string("url", "The url to add"))
      .handle { implicit m =>
        m.voiceChannel match {
          case channel: NormalVoiceGuildChannel =>
            musicCommandMsgWithChannel(GuildMusicCommand.Queue(m.args), m, channel)

          case _ => sendMessage("Only normal voice channels are supported")
        }
      }

  private val ytQueue: SlashCommand[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.ytQueue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named(
        "youtube",
        "Queues the first result from searching for the topic on youtube"
      )
      .withParams(string("search", "What to search for"))
      .handle { implicit m =>
        m.voiceChannel match {
          case channel: NormalVoiceGuildChannel =>
            musicCommandMsgWithChannel(GuildMusicCommand.Queue(s"ytsearch:${m.args}"), m, channel)

          case _ => sendMessage("Only normal voice channels are supported")
        }
      }

  private val scQueue: SlashCommand[VoiceChannelCommandInteraction, Id[String]] =
    GuildVoiceCommand
      .andThen(canExecute(CommandCategory.Music, _.music.scQueue))
      .withExtra(CommandCategory.Music.slashExtra)
      .named(
        "soundcloud",
        "Queues the first result from searching for the topic on Soundcloud"
      )
      .withParams(string("search", "What to search for"))
      .handle { implicit m =>
        m.voiceChannel match {
          case channel: NormalVoiceGuildChannel =>
            musicCommandMsgWithChannel(GuildMusicCommand.Queue(s"scsearch:${m.args}"), m, channel)

          case _ => sendMessage("Only normal voice channels are supported")
        }
      }

  private val next: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.next))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("next", "Skips to the next track")(musicCommandMsg(GuildMusicCommand.Next, _))

  private val prev: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.prev))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("prev", "Plays the previous track")(musicCommandMsg(GuildMusicCommand.Prev, _))

  private val clear: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.clear))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("clear", "Clears the current playlist, while keeping the bot in the room")(
        musicCommandMsg(GuildMusicCommand.Clear, _)
      )

  private val shuffle: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.shuffle))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("shuffle", "Shuffles the playlist")(musicCommandMsg(GuildMusicCommand.Shuffle, _))

  private val gui: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.gui))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("gui", "Brings up the button gui")(musicCommandMsg(GuildMusicCommand.Gui, _))

  private val seek: SlashCommand[NormalVoiceChannelCommandInteraction, (Id[Int], Option[String])] =
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
        println(m.args._1)
        println(m.args._2)

        musicCommandMsg(GuildMusicCommand.Seek(position, offset), m)
      }

  private val loop: SlashCommand[NormalVoiceChannelCommandInteraction, NotUsed] =
    MusicCommand
      .andThen(canExecute(CommandCategory.Music, _.music.loop))
      .withExtra(CommandCategory.Music.slashExtra)
      .command("loop", "Toggles looping mode on and off")(
        musicCommandMsg(GuildMusicCommand.ToggleLoop, _)
      )

  val musicCommand: SlashCommandGroup =
    SlashCommand
      .withExtra(CommandCategory.Music.slashExtra)
      .group("music", "Music commands")(
        pause,
        volume,
        defVolume, //FIXME: Stop thinking
        stop,
        nowPlaying,
        playlist,
        SlashCommand.group("queue", "Queue a track")(
          queueUrl,
          ytQueue,
          scQueue
        ),
        SlashCommand.group("navigate", "Navigate around the track")(
          next,
          prev,
          seek
        ),
        SlashCommand.group("sub", "More commands which need to be nested")(
          shuffle,
          gui, //FIXME in AckCord
          loop,
          clear
        )
      )
}
