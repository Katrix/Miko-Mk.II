package miko.slaves

import akka.actor.typed._
import akka.actor.typed.scaladsl._

import scala.collection.mutable

class SlaveNegotiator(
    ctx: ActorContext[SlaveNegotiator.Command],
    reservationId: Long,
    asked: Seq[ActorRef[AbstractSlave.Command]],
    replyTo: ActorRef[SlaveHandler.ReservationReply]
) extends AbstractBehavior[SlaveNegotiator.Command](ctx) {
  import SlaveNegotiator._

  private val awaitingReply = asked.to(mutable.Set)
  private val preferred     = mutable.Buffer.empty[ActorRef[AbstractSlave.Command]]
  private val loggedIn      = mutable.Buffer.empty[ActorRef[AbstractSlave.Command]]
  private val notLoggedIn   = mutable.Buffer.empty[ActorRef[AbstractSlave.Command]]

  def release(seq: collection.Iterable[ActorRef[AbstractSlave.Command]]): Unit =
    seq.foreach(_ ! AbstractSlave.ReservationHandled(reservationId))

  def announceWinner(): Unit = {
    def releaseAndSend(seq: mutable.Seq[ActorRef[AbstractSlave.Command]]): Unit =
      if (seq.nonEmpty) {
        release(seq.view.drop(1))
        seq.head ! AbstractSlave.CompleteReservation(reservationId)
      }

    if (preferred.nonEmpty || loggedIn.nonEmpty || notLoggedIn.nonEmpty) {
      releaseAndSend(preferred)
      releaseAndSend(loggedIn)
      releaseAndSend(notLoggedIn)
    } else {
      replyTo ! SlaveHandler.ReservationFailed(reservationId)
    }
  }

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case SlaveReservationStatus(slave, status) =>
      awaitingReply.remove(slave)

      status match {
        case ReservationStatus.IsPreferred =>
          if (preferred.isEmpty) {
            release(loggedIn)
            release(notLoggedIn)
          }
          preferred += slave

        case ReservationStatus.LoggedIn =>
          if (preferred.isEmpty) {
            if (loggedIn.isEmpty) {
              release(notLoggedIn)
            }

            loggedIn += slave
          } else {
            slave ! AbstractSlave.ReservationHandled(reservationId)
          }

        case ReservationStatus.NotLoggedIn =>
          if (preferred.isEmpty && loggedIn.isEmpty) {
            notLoggedIn += slave
          } else {
            slave ! AbstractSlave.ReservationHandled(reservationId)
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
object SlaveNegotiator {

  def apply(
      reservationId: Long,
      asked: Seq[ActorRef[AbstractSlave.Command]],
      replyTo: ActorRef[SlaveHandler.ReservationReply],
  ): Behavior[Command] =
    Behaviors.setup(ctx => new SlaveNegotiator(ctx, reservationId, asked, replyTo))

  sealed trait Command

  case class SlaveReservationStatus(
      slave: ActorRef[AbstractSlave.Command],
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
