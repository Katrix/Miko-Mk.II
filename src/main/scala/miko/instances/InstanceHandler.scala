package miko.instances

import ackcord.Cache
import ackcord.data.GuildId
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri
import miko.MikoConfig
import org.slf4j.Logger

import scala.collection.mutable

class InstanceHandler(ctx: ActorContext[InstanceHandler.Command], topCache: Cache, wsUri: Uri)
    extends AbstractBehavior[InstanceHandler.Command](ctx) {
  import InstanceHandler._
  val log: Logger = context.log

  private val secondaryTokens = MikoConfig()(context.system).secondaryTokens
  private val secondaries = secondaryTokens.zipWithIndex.map {
    case (token, idx) =>
      context.spawn(Instance.secondary(wsUri, token), s"Instance-Secondary-$idx")
  } :+ context.spawn(Instance.primary(topCache), "Instance-Primary")

  val guildReservationAttempt = mutable.HashMap.empty[Long, Seq[ActorRef[Instance.Command]]]
  val negotiators             = mutable.HashMap.empty[Long, ActorRef[InstanceNegotiator.Command]]
  val encounteredNonces       = mutable.HashSet.empty[Long]

  private var instanceShutdownCount = 0

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case Shutdown =>
        secondaries.foreach { secondary =>
          context.watch(secondary)
          secondary ! Instance.Shutdown
        }

      case ReserveInstance(reservationId, guildId, replyTo) =>
        if (!encounteredNonces.contains(reservationId)) {
          val negotiator =
            context.spawn(InstanceNegotiator(reservationId, secondaries, replyTo), s"Negotiator$reservationId")
          context.watchWith(negotiator, RemoveNegotiator(reservationId))

          negotiators.put(reservationId, negotiator)
          encounteredNonces += reservationId

          secondaries.foreach(_ ! Instance.RequestReservation(guildId, reservationId, replyTo, negotiator))
        }

      case CancelReservation(reservationId) =>
        negotiators.get(reservationId).foreach(_ ! InstanceNegotiator.CancelReservation)

      case RemoveNegotiator(reservationId) =>
        negotiators.remove(reservationId)
    }

    Behaviors.same
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case Terminated(_) =>
      instanceShutdownCount += 1
      if (instanceShutdownCount == secondaries.size) Behaviors.stopped else Behaviors.same
  }
}
object InstanceHandler {
  def apply(topCache: Cache, wsUri: Uri): Behavior[Command] =
    Behaviors.setup(ctx => new InstanceHandler(ctx, topCache, wsUri))

  sealed trait Command
  case object Shutdown                                                                                   extends Command
  case class ReserveInstance(reservationId: Long, guildId: GuildId, replyTo: ActorRef[ReservationReply]) extends Command
  case class CancelReservation(reservationId: Long)                                                      extends Command

  private case class RemoveNegotiator(reservationId: Long) extends Command

  sealed trait ReservationReply
  case class SendLifetime(reservationId: Long, replyTo: ActorRef[Instance.SetLifetime], instanceCache: Cache)
      extends ReservationReply
  case class ReservationFailed(reservationId: Long) extends ReservationReply
}
