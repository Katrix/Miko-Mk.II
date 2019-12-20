package miko.slaves

import ackcord.Cache
import akka.actor.typed.scaladsl._

class Master(ctx: ActorContext[AbstractSlave.Command], timers: TimerScheduler[AbstractSlave.Command], val cache: Cache)
    extends AbstractSlave(ctx, timers) {
  import AbstractSlave._

  override def stopSlave(): Unit = context.self ! StopNow

  override def userAdded(): Unit = ()

  override def userRemoved(): Unit = ()

  override def bestStatus: SlaveNegotiator.ReservationStatus = SlaveNegotiator.ReservationStatus.IsPreferred
}
