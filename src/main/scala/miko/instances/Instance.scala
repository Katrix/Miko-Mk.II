package miko.instances

import ackcord.Cache
import ackcord.data.GuildId
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri

import scala.collection.mutable
import scala.concurrent.duration._

abstract class Instance(
    ctx: ActorContext[Instance.Command],
    timers: TimerScheduler[Instance.Command]
) extends AbstractBehavior[Instance.Command](ctx) {
  import Instance._

  implicit val system: ActorSystem[Nothing] = ctx.system

  def cache: Cache

  protected val reservationIdToGuildId = mutable.HashMap.empty[Long, GuildId]

  protected val users        = mutable.HashSet.empty[GuildId]
  protected val reservations = mutable.HashMap.empty[GuildId, ActorRef[InstanceHandler.ReservationReply]]

  def stopInstance(): Unit

  def userAdded(): Unit

  def userRemoved(): Unit

  def bestStatus: InstanceNegotiator.ReservationStatus

  override def onMessage(msg: Command): Behavior[Command] = {
    var shouldStop = false

    msg match {
      case Shutdown =>
        stopInstance()

      case StopNow =>
        shouldStop = true

      case RequestReservation(guildId, reservationId, replyTo, negotiator) =>
        val status = if (users.contains(guildId) || reservations.contains(guildId)) {
          InstanceNegotiator.ReservationStatus.AlreadyServingGuild
        } else {
          reservationIdToGuildId.put(reservationId, guildId)
          reservations.put(guildId, replyTo)

          if (users.nonEmpty) bestStatus
          else InstanceNegotiator.ReservationStatus.NotLoggedIn
        }

        negotiator ! InstanceNegotiator.InstanceReservationStatus(context.self, status)

      case ReservationHandled(reservationId) =>
        reservations.remove(reservationIdToGuildId(reservationId))

      case CompleteReservation(reservationId) =>
        val guildId = reservationIdToGuildId(reservationId)

        reservations.remove(guildId).foreach { replyTo =>
          users.add(guildId)

          userAdded()
          replyTo ! InstanceHandler.SendLifetime(reservationId, ctx.self, cache)
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
object Instance {

  def secondary(wsUri: Uri, token: String): Behavior[Command] =
    Behaviors.setup(ctx => Behaviors.withTimers(timers => new Secondary(ctx, timers, wsUri, token)))

  def primary(cache: Cache): Behavior[Command] =
    Behaviors.setup(ctx => Behaviors.withTimers(timers => new Primary(ctx, timers, cache)))

  sealed trait Command
  case object Shutdown                   extends Command
  private[instances] case object StopNow extends Command

  case class RequestReservation(
      guildId: GuildId,
      reservationId: Long,
      replyTo: ActorRef[InstanceHandler.ReservationReply],
      negotiator: ActorRef[InstanceNegotiator.Command]
  ) extends Command

  private case class GuildUserDone(guildId: GuildId) extends Command

  case class ReservationHandled(reservationId: Long)                 extends Command
  case class CompleteReservation(reservationId: Long)                extends Command
  case class SetLifetime(reservationId: Long, lifetime: ActorRef[_]) extends Command

  case class ReleaseReservation(reservationId: Long) extends Command
}
