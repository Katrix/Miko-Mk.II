package miko.slaves

import ackcord.gateway.GatewaySettings
import ackcord.{Cache, DiscordShard}
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.http.scaladsl.model.Uri
import miko.slaves.AbstractSlave.StopNow

class Slave(
    ctx: ActorContext[AbstractSlave.Command],
    timers: TimerScheduler[AbstractSlave.Command],
    wsUri: Uri,
    token: String
) extends AbstractSlave(ctx, timers) {

  val cache: Cache                          = Cache.create
  var shard: ActorRef[DiscordShard.Command] = _

  def logInIfNeeded(): Unit =
    if (shard == null) {
      shard =
        context.spawn(DiscordShard(wsUri, GatewaySettings(token, guildSubscriptions = false), cache), "SlaveShard")
      shard ! DiscordShard.StartShard
    }

  def shutdownIfUnused(): Unit =
    if (users.isEmpty) {
      shard ! DiscordShard.StopShard
      shard = null
    }

  override def stopSlave(): Unit = {
    if (shard != null) {
      context.watchWith(shard, StopNow)
      shard ! DiscordShard.StopShard
    } else {
      context.self ! StopNow
    }
  }

  override def userAdded(): Unit = logInIfNeeded()

  override def userRemoved(): Unit = shutdownIfUnused()

  override def bestStatus: SlaveNegotiator.ReservationStatus = SlaveNegotiator.ReservationStatus.LoggedIn
}
