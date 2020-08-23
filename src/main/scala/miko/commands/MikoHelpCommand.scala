package miko.commands

import ackcord.CacheSnapshot
import ackcord.commands.HelpCommand
import ackcord.data.{EmbedField, Message, OutgoingEmbed, OutgoingEmbedFooter}
import ackcord.requests.{CreateMessageData, Requests}

import scala.concurrent.Future

class MikoHelpCommand(requests: Requests) extends HelpCommand(requests) {

  def getActiveCommands: Seq[HelpCommand.HelpCommandEntry] = registeredCommands.toSeq

  override def createSearchReply(message: Message, query: String, matches: Seq[HelpCommand.HelpCommandProcessedEntry])(
      implicit c: CacheSnapshot
  ): Future[CreateMessageData] = Future.successful(
    CreateMessageData(
      embed = Some(
        OutgoingEmbed(
          title = Some(s"Commands matching: $query"),
          fields = matches.map(createContent(_))
        )
      )
    )
  )

  override def createReplyAll(message: Message, page: Int)(implicit c: CacheSnapshot): Future[CreateMessageData] = {
    if (page <= 0) {
      Future.successful(CreateMessageData(embed = Some(OutgoingEmbed(description = Some("Invalid Page")))))
    } else {
      Future
        .traverse(registeredCommands.toSeq) { entry =>
          entry.prefixParser
            .canExecute(c, message)
            .zip(entry.prefixParser.needsMention(c, message))
            .zip(entry.prefixParser.symbols(c, message))
            .zip(entry.prefixParser.aliases(c, message))
            .zip(entry.prefixParser.caseSensitive(c, message))
            .map(entry -> _)
        }
        .map { entries =>
          val commandSlice = entries
            .collect {
              case (entry, ((((canExecute, needsMention), symbols), aliases), caseSensitive)) if canExecute =>
                HelpCommand.HelpCommandProcessedEntry(needsMention, symbols, aliases, caseSensitive, entry.description)
            }
            .sortBy(e => (e.symbols.head, e.aliases.head))
            .slice((page - 1) * 10, page * 10)

          val maxPages = Math.max(Math.ceil(registeredCommands.size / 10d).toInt, 1)
          if (commandSlice.isEmpty) {
            CreateMessageData(s"Max pages: $maxPages")
          } else {

            CreateMessageData(
              embed = Some(
                OutgoingEmbed(
                  fields = commandSlice.map(createContent(_)),
                  footer = Some(OutgoingEmbedFooter(s"Page: $page of $maxPages"))
                )
              )
            )
          }
        }
    }
  }

  def createContent(entry: HelpCommand.HelpCommandProcessedEntry)(implicit c: CacheSnapshot): EmbedField = {
    val invocation = {
      val mention = if (entry.needsMention) s"${c.botUser.mention} " else ""
      val symbol  = if (entry.symbols.length > 1) entry.symbols.mkString("(", "|", ")") else entry.symbols.head
      val alias   = if (entry.aliases.length > 1) entry.aliases.mkString("(", "|", ")") else entry.aliases.head

      mention + symbol + alias
    }

    val builder = new StringBuilder
    builder.append(s"Name: ${entry.description.name}\n")
    builder.append(s"Description: ${entry.description.description}\n")
    builder.append(s"Usage: $invocation ${entry.description.usage}\n")

    EmbedField(entry.description.name, builder.mkString)
  }
}
