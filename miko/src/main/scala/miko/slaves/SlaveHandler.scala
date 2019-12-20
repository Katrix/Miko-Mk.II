package miko.slaves

import ackcord.Cache
import ackcord.data.GuildId
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri
import miko.MikoConfig
import org.slf4j.Logger

import scala.collection.mutable

class SlaveHandler(ctx: ActorContext[SlaveHandler.Command], topCache: Cache, wsUri: Uri)
    extends AbstractBehavior[SlaveHandler.Command](ctx) {
  import SlaveHandler._
  val log: Logger = context.log

  private val slaveTokens = MikoConfig()(context.system).slaveTokens
  private val slaves = slaveTokens.zipWithIndex.map {
    case (token, idx) =>
      context.spawn(AbstractSlave.slave(wsUri, token), s"Slave-$idx")
  } :+ context.spawn(AbstractSlave.master(topCache), "Slave-Master")

  val guildReservationAttempt = mutable.HashMap.empty[Long, Seq[ActorRef[AbstractSlave.Command]]]
  val negotiators             = mutable.HashMap.empty[Long, ActorRef[SlaveNegotiator.Command]]
  val encounteredNonces       = mutable.HashSet.empty[Long]

  private var slaveShutdownCount = 0

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case Shutdown =>
        slaves.foreach { slave =>
          context.watch(slave)
          slave ! AbstractSlave.Shutdown
        }

      case ReserveSlave(reservationId, guildId, replyTo) =>
        if (!encounteredNonces.contains(reservationId)) {
          val negotiator = context.spawn(SlaveNegotiator(reservationId, slaves, replyTo), s"Negotiator$reservationId")
          context.watchWith(negotiator, RemoveNegotiator(reservationId))

          negotiators.put(reservationId, negotiator)
          encounteredNonces += reservationId

          slaves.foreach(_ ! AbstractSlave.RequestReservation(guildId, reservationId, replyTo, negotiator))
        }

      case CancelReservation(reservationId) =>
        negotiators.get(reservationId).foreach(_ ! SlaveNegotiator.CancelReservation)

      case RemoveNegotiator(reservationId) =>
        negotiators.remove(reservationId)
    }

    Behaviors.same
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case Terminated(_) =>
      slaveShutdownCount += 1
      if (slaveShutdownCount == slaves.size) Behaviors.stopped else Behaviors.same
  }
}
object SlaveHandler {
  def apply(topCache: Cache, wsUri: Uri): Behavior[Command] =
    Behaviors.setup(ctx => new SlaveHandler(ctx, topCache, wsUri))

  sealed trait Command
  case object Shutdown                                                                                extends Command
  case class ReserveSlave(reservationId: Long, guildId: GuildId, replyTo: ActorRef[ReservationReply]) extends Command
  case class CancelReservation(reservationId: Long)                                                   extends Command

  private case class RemoveNegotiator(reservationId: Long) extends Command

  sealed trait ReservationReply
  case class SendLifetime(reservationId: Long, replyTo: ActorRef[AbstractSlave.SetLifetime], slaveCache: Cache)
      extends ReservationReply
  case class ReservationFailed(reservationId: Long) extends ReservationReply
}
