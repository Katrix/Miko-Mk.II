package miko.image

import ackcord.OptFuture
import ackcord.data.{OutgoingEmbed, OutgoingEmbedImage}
import ackcord.slashcommands.{Command, ResolvedCommandInteraction}
import ackcord.util.JsonSome
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import miko.commands.{CommandCategory, MikoCommandComponents, MikoSlashCommandController}
import miko.settings.GuildSettings
import miko.settings.GuildSettings.Commands.Permissions.CommandPermission
import miko.util.Color

import scala.concurrent.duration._

class ImageCommands(imageCache: ActorRef[ImageCache.Command])(implicit components: MikoCommandComponents)
    extends MikoSlashCommandController(components) {

  def imageCommand(
      name: String,
      description: String,
      getPermissions: GuildSettings.Commands.Permissions => CommandPermission,
      tpe: ImageType
  ): Command[ResolvedCommandInteraction, Option[String]] =
    Command
      .andThen(canExecute(CommandCategory.General, getPermissions))
      .withExtra(CommandCategory.General.slashExtra)
      .named(name, description)
      .withParams(string("Tags", "The tags to search for. Seperate multiple tags with +").notRequired)
      .handle { implicit m =>
        def descEmbed(str: String): Seq[OutgoingEmbed] = Seq(OutgoingEmbed(description = Some(str)))
        implicit val timeout: Timeout                  = Timeout(30.seconds)

        val tags = m.args.map(_.split("\\+").toSet).getOrElse(Set.empty)

        val cachedImage = imageCache.ask[ImageCache.ImageReply](
          respondTo => ImageCache.ImageRequest(tpe, tags, allowExplicit = false, replyTo = respondTo)
        )

        sendEmbed(descEmbed("Loading picture...")).doAsync { implicit t =>
          OptFuture.fromFuture(cachedImage).flatMap {
            case ImageCache.FailedToGetImage(e) =>
              editOriginalMessage(
                embeds = JsonSome(
                  Seq(
                    OutgoingEmbed(
                      description = Some(s"Failed to get image: ${e.getMessage}"),
                      color = Some(Color.Failure)
                    )
                  )
                )
              )
            case ImageCache.GotImage(CachedImage(img, _, _, _)) =>
              editOriginalMessage(
                embeds = JsonSome(
                  Seq(
                    OutgoingEmbed(
                      image = Some(OutgoingEmbedImage(img.toString())),
                      color = Some(Color.Success),
                      description = Some("Here's your image")
                    )
                  )
                )
              )
          }
        }
      }

  val safebooru: Command[ResolvedCommandInteraction, Option[String]] =
    imageCommand("safebooru", "Fetch an image from safebooru", _.general.safebooru, ImageType.Safebooru)
}
