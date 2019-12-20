package miko

import scala.scalajs.js
import Implicits._

import scala.scalajs.js.annotation.JSGlobal

@js.native
trait MikoJsRouter extends js.Object {

  val miko: MikoJsRouterMiko = js.native
}

@js.native
trait MikoJsRouterMiko extends js.Object {

  val web: MikoJsRouterWeb = js.native
}

@js.native
trait MikoJsRouterWeb extends js.Object {

  val controllers: MikoJsRouterControllers = js.native
}

@js.native
trait MikoJsRouterControllers extends js.Object {

  val WebController: WebControllerJs     = js.native
  val GuestController: GuestControllerJs = js.native
}

@js.native
trait WebControllerJs extends js.Object {

  def entry(): JsRoute                      = js.native
  def selectGuild(): JsRoute                = js.native
  def guildHome(guildId: String): JsRoute = js.native
  def help(guildId: String): JsRoute      = js.native
  def music(guildId: String): JsRoute     = js.native
  def settings(guildId: String): JsRoute  = js.native
  def log(guildId: String): JsRoute       = js.native
}

@js.native
trait GuestControllerJs extends js.Object {

  def guildHome(): JsRoute = js.native
  def help(): JsRoute      = js.native
  def music(): JsRoute     = js.native
  def settings(): JsRoute  = js.native
  def log(): JsRoute       = js.native
}

@js.native
trait JsRoute extends js.Object {

  val method: String       = js.native
  val `type`: String       = js.native
  val url: String          = js.native
  val absoluteURL: String  = js.native
  val webSocketURL: String = js.native
}
