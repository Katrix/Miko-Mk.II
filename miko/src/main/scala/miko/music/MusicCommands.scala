package miko.music

import ackcord.CacheSnapshot
import ackcord.commands.{VoiceGuildMemberCommandMessage, _}
import ackcord.requests.Requests
import ackcord.syntax._
import ackcord.util.GuildRouter
import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Flow
import cats.Monad
import cats.mtl.{ApplicativeHandle, MonadState}
import cats.syntax.all._
import miko.MikoConfig
import miko.commands.MikoCommandController

class MusicCommands(
    musicHandler: ActorRef[GuildRouter.Command[Nothing, GuildMusicHandler.Command]],
    requests: Requests
)(
    implicit config: MikoConfig
) extends MikoCommandController(requests) {

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
          val isBotInSameVChannel = botVChannelId.contains(m.vChannel.id)

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
    GuildMusicHandler.MusicCmdInfo(Some(m.tChannel), m.vChannel.id, Some(m.cache))

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

  val pause: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Pause, m)
  }

  val volume: Command[Int] = MusicCommand.parsing[Int].withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Volume(m.parsed), m)
  }

  val defVolume: Command[Int] = GuildCommand.parsing[Int].withSideEffects { implicit m =>
    musicHandler ! GuildRouter
      .SendToGuildActor(m.guild.id, GuildMusicHandler.SetDefaultVolume(m.parsed, Some(m.tChannel), Some(m.cache)))
  }

  val stop: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Stop, m)
  }

  val nowPlaying: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.NowPlaying, m)
  }

  val queue: Command[String] = GuildVoiceCommand.parsing[String].withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Queue(m.parsed), m)
  }

  val next: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Next, m)
  }

  val prev: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Prev, m)
  }

  val clear: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Clear, m)
  }

  val shuffle: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Shuffle, m)
  }

  val ytQueue: Command[String] = GuildVoiceCommand.parsing[String].withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Queue(s"ytsearch:${m.parsed}"), m)
  }

  val scQueue: Command[String] = GuildVoiceCommand.parsing[String].withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Queue(s"scsearch:${m.parsed}"), m)
  }

  val gui: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Gui, m)
  }

  //TODO: Add to AckCord
  def parseOrElseWith[A, B, C](parse1: MessageParser[A], parse2: MessageParser[B])(
      combine: Either[A, B] => C
  ): MessageParser[C] =
    new MessageParser[C] {
      override def parse[F[_]](
          implicit c: CacheSnapshot,
          F: Monad[F],
          E: ApplicativeHandle[F, String],
          S: MonadState[F, List[String]]
      ): F[C] =
        E.handleWith(parse1.parse[F].map(a => combine(Left(a))))(_ => parse2.parse[F].map(b => combine(Right(b))))
    }

  val seek: Command[(Option[String], Long)] =
    MusicCommand
      .parsing(
        (
          MessageParser
            .optional(parseOrElseWith(MessageParser.startsWith("+"), MessageParser.startsWith("-"))(_.merge)),
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

  val progress: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.Progress, m)
  }

  val loop: Command[NotUsed] = MusicCommand.withSideEffects { implicit m =>
    musicHandler ! musicCommand(GuildMusicCommand.ToggleLoop, m)
  }
}
