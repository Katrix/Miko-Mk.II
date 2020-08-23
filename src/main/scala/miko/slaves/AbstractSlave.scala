package miko.slaves

import ackcord.Cache
import ackcord.data.GuildId
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri

import scala.collection.mutable
import scala.concurrent.duration._

abstract class AbstractSlave(
    ctx: ActorContext[AbstractSlave.Command],
    timers: TimerScheduler[AbstractSlave.Command]
) extends AbstractBehavior[AbstractSlave.Command](ctx) {
  import AbstractSlave._

  implicit val system: ActorSystem[Nothing] = ctx.system

  def cache: Cache

  protected val reservationIdToGuildId = mutable.HashMap.empty[Long, GuildId]

  protected val users        = mutable.HashSet.empty[GuildId]
  protected val reservations = mutable.HashMap.empty[GuildId, ActorRef[SlaveHandler.ReservationReply]]

  def stopSlave(): Unit

  def userAdded(): Unit

  def userRemoved(): Unit

  def bestStatus: SlaveNegotiator.ReservationStatus

  override def onMessage(msg: Command): Behavior[Command] = {
    var shouldStop = false

    msg match {
      case Shutdown =>
        stopSlave()

      case StopNow =>
        shouldStop = true

      case RequestReservation(guildId, reservationId, replyTo, negotiator) =>
        if (users.contains(guildId) || reservations.contains(guildId)) {
          negotiator ! SlaveNegotiator.SlaveReservationStatus(
            context.self,
            SlaveNegotiator.ReservationStatus.AlreadyServingGuild
          )
        } else {
          reservationIdToGuildId.put(reservationId, guildId)
          reservations.put(guildId, replyTo)

          val status =
            if (users.nonEmpty) bestStatus
            else SlaveNegotiator.ReservationStatus.NotLoggedIn
          negotiator ! SlaveNegotiator.SlaveReservationStatus(context.self, status)
        }

      case ReservationHandled(reservationId) =>
        reservations.remove(reservationIdToGuildId(reservationId))

      case CompleteReservation(reservationId) =>
        val guildId = reservationIdToGuildId(reservationId)

        reservations.remove(guildId).foreach { replyTo =>
          users.add(guildId)

          userAdded()
          replyTo ! SlaveHandler.SendLifetime(reservationId, ctx.self, cache)
          timers.startSingleTimer(reservationId, GuildUserDone(guildId), 1.minute)
        }

      case SetLifetime(reservationId, lifetime) =>
        timers.cancel(reservationId)
        context.watchWith(lifetime, GuildUserDone(reservationIdToGuildId(reservationId)))

      case ReleaseReservation(reservationId) =>
        reservations.remove(reservationIdToGuildId(reservationId))

      case GuildUserDone(guildId) =>
        users.remove(guildId)

        userRemoved()
    }

    if (shouldStop) Behaviors.stopped else Behaviors.same
  }
}
object AbstractSlave {

  def slave(wsUri: Uri, token: String): Behavior[Command] =
    Behaviors.setup(ctx => Behaviors.withTimers(timers => new Slave(ctx, timers, wsUri, token)))

  def master(cache: Cache): Behavior[Command] =
    Behaviors.setup(ctx => Behaviors.withTimers(timers => new Master(ctx, timers, cache)))

  sealed trait Command
  case object Shutdown                extends Command
  private[slaves] case object StopNow extends Command

  case class RequestReservation(
      guildId: GuildId,
      reservationId: Long,
      replyTo: ActorRef[SlaveHandler.ReservationReply],
      negotiator: ActorRef[SlaveNegotiator.Command]
  ) extends Command

  private case class GuildUserDone(guildId: GuildId) extends Command

  case class ReservationHandled(reservationId: Long)                 extends Command
  case class CompleteReservation(reservationId: Long)                extends Command
  case class SetLifetime(reservationId: Long, lifetime: ActorRef[_]) extends Command

  case class ReleaseReservation(reservationId: Long) extends Command
}
