package miko.web.layouts

import MikoBundle._
import play.api.mvc.Call

abstract class navbar(implicit req: RequestHeader) {

  protected val mikoLogo: Frag =
    <.img(
      ^.cls := "img-circle",
      ^.src := Assets.versioned("images/logo.png").absoluteURL(),
      ^.alt := "Miko logo",
      ^.widthA := 48,
      ^.heightA := 48
    )

  protected def brandMikoLogo: Frag

  protected def navAnchor(href: Call, content: Modifier): Frag =
    <.a(^.cls := "navbar-item", callHref(href), content)

  protected def navbarBrand: Frag =
    <.div(^.cls := "navbar-brand")(
      brandMikoLogo,
      //TODO Needs javascript https://bulma.io/documentation/components/navbar/#navbarJsExample
      <.a(
        ^.role := "button",
        ^.cls := "navbar-burger",
        ^.aria.label := "menu",
        ^.aria.expanded := false,
        (0 until 3).map(_ => <.span(^.aria.hidden := true))
      )
    )

  protected def startItems: Seq[Frag]

  protected def endItems: Seq[Frag]

  lazy val navbar: Frag =
    <.nav(^.cls := "navbar is-primary", ^.role := "navigation", ^.aria.label := "main navigation")(
      navbarBrand,
      <.div(^.cls := "navbar-menu")(
        <.div(^.cls := "navbar-start", startItems).when(startItems.nonEmpty),
        <.div(^.cls := "navbar-end", endItems).when(endItems.nonEmpty)
      )
    )

}
