package miko.web.layouts

import MikoBundle._

object mainPlain {

  def apply(title: String)(content: Frag)(implicit req: RequestHeader): String = {

    val navbar: navbar = new navbar() {
      override protected val brandMikoLogo: Frag = <.div(^.cls := "navbar-item", mikoLogo)

      override protected val startItems: Seq[Frag] = Nil

      override protected val endItems: Seq[Frag] = Seq(
        navAnchor(miko.web.controllers.routes.WebController.logout(), "Logout")
      )
    }

    val heroContent =
      <.section(^.cls := "hero")(
        <.div(^.cls := "hero-head", navbar.navbar),
        <.div(^.cls := "hero-body", content)
      )

    miko.web.layouts.base(title, (), heroContent, ())
  }
}
