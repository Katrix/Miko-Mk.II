package miko.web.views

import miko.services.{CommandData, MikoCmdCategory}
import miko.web.layouts.MikoBundle._

object help {

  private def menuCat(label: String, items: Seq[Frag]) = {
    frag(
      <.p(^.cls := "menu-label", label),
      <.ul(^.cls := "menu-list")(
        items.map(item => <.li(item))
      )
    )
  }

  private def isActive(cat: MikoCmdCategory) = cat.prefix == "!"

  private def menuItem(cat: MikoCmdCategory) =
    <.a(is.active.when(isActive(cat)), ^.href := s"#cmd${cat.prefix}", cat.description)

  def apply(commandData: CommandData)(implicit info: GuildViewInfo, request: RequestHeader): String =
    miko.web.layouts.main("Help - Miko")(
      columns(
        column(is._2)(
          <.aside(^.cls := "menu")(
            menuCat("Commands", commandData.commands.keys.toSeq.map(cat => menuItem(cat)))
          )
        ),
        column(
          container(
            <.h1(^.cls := "title", "Command help"),
            commandData.commands.toSeq.map {
              case (cat, commands) =>
                frag(
                  <.h2(^.id := s"cmd${cat.prefix}", ^.cls := "title", s"${cat.prefix} - ${cat.description}"),
                  <.table(^.cls := "table is-fullwidth is-hoverable")(
                    <.thead(
                      <.tr(
                        <.th(^.widthA := 120.px, "Name"),
                        <.th(^.widthA := 180.px, "Aliases"),
                        <.th(^.widthA := 250.px, "Usage"),
                        <.th("Description")
                      )
                    ),
                    <.tbody(
                      commands.toSeq.sortBy(_.name).map { command =>
                        <.tr(
                          <.th(command.name),
                          <.th(command.aliases.mkString("|")),
                          <.th(command.usage),
                          <.th(command.description)
                        )
                      }
                    )
                  )
                )
            }
          )
        )
      )
    )
}
