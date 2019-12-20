package miko.web.layouts

import scalatags.Text.all._
import miko.web.models.GuildViewInfo
import play.api.mvc.RequestHeader

object main {
  def apply(title: String, isFullHeight: Boolean = false, removePadding: Boolean = false)(
    content: Frag
  )(implicit info: GuildViewInfo, request: RequestHeader): String =
    mainBase(title, isFullHeight, removePadding)(())(content)(())
}
