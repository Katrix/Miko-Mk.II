package miko.image

import java.time.Instant

import akka.http.scaladsl.model.Uri

case class CachedImage(uri: Uri, tags: Set[String], rating: Rating, createdAt: Instant)
sealed trait Rating
object Rating {
  case object Safe     extends Rating
  case object Explicit extends Rating
}
