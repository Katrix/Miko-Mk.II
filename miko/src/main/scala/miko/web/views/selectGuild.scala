package miko.web.views

import ackcord.data.Guild
import miko.web.layouts.MikoBundle._
import miko.web.models.ViewInfo

import scala.util.Random

object selectGuild {

  private def guildImgSrc(guild: Guild) = {
    val guildId = guild.id
    val iconUrl = guild.icon.map(hash => s"https://cdn.discordapp.com/icons/$guildId/$hash.png?size=128")
    val default = s"https://cdn.discordapp.com/embed/avatars/${Math.abs(Random.nextInt() % 5)}.png?size=128"

    iconUrl.getOrElse(default)
  }

  def apply()(implicit info: ViewInfo, req: RequestHeader): String =
    miko.web.layouts.mainPlain("Select Guild - Miko")(
      container(
        column(is._6, is.offset._3)(
          <.h1(^.cls := "title", is._2, "Select a guild"),
          <.div(^.cls := "box")(
            <.div(^.cls := "scrolling-wrapper-x")(
              info.cache.guildMap.values.toSeq.sortBy(_.name).map { guild =>
                <.figure(^.cls := "image scroll-card", ^.widthA := 128.px)(
                  <.a(callHref(routes.WebController.guildHome(guild.id.asString)))(
                    <.img(
                      is.rounded,
                      ^.alt := guild.name,
                      ^.widthA := 128,
                      ^.heightA := 128,
                      ^.height := 128.px,
                      ^.src := guildImgSrc(guild)
                    )
                  ),
                  <.figcaption(guild.name)
                )
              }
            ),
            field(
              label(forA = "guildSearch", "Search"),
              control(is.expanded)(
                <.input(^.tpe := "text", ^.cls := "input", ^.placeholder := "Search...")
              )
            )
          )
        )
      )
    )
}
