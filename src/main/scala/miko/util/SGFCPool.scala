package miko.util

import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl._
import akka.util.Timeout
import org.slf4j.Logger

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

class SGFCPool[Command, OutsideCommand, Res](
    ctx: ActorContext[SGFCPool.Msg[OutsideCommand, Res]],
    poolSize: Int,
    within: FiniteDuration,
    behavior: Behavior[Command],
    mapper: SGFCPool.RouteeCommand[OutsideCommand, Res] => Command
) extends AbstractBehavior[SGFCPool.Msg[OutsideCommand, Res]](ctx) {
  import SGFCPool._
  import context.executionContext
  val log: Logger = context.log

  implicit val system: ActorSystem[Nothing] = context.system

  (1 to poolSize).foreach { _ =>
    val child = context.spawnAnonymous(behavior)
    context.watch(child)
  }

  private def children = context.children.asInstanceOf[Iterable[ActorRef[Command]]]

  override def onMessage(msg: Msg[OutsideCommand, Res]): Behavior[Msg[OutsideCommand, Res]] = {
    msg match {
      case Msg(replyTo, msg) =>
        implicit val timeout: Timeout = Timeout(within)

        val promise = Promise[Res]
        children.foreach { child =>
          promise.completeWith(child.ask[Res](act => mapper(RouteeCommand(act, msg))))
        }

        promise.future.foreach(replyTo ! _)
    }

    Behaviors.same
  }

  override def onSignal: PartialFunction[Signal, Behavior[Msg[OutsideCommand, Res]]] = {
    case Terminated(child) =>
      if (context.children.nonEmpty) {
        log.debug("Pool child stopped [{}]", child.path)
        Behaviors.same
      } else {
        log.info("Last pool child stopped, stopping pool [{}]", context.self.path)
        Behaviors.stopped
      }
  }
}
//ScatterGatherFirstCompletedPool
object SGFCPool {

  def apply[Command, OutsideCommand, Res](
      poolSize: Int,
      within: FiniteDuration,
      behavior: Behavior[Command],
      mapper: RouteeCommand[OutsideCommand, Res] => Command
  ): Behavior[Msg[OutsideCommand, Res]] =
    Behaviors.setup(ctx => new SGFCPool(ctx, poolSize, within, behavior, mapper))

  case class Msg[A, Res](replyTo: ActorRef[Res], msg: A)

  case class RouteeCommand[A, Res](replyTo: ActorRef[Res], msg: A)
}
