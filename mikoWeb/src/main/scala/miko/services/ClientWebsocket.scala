package miko.services

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js.JSON

import org.scalajs.dom.raw.WebSocket

import io.circe._
import io.circe.syntax._
import miko.MikoProtocol

object ClientWebsocket extends MikoProtocol {

  /*
  private var socket: WebSocket = _
  private val messageQueue = mutable.Queue.empty[String]
  private val promises     = mutable.LongMap.empty[Promise[_]]
  private var id: Long = 0

  def start(): Unit = {
    println(websocketUri)
    socket = new WebSocket(websocketUri)
    socket.onopen = _ => {
      messageQueue.dequeueAll(_ => true).foreach(socket.send(_))
    }

    socket.onclose = e => {
      println(s"WS Closed Clean:${e.wasClean} Reason:${e.reason} Code:${e.code} Rest:${JSON.stringify(e)}") //TODO
    }

    socket.onerror = e => {
      println(s"WS Error ${JSON.stringify(e)}") //TODO
    }

    socket.onmessage = event => {
      parser.parse(event.data.asInstanceOf[String]).flatMap(_.as[ServerMessage]) match {
        case Right(msg: ResponseMessage[_]) =>
          promises.get(msg.id) match {
            case Some(promise) => promise.asInstanceOf[Promise[Any]].success(msg.data)
            case None          => ???
          }

        case Right(KeepAlive) => //Ignore
        case Right(InvalidRequestResponse(invalidId, message)) =>
          promises.get(invalidId) match {
            case Some(promise) => promise.failure(new Exception(message))
            case None => ???
          }

        case Right(msg) => ???
        case Left(e)    => e.printStackTrace()
      }
    }
  }

  def sendRequest(message: ClientMessage): Unit =
    sendStringMsg(message.asJson.noSpaces)

  def sendMsgExpectResponse[Response <: ResponseMessage[D], D](
      message: Long => ClientWithResponseMessage[Response, D]
  ): Future[D] = {
    val createdMsg: ClientMessage = message(id)
    val promise = Promise[D]
    promises.put(id, promise)
    id += 1

    sendStringMsg(createdMsg.asJson.noSpaces)
    promise.future
  }

  private def sendStringMsg(msg: String) = {
    require(socket != null, "WebSocket not started yet")

    if (socket.readyState == WebSocket.OPEN) {
      socket.send(msg)
    } else {
      messageQueue += msg
    }
  }

  private def websocketUri: String = {
    val location = org.scalajs.dom.document.location
    val wsProtocol = if (location.protocol == "https:") "wss" else "ws"

    s"$wsProtocol://${location.host}/server/ws"
  }
  */
}
