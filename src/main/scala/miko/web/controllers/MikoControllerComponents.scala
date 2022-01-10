package miko.web.controllers

import ackcord.data.{GuildMember, User, UserId}
import ackcord.{CacheSnapshot, Requests}
import akka.actor.typed.ActorRef
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import miko.CacheStorage
import miko.util.SGFCPool
import play.api.http.{FileMimeTypes, HttpErrorHandler}
import play.api.i18n.{Langs, MessagesApi}
import play.api.mvc._

import scala.concurrent.ExecutionContext

case class MikoControllerComponents(
    cacheStorage: ActorRef[SGFCPool.Msg[CacheStorage.Command, CacheSnapshot]],
    requests: Requests,
    memberCache: scalacache.Cache[IO, UserId, (User, GuildMember)],
    errorHandler: HttpErrorHandler,
    ioRuntime: IORuntime,
    base: ControllerComponents
) extends ControllerComponents {
  override def actionBuilder: ActionBuilder[Request, AnyContent] = base.actionBuilder
  override def parsers: PlayBodyParsers                          = base.parsers
  override def messagesApi: MessagesApi                          = base.messagesApi
  override def langs: Langs                                      = base.langs
  override def fileMimeTypes: FileMimeTypes                      = base.fileMimeTypes
  override def executionContext: ExecutionContext                = base.executionContext
}
