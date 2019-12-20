package miko.web.views

import miko.MikoConfig
import miko.web.layouts.MikoBundle._

object entry {

  def apply()(implicit config: MikoConfig, req: RequestHeader) = {
    def item(title: String, href: String, button: String) =
      column(
        <.h2(^.cls := "title", is._5, title),
        <.a(^.cls := "button", is.primary, ^.href := href, button)
      )

    val content = <.section(^.cls := "hero is-fullheight")(
      <.div(^.cls := "hero-body")(
        container(^.cls := "has-text-centered")(
          column(is._6, is.offset._3)(
            <.h1(^.cls := "title", is._2, "Miko Web Interface"),
            <.div(^.cls := "box")(
              columns(
                item("Login and start using the web interface", routes.WebController.codeGrant().absoluteURL(), "Login"),
                item(
                  "Invite Miko to a new guild",
                  s"https://discordapp.com/api/oauth2/authorize?client_id=${config.clientId}&scope=bot&permissions=335670360",
                  "Invite Miko"
                ),
                item("Try the web interface", routes.GuestController.guildHome().absoluteURL(), "Guest mode")
              )
            )
          )
        )
      )
    )

    miko.web.layouts.base("Miko", (), content, ())
  }
}
