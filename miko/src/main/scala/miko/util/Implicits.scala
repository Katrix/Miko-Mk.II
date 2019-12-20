package miko.util

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.IO
import ackcord.util.Streamable

object Implicits {

  implicit val ioStreamable: Streamable[IO] = new Streamable[IO] {
    override def toSource[A](fa: IO[A]): Source[A, NotUsed] = Source.future(fa.unsafeToFuture())
  }
}
