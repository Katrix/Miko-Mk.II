package miko.instances

import ackcord.Cache
import akka.actor.typed.scaladsl._

class Primary(ctx: ActorContext[Instance.Command], timers: TimerScheduler[Instance.Command], val cache: Cache)
    extends Instance(ctx, timers) {
  import Instance._

  override def stopInstance(): Unit = context.self ! StopNow

  override def userAdded(): Unit = ()

  override def userRemoved(): Unit = ()

  override def bestStatus: InstanceNegotiator.ReservationStatus = InstanceNegotiator.ReservationStatus.IsPreferred
}
