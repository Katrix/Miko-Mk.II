package miko.commands

import ackcord.commands._
import ackcord.requests.Requests
import akka.NotUsed
import akka.stream.scaladsl.Flow
import miko.MikoConfig

abstract class MikoCommandController(requests: Requests)(implicit config: MikoConfig)
    extends CommandController(requests) {

  def botOwnerFilter[M[A] <: CommandMessage[A]]: CommandFunction[M, M] = new CommandFunction[M, M] {
    override def flow[A]: Flow[M[A], Either[Option[CommandError], M[A]], NotUsed] = Flow[M[A]].map { m =>
      if (m.message.authorUserId.exists(config.botOwners.contains(_))) Right(m)
      else Left(Some(CommandError("Only bot owners can use this command", m.tChannel, m.cache)))
    }
  }

  val BotOwnerCommand: CommandBuilder[UserCommandMessage, NotUsed]             = Command.andThen(botOwnerFilter)
  val BotOwnerGuildCommand: CommandBuilder[GuildMemberCommandMessage, NotUsed] = GuildCommand.andThen(botOwnerFilter)
}
