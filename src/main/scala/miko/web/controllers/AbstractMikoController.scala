package miko.web.controllers

import ackcord.data.{GuildMember, User}
import ackcord.{CacheSnapshot, Requests}
import akka.actor.typed.ActorRef
import miko.CacheStorage
import miko.util.SGFCPool
import play.api.http.HttpErrorHandler
import play.api.mvc.AbstractController
import scalacache.Cache
import zio.ZEnv

class AbstractMikoController(components: MikoControllerComponents)
    extends AbstractController(components)
    with MikoBaseController {
  override def cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]] = components.cacheStorage

  override def requests: Requests = components.requests

  implicit override def memberCache: Cache[(User, GuildMember)] = components.memberCache

  override def errorHandler: HttpErrorHandler = components.errorHandler

  override def zioRuntime: zio.Runtime[ZEnv] = components.runtime
}
