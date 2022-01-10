package miko.instances

import akka.actor.typed._
import akka.actor.typed.scaladsl._

import scala.collection.mutable

class InstanceNegotiator(
    ctx: ActorContext[InstanceNegotiator.Command],
    reservationId: Long,
    asked: Seq[ActorRef[Instance.Command]],
    replyTo: ActorRef[InstanceHandler.ReservationReply]
) extends AbstractBehavior[InstanceNegotiator.Command](ctx) {
  import InstanceNegotiator._

  private val awaitingReply = asked.to(mutable.Set)
  private val preferred     = mutable.Buffer.empty[ActorRef[Instance.Command]]
  private val loggedIn      = mutable.Buffer.empty[ActorRef[Instance.Command]]
  private val notLoggedIn   = mutable.Buffer.empty[ActorRef[Instance.Command]]

  def release(seq: collection.Iterable[ActorRef[Instance.Command]]): Unit =
    seq.foreach(_ ! Instance.ReservationHandled(reservationId))

  def announceWinner(): Unit = {
    def releaseAndSend(seq: mutable.Seq[ActorRef[Instance.Command]]): Unit =
      if (seq.nonEmpty) {
        release(seq.view.drop(1))
        seq.head ! Instance.CompleteReservation(reservationId)
      }

    if (preferred.nonEmpty || loggedIn.nonEmpty || notLoggedIn.nonEmpty) {
      releaseAndSend(preferred)
      releaseAndSend(loggedIn)
      releaseAndSend(notLoggedIn)
    } else {
      replyTo ! InstanceHandler.ReservationFailed(reservationId)
    }
  }

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case InstanceReservationStatus(instance, status) =>
      awaitingReply.remove(instance)

      status match {
        case ReservationStatus.IsPreferred =>
          if (preferred.isEmpty) {
            release(loggedIn)
            release(notLoggedIn)
          }
          preferred += instance

        case ReservationStatus.LoggedIn =>
          if (preferred.isEmpty) {
            if (loggedIn.isEmpty) {
              release(notLoggedIn)
            }

            loggedIn += instance
          } else {
            instance ! Instance.ReservationHandled(reservationId)
          }

        case ReservationStatus.NotLoggedIn =>
          if (preferred.isEmpty && loggedIn.isEmpty) {
            notLoggedIn += instance
          } else {
            instance ! Instance.ReservationHandled(reservationId)
          }

        case ReservationStatus.AlreadyServingGuild => // NO OP
      }

      if (awaitingReply.isEmpty) {
        announceWinner()
        Behaviors.stopped
      } else {
        Behaviors.same
      }

    case CancelReservation =>
      release(preferred)
      release(loggedIn)
      release(notLoggedIn)
      release(awaitingReply)

      Behaviors.stopped
  }
}
object InstanceNegotiator {

  def apply(
      reservationId: Long,
      asked: Seq[ActorRef[Instance.Command]],
      replyTo: ActorRef[InstanceHandler.ReservationReply]
  ): Behavior[Command] =
    Behaviors.setup(ctx => new InstanceNegotiator(ctx, reservationId, asked, replyTo))

  sealed trait Command

  case class InstanceReservationStatus(
      instance: ActorRef[Instance.Command],
      status: ReservationStatus
  ) extends Command

  case object CancelReservation extends Command

  sealed trait ReservationStatus
  object ReservationStatus {
    case object IsPreferred         extends ReservationStatus
    case object LoggedIn            extends ReservationStatus
    case object NotLoggedIn         extends ReservationStatus
    case object AlreadyServingGuild extends ReservationStatus
  }
}
