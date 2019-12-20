package miko.web.layouts

import MikoBundle._

object base {
  def apply(title: String, header: Frag, body: Frag, footer: Frag)(
      implicit req: RequestHeader
  ): String = {
    "<!DOCTYPE html>" + <.html(
      <.head(
        <.meta(^.charset := "utf-8"),
        <.meta(^.name := "viewport", ^.content := "width=device-width, initial-scale=1"),
        <.title(title),
        <.link(^.rel := "stylesheet", callHref(Assets.versioned("stylesheets/main.css"))),
        <.script(^.defer, ^.src := "https://use.fontawesome.com/releases/v5.0.7/js/all.js")
      ),
      <.body(
        header,
        body,
        footer,
        raw(
          views.html.helper
            .javascriptRouter("jsRoutes")(
              routes.js.WebController.entry,
              routes.js.WebController.selectGuild,
              routes.js.WebController.guildHome,
              routes.js.WebController.help,
              routes.js.WebController.music,
              routes.js.WebController.settings,
              routes.js.WebController.log,
              routes.js.GuestController.guildHome,
              routes.js.GuestController.help,
              routes.js.GuestController.music,
              routes.js.GuestController.settings,
              routes.js.GuestController.log
            )
            .body
        ),
        raw(
          scalajs.html
            .scripts(
              "miko-web-js",
              Assets.versioned(_).toString,
              name => getClass.getResource(s"/public/$name") != null
            )
            .body
        )
      )
    )
  }
}
