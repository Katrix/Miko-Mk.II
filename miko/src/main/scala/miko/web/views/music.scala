package miko.web.views

import akka.http.scaladsl.model.Uri
import miko.services.{MusicData, MusicEntry}
import miko.web.layouts.MikoBundle._

object music {

  private val fullsize = ^.cls := "fullsize"

  //noinspection TypeAnnotation
  private object player {
    trait PlaylistMixin {
      val item = ^.cls := "player-playlist-item"
    }

    val playlist = new AttrPair(^.cls, "player-playlist", stringAttr) with PlaylistMixin

    val bar  = ^.cls := "player-bar"
    val item = ^.cls := "player-item"
  }

  private val levelItem = <.div(^.cls := "level-item")

  private def barIcon(id: String, ariaLabel: String, icon: String, title: Option[Modifier] = None) = <.span(
    ^.cls := "icon",
    is.large,
    ^.id := id,
    ^.aria.label := ariaLabel,
    ^.role := "button",
    <.i(^.cls := s"fas fa-lg $icon", title)
  )

  private def barSlider(name: String, id: String, value: Int) = frag(
    <.input(
      ^.tpe := "range",
      ^.cls := "slider has-output",
      is.circle,
      ^.name := name,
      ^.id := id,
      ^.onchange := "",
      ^.min := 50,
      ^.max := 150,
      ^.step := 1,
      ^.value := value
    ),
    <.output(
      ^.`for` := id,
      ^.data.prefix := s"$name: ",
      s"$name: $value"
    )
  )

  def apply(musicData: MusicData)(implicit info: GuildViewInfo, request: RequestHeader): String = {
    def isIdxActive(idx: Int) =
      musicData.currentlyPlaying.getOrElse(-1) == idx

    def ytUrl(id: String, highQuality: Boolean) = {
      if (highQuality) {
        s"https://img.youtube.com/vi/$id/0.jpg"
      } else {
        s"https://img.youtube.com/vi/$id/mqdefault.jpg"
      }
    }

    def imgUrl(url: String, highQuality: Boolean = false) = {
      val uri = Uri(url)
      uri.authority.host.toString match {
        case "www.youtube.com" | "youtube.com" =>
          uri.query().get("v").map(ytUrl(_, highQuality)).getOrElse {
            println(uri)
            ""
          }
        case "youtu.be" => ytUrl(uri.path.toString(), highQuality)
        case _ =>
          println(uri.authority.host.toString())
          ""
      }
    }

    def formatDuration(seconds: Long) = {
      if (seconds > 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
      } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
      }
    }

    def playingMaxPos =
      musicData.currentlyPlaying.fold(1L)(musicData.playlist(_).durationSec)

    def playingCurrentPos =
      musicData.currentlyPlaying.fold(1L)(_ => musicData.state.currentPosition)

    def entryContent(url: String, name: String, durationSec: Long, isSeekable: Boolean) =
      <.article(^.cls := "media")(
        <.figure(^.cls := "media-left image is-72x128")(
          <.img(^.src := imgUrl(url), ^.alt := name)
        ),
        <.div(^.cls := "media-content has-text-light")(
          <.strong(^.cls := "has-text-white", name),
          <.br,
          <.small(url),
          <.br,
          if (isSeekable) formatDuration(durationSec) else "Radio"
        ),
        <.div(^.cls := "media-right")(
          <.button(^.cls := "delete", ^.aria.label := "remove entry")
        )
      )

    miko.web.layouts.mainBase("Music - Miko", isFullHeight = true, removePadding = true)(())(
      columns(is.gapless, fullsize)(
        column(
          is._8,
          ^.cls := "music-playerimg",
          ^.backgroundImage := s"url(${musicData.currentlyPlaying
            .fold("")(idx => imgUrl(musicData.playlist(idx).url, highQuality = true))})"
        ),
        column(^.cls := "has-background-dark")(
          <.div(
            ^.id := "playerPlaylist",
            player.playlist,
            ^.height := "calc(89vh - 4rem);",
            for ((MusicEntry(id, url, name, durationSec, isSeekable), idx) <- musicData.playlist.zipWithIndex) yield {
              <.div(
                ^.id := s"playerContent$id",
                player.playlist.item,
                (^.cls := "has-background-black-ter").when(isIdxActive(idx)),
                entryContent(url, name, durationSec, isSeekable)
              )
            }
          )
        )
      )
    )(
      <.div(player.bar)(
        <.div(player.item)(
          <.progress(
            ^.id := "musicProgress",
            ^.cls := "progress",
            is.primary,
            ^.value := playingCurrentPos,
            ^.max := playingMaxPos
          )
        ),
        <.div(^.cls := "level", player.item)(
          levelItem(barSlider("Default Volume", "musicPlayingDefaultVolume", musicData.state.defVolume)),
          levelItem(barIcon("musicStepBackward", "step backward", "fa-step-backward")),
          levelItem(
            barIcon("musicPlay", "play music", "fa-play", Some(^.title := "Play")),
            barIcon("musicPause", "pause music", "fa-pause", Some(^.title := "Pause")),
            <.div(^.cls := "time")(
              <.span(^.id := "currentTime", ^.cls := "current-time", formatDuration(playingCurrentPos)),
              <.span(^.cls := "seperator-time", " / "),
              <.span(^.id := "totalTime", ^.cls := "total-time", formatDuration(playingMaxPos))
            )
          ),
          levelItem(barIcon("musicStepForward", "step forward", "fa-step-forward")),
          levelItem(barSlider("Current volume", "musicPlayingCurrentVolume", musicData.state.volume))
        )
      )
    )
  }
}
