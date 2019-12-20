package miko.web.views

import miko.web.layouts.MikoBundle._

object guildHome {

  def apply()(implicit info: GuildViewInfo, request: RequestHeader): String = miko.web.layouts.main("Guild Home - Miko")(())
}
