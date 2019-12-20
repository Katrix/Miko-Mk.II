package miko.web

import scala.collection.immutable

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import ackcord.data.UserId
import miko.services.ServerEvent
import miko.web.WebEvents.ServerEventWrapper

case class WebEvents(publish: Sink[ServerEventWrapper, NotUsed], subscribe: Source[ServerEventWrapper, NotUsed])(
    implicit mat: Materializer
) {

  /**
    * Publish a single message.
    */
  def publishSingle(elem: ServerEventWrapper): Unit = publish.runWith(Source.single(elem))

  /**
    * Publish many messages.
    */
  def publishMany(it: immutable.Iterable[ServerEventWrapper]): Unit = publish.runWith(Source(it))
}
object WebEvents {

  case class ServerEventWrapper(applicableUsers: Set[UserId], event: ServerEvent)

  def create(implicit mat: Materializer): WebEvents = {
    val (sink, source) = MergeHub
      .source[ServerEventWrapper](perProducerBufferSize = 16)
      .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
      .run()

    WebEvents(sink, source)
  }
}
