package miko.web.layouts

import MikoBundle._
import play.api.mvc.Call

object mainBase {

  def apply(title: String, isFullHeight: Boolean = false, removePadding: Boolean = false)(header: Frag)(content: Frag)(
      footer: Frag
  )(implicit info: GuildViewInfo, request: RequestHeader): String = {
    val strGuildId = info.guildId.asString

    def webOrGuest[A](web: A, guest: A) = if (info.isGuest) guest else web

    //noinspection TypeAnnotation
    val navbar = new navbar() {
      def navAnchor2(webHref: Call, guestHref: Call, content: Modifier): Frag =
        navAnchor(webOrGuest(webHref, guestHref), content)

      override protected def brandMikoLogo: Frag =
        navAnchor2(routes.WebController.guildHome(strGuildId), routes.GuestController.guildHome(), mikoLogo)

      override protected def startItems: Seq[Frag] = Seq(
        navAnchor2(routes.WebController.guildHome(strGuildId), routes.GuestController.guildHome(), "Home"),
        navAnchor2(routes.WebController.help(strGuildId), routes.GuestController.help(), "Help"),
        navAnchor2(routes.WebController.music(strGuildId), routes.GuestController.music(), "Music")
          .when(info.userInVChannel),
        frag(
          navAnchor2(routes.WebController.settings(strGuildId), routes.GuestController.settings(), "Settings"),
          navAnchor2(routes.WebController.log(strGuildId), routes.GuestController.log(), "Logs")
        ).when(info.isAdmin)
      )

      override protected def endItems: Seq[Frag] =
        if (!info.isGuest) {
          Seq(
            navAnchor2(routes.WebController.selectGuild(), routes.WebController.selectGuild(), "All guilds"),
            navAnchor2(routes.WebController.logout(), routes.WebController.logout(), "Logout")
          )
        } else {
          Seq(navAnchor2(routes.WebController.entry(), routes.WebController.entry(), "Back to Start"))
        }
    }

    val body = frag(
      <.section(
        ^.cls := "hero",
        modifier(^.cls := "is-fullheight-with-navbar", ^.height := "calc(100vh - 4rem)").when(isFullHeight),
        <.div(^.cls := "hero-head", header),
        <.div(^.cls := "hero-body", (^.padding := 0).when(removePadding), content),
        <.div(^.cls := "hero-foot", footer)
      ),
      <.script(
        cspNonce,
        raw(s"""GUILD_ID = "${info.guildId.toLong}";""")
      )
    )

    miko.web.layouts.base(title, navbar.navbar, body, ())
  }
}
