package miko.image

import java.io.IOException
import java.time.{Duration, Instant}

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import miko.image.ImageCache._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}
import scala.xml.NodeSeq

class ImageCache(ctx: ActorContext[ImageCache.Command], timers: TimerScheduler[ImageCache.Command])
    extends AbstractBehavior[ImageCache.Command](ctx)
    with ScalaXmlSupport {

  import context.executionContext

  implicit val system: ActorSystem[Nothing] = context.system

  private val rand                                            = new Random()
  private val cache: Map[ImageType, mutable.Set[CachedImage]] = Map(ImageType.Safebooru -> mutable.Set.empty)
  private val recentlySeenImages: Map[ImageType, mutable.Set[CachedImage]] = Map(
    ImageType.Safebooru -> mutable.Set.empty
  )

  timers.startTimerWithFixedDelay("ClearCache", ClearCache, 15.minutes)
  timers.startTimerWithFixedDelay("ClearRecentlySeenImage", ClearRecentlySeenImage, 15.minutes)

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case request: ImageRequest =>
      getImage(request).onComplete {
        case Success(image) => request.replyTo ! GotImage(image)
        case Failure(e)     => request.replyTo ! FailedToGetImage(e)
      }

      Behaviors.same

    case DownloadedImages(tpe, images) =>
      cache(tpe) ++= images.diff(recentlySeenImages(tpe))

      Behaviors.same
    case AddRecentlySeen(tpe, image) =>
      cache(tpe) -= image
      recentlySeenImages(tpe) += image

      Behaviors.same
    case ClearCache =>
      val now      = Instant.now()
      val lifetime = Duration.ofMinutes(15)

      cache.values.foreach { set =>
        set --= set.filter(_.createdAt.plus(lifetime).isBefore(now))
      }

      Behaviors.same
    case ClearRecentlySeenImage =>
      val now      = Instant.now()
      val lifetime = Duration.ofMinutes(60)

      recentlySeenImages.values.foreach { set =>
        set --= set.filter(_.createdAt.plus(lifetime).isBefore(now))
      }

      Behaviors.same
  }

  def getImage(request: ImageRequest): Future[CachedImage] = {
    val cacheTpe        = cache(request.tpe)
    val recentlySeenTpe = recentlySeenImages(request.tpe)

    val filtered = cacheTpe
      .filter { img =>
        val checkSafe = request.allowExplicit || (img.rating == Rating.Safe)

        if (request.tags.isEmpty) checkSafe
        else checkSafe && request.tags.subsetOf(img.tags)
      }
      .diff(recentlySeenTpe)

    if (filtered.isEmpty) {
      downloadImages(request).map { s =>
        val retObj = randFromSet(s)
        context.self ! DownloadedImages(request.tpe, s.filter(_ != retObj))
        context.self ! AddRecentlySeen(request.tpe, retObj)
        retObj
      }
    } else {
      if (filtered.size < 5) downloadImages(request).foreach { s =>
        context.self ! DownloadedImages(request.tpe, s)
      }

      val retObj = randFromSet(filtered)
      cacheTpe.remove(retObj)
      recentlySeenTpe.add(retObj)
      Future.successful(retObj)
    }
  }

  private def downloadImages(request: ImageRequest): Future[Set[CachedImage]] = {
    println("Downloading more images")
    val uri = request.tpe match {
      case ImageType.Safebooru =>
        s"https://safebooru.org/index.php?page=dapi&s=post&q=index&limit=100&tags=${request.tags.mkString("+")}"
    }
    Http(system.toClassic)
      .singleRequest(HttpRequest(uri = uri))
      .flatMap { response =>
        response.status match {
          case s if s.isSuccess() =>
            request.tpe match {
              case ImageType.Safebooru =>
                Unmarshal(response.entity).to[NodeSeq].flatMap { nodeSeq =>
                  Future.fromTry(parseSafebooruXml(nodeSeq).left.map(s => new Exception(s)).toTry)
                }
            }

          case s =>
            response.discardEntityBytes()
            Future.failed(new IOException(s.reason()))
        }
      }
  }

  private def parseSafebooruXml(xml: NodeSeq): Either[String, Set[CachedImage]] = {
    val res = xml.headOption
      .toRight("Unfamiliar XML")
      .map(_.child)
      .map { seq =>
        seq
          .map { c =>
            for {
              urlNode    <- c.attribute("file_url").flatMap(_.headOption)
              tagsNode   <- c.attribute("tags").flatMap(_.headOption)
              ratingNode <- c.attribute("rating").flatMap(_.headOption)
            } yield {
              val uri    = urlNode.text
              val tags   = tagsNode.text.split(" ").toSet
              val rating = if (ratingNode.text == "s") Rating.Safe else Rating.Explicit

              CachedImage(uri, tags, rating, Instant.now())
            }
          }
          .collect {
            case Some(a) => a
          }
      }

    res.filterOrElse(_.nonEmpty, "No images found").map(_.toSet)
  }

  private def randFromSet[A](set: collection.Set[A]): A =
    set.iterator.drop(rand.nextInt(set.size)).next()
}
object ImageCache {
  def apply(): Behavior[Command] = Behaviors.setup(ctx => Behaviors.withTimers(timers => new ImageCache(ctx, timers)))

  sealed trait ImageReply
  case class GotImage(image: CachedImage)   extends ImageReply
  case class FailedToGetImage(e: Throwable) extends ImageReply

  sealed trait Command

  case class ImageRequest(tpe: ImageType, tags: Set[String], allowExplicit: Boolean, replyTo: ActorRef[ImageReply])
      extends Command

  private case class AddRecentlySeen(tpe: ImageType, image: CachedImage)        extends Command
  private case class DownloadedImages(tpe: ImageType, images: Set[CachedImage]) extends Command
  private case object ClearCache                                                extends Command
  private case object ClearRecentlySeenImage                                    extends Command
}
