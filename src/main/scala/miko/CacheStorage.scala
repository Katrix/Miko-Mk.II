package miko

import java.nio.file.{Files, Paths}

import ackcord.CacheSnapshot.BotUser
import ackcord.data._
import ackcord.{APIMessage, Cache, CacheSnapshot, MemoryCacheSnapshot}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.stream.typed.scaladsl.ActorSink
import io.circe._
import io.circe.syntax._
import miko.util.SGFCPool
import shapeless.tag.@@

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object CacheStorage {
  def apply(cache: Cache): Behavior[SGFCPool.Msg[Command, CacheSnapshot]] = {

    SGFCPool[InnerCommand, Command, CacheSnapshot](
      5,
      within = 10.seconds,
      Behaviors.setup[InnerCommand] { ctx =>
        implicit val system: ActorSystem[Nothing] = ctx.system
        cache.subscribeAPI.runWith(
          ActorSink.actorRefWithBackpressure(ctx.self, Event, InitSink, Ack, Shutdown, _ => Shutdown)
        )

        Behaviors.withStash(32) { stash =>
          running(ctx, stash, null, 0)
        }
      }, {
        case SGFCPool.RouteeCommand(replyTo, GetLatestCache) => GetLatestCacheInner(replyTo)
      }
    )
  }

  private val CreateDummyData = false

  private def running(
      context: ActorContext[InnerCommand],
      stash: StashBuffer[InnerCommand],
      latestCache: MemoryCacheSnapshot,
      receiveCounter: Int
  ): Behavior[InnerCommand] =
    Behaviors.receiveMessage {
      case GetLatestCacheInner(replyTo) =>
        if (latestCache == null) stash.stash(GetLatestCacheInner(replyTo))
        else replyTo ! latestCache

        Behaviors.same

      case InitSink(replyTo) =>
        replyTo ! Ack
        Behaviors.same

      case Event(replyTo, event) =>
        implicit val system: ActorSystem[Nothing] = context.system

        if (!MikoConfig().useDummyData && CreateDummyData && receiveCounter == 5) {
          Files.write(Paths.get("offlineCache.json"), latestCache.asJson.spaces2.linesIterator.toSeq.asJava)
        }

        replyTo ! Ack
        stash.unstashAll(running(context, stash, event.cache.current, receiveCounter + 1))

      case Shutdown => Behaviors.stopped
    }

  private case object Ack

  sealed trait InnerCommand
  case class GetLatestCacheInner(replyTo: ActorRef[CacheSnapshot])         extends InnerCommand
  private case class InitSink(replyTo: ActorRef[Ack.type])                 extends InnerCommand
  private case class Event(replyTo: ActorRef[Ack.type], event: APIMessage) extends InnerCommand
  private case object Shutdown                                             extends InnerCommand

  sealed trait Command
  case object GetLatestCache extends Command

  import MikoProtocol._
  import io.circe.generic.auto._

  implicit private val botUserEncoder: Encoder[User @@ BotUser] = (a: User @@ BotUser) => (a: User).asJson
  implicit private val botUserDecoder: Decoder[User @@ BotUser] = (c: HCursor) =>
    c.as[User].map(u => shapeless.tag[BotUser](u))

  implicit private val cacheProcessorEncoder: Encoder[MemoryCacheSnapshot.CacheProcessor] =
    (_: MemoryCacheSnapshot.CacheProcessor) => Json.Null
  implicit private val cacheProcessorDecoder: Decoder[MemoryCacheSnapshot.CacheProcessor] =
    (_: HCursor) => Right(MemoryCacheSnapshot.defaultCacheProcessor)

  implicit private val memoryCacheSnapshotEncoder: Encoder[MemoryCacheSnapshot] = generic.semiauto.deriveEncoder
  implicit private val memoryCacheSnapshotDecoder: Decoder[MemoryCacheSnapshot] = generic.semiauto.deriveDecoder
}
