package miko.image

import ackcord.OptFuture
import ackcord.commands._
import ackcord.data.{OutgoingEmbed, OutgoingEmbedImage}
import ackcord.syntax._
import ackcord.util.JsonSome
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import miko.commands.{CommandCategory, MikoCommandComponents, MikoCommandController}
import miko.util.Color

import scala.concurrent.duration._

class ImageCommands(imageCache: ActorRef[ImageCache.Command])(implicit components: MikoCommandComponents)
    extends MikoCommandController(components) {

  def imageCommand(tpe: ImageType): Command[Option[String]] = Command.parsing[Option[String]].asyncOptRequest {
    implicit m =>
      def descEmbed(str: String): Some[OutgoingEmbed] = Some(OutgoingEmbed(description = Some(str)))
      implicit val system: ActorSystem[Nothing]       = this.requests.system
      implicit val timeout: Timeout                   = Timeout(30.seconds)

      val tags = m.parsed.map(_.split("\\+").toSet).getOrElse(Set.empty)

      val cachedImage = imageCache.ask[ImageCache.ImageReply](
        respondTo => ImageCache.ImageRequest(tpe, tags, allowExplicit = false, replyTo = respondTo)
      )
      val msg = requests.singleFutureSuccess(m.textChannel.sendMessage(embed = descEmbed("Loading picture...")))

      OptFuture.fromFuture(
        cachedImage.zip(msg).map {
          case (ImageCache.FailedToGetImage(e), rawMsg) =>
            rawMsg.toMessage.edit(
              embed = JsonSome(
                OutgoingEmbed(
                  description = Some(s"Failed to get image: ${e.getMessage}"),
                  color = Some(Color.Failure)
                )
              )
            )
          case (ImageCache.GotImage(CachedImage(img, _, _, _)), rawMsg) =>
            rawMsg.toMessage.edit(
              embed = JsonSome(
                OutgoingEmbed(
                  image = Some(OutgoingEmbedImage(img.toString())),
                  color = Some(Color.Success),
                  description = Some("Here's your image")
                )
              )
            )
        }
      )
  }

  val safebooru: NamedDescribedCommand[Option[String]] =
    imageCommand(ImageType.Safebooru)
      .toNamed(named(Seq("safebooru"), CommandCategory.General, _.general.safebooru))
      .toDescribed(
        CommandDescription("Safebooru", "Fetch an image from safebooru", extra = CommandCategory.General.extra)
      )
}
