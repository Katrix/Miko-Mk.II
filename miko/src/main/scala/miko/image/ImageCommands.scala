package miko.image

import ackcord.data.{OutgoingEmbed, OutgoingEmbedImage}
import ackcord.requests.{Requests, RequestStreams}
import ackcord.syntax._
import ackcord.util.JsonSome
import ackcord.commands._
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import miko.commands.MikoCommandController
import miko.MikoConfig
import miko.util.Color

import scala.concurrent.duration._

class ImageCommands(requests: Requests, imageCache: ActorRef[ImageCache.Command])(implicit config: MikoConfig)
    extends MikoCommandController(requests) {

  implicit val timeout: Timeout = Timeout(30.seconds)

  def imageCommand(tpe: ImageType): Command[Option[String]] = Command.parsing[Option[String]].streamed {
    Flow[CommandMessage[Option[String]]]
      .flatMapConcat { implicit m =>
        def descEmbed(str: String): Some[OutgoingEmbed] = Some(OutgoingEmbed(description = Some(str)))

        val tags = m.parsed.map(_.split("\\+").toSet).getOrElse(Set.empty)
        val cachedImage = Source
          .single(())
          .via(
            ActorFlow.ask[Unit, ImageCache.Command, ImageCache.ImageReply](imageCache)(
              (_, a) => ImageCache.ImageRequest(tpe, tags, allowExplicit = false, a)
            )
          )
        val request = m.tChannel.sendMessage(embed = descEmbed("Loading picture..."))
        val msg     = Source.single(request).via(RequestStreams.removeContext(requests.flowSuccess))

        cachedImage.zip(msg)
      }
      .map {
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
      .to(requests.sinkIgnore)
  }

  val safebooru: Command[Option[String]] = imageCommand(ImageType.Safebooru)
}
