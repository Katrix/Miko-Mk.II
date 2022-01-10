package miko.instances

import ackcord.gateway.GatewaySettings
import ackcord.{Cache, DiscordShard}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri
import miko.instances.Instance.StopNow

class Secondary(
    ctx: ActorContext[Instance.Command],
    timers: TimerScheduler[Instance.Command],
    wsUri: Uri,
    token: String
) extends Instance(ctx, timers) {

  val cache: Cache                          = Cache.create()
  var shard: ActorRef[DiscordShard.Command] = _

  def logInIfNeeded(): Unit =
    if (shard == null) {
      shard = context.spawn(DiscordShard(wsUri, GatewaySettings(token), cache), "SecondaryShard")
      shard ! DiscordShard.StartShard
    }

  def shutdownIfUnused(): Unit =
    if (users.isEmpty) {
      shard ! DiscordShard.StopShard
      shard = null
    }

  override def stopInstance(): Unit = {
    if (shard != null) {
      context.watchWith(shard, StopNow)
      shard ! DiscordShard.StopShard
    } else {
      context.self ! StopNow
    }
  }

  override def userAdded(): Unit = logInIfNeeded()

  override def userRemoved(): Unit = shutdownIfUnused()

  override def bestStatus: InstanceNegotiator.ReservationStatus =
    if (shard != null) InstanceNegotiator.ReservationStatus.LoggedIn
    else InstanceNegotiator.ReservationStatus.NotLoggedIn
}
