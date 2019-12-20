package miko

import scala.scalajs.js
import org.scalajs.dom.ext._
import org.scalajs.dom.{Element, Event, document, html, window}
import ackcord.data.{GuildId, RawSnowflake}
import miko.Implicits._
import org.scalajs.dom.raw.DOMTokenList

object MikoWeb {

  def main(args: Array[String]): Unit = {
    setupSliderOutput()
    val optGuildIdString = js.Dynamic.global.GUILD_ID.as[js.UndefOr[String]]
    val optGuildId       = optGuildIdString.map(str => GuildId(RawSnowflake(str)))
    val path             = window.location.pathname

    val router          = js.Dynamic.global.jsRoutes.as[MikoJsRouter]
    val controllers     = router.miko.web.controllers
    val WebController   = controllers.WebController
    val GuestController = controllers.GuestController

    optGuildId.toOption match {
      case Some(guildId) =>
        val guildIdString = guildId.asString

        path match {
          case p if p == WebController.entry().url                              =>
          case p if p == WebController.selectGuild().url                        =>
          case p if p == WebController.guildHome(guildIdString).url =>
          case p if p == WebController.help(guildIdString).url      =>
          case p if p == WebController.music(guildIdString).url     => pages.Music.setupMusic(guildId)
          case p if p == WebController.settings(guildIdString).url  =>
          case p if p == WebController.log(guildIdString).url       =>
          case p if p == GuestController.guildHome().url                        =>
          case p if p == GuestController.help().url                             =>
          case p if p == GuestController.music().url                            =>
          case p if p == GuestController.settings().url                         =>
          case p if p == GuestController.log().url                              =>
          case _                                                    =>
        }
      case None =>
        path match {
          case p if p == WebController.entry().url       =>
          case p if p == WebController.selectGuild().url =>
          case p if p == GuestController.guildHome().url =>
          case p if p == GuestController.help().url      =>
          case p if p == GuestController.music().url     =>
          case p if p == GuestController.settings().url  =>
          case p if p == GuestController.log().url       =>
          case _                             =>
        }
    }
  }

  def setupSliderOutput(): Unit = {
    document.addEventListener(
      "DOMContentLoaded",
      (_: Event) => {
        document.querySelectorAll("""input[type="range"].slider""").foreach {
          case slider: Element =>
            findOutputForSlider(slider).foreach {
              output =>
                if (slider.classList.contains("has-output-tooltip")) {
                  // Get new output position
                  val newPosition = getSliderOutputPosition(slider)

                  // Set output position
                  output.style.left = newPosition
                }

                slider.addEventListener(
                  "input",
                  (event: Event) => {
                    val targetElement = event.target.asInstanceOf[Element]
                    if (targetElement.classList.contains("has-output-tooltip")) {
                      output.style.left = getSliderOutputPosition(targetElement)
                    }

                    val prefix = output.dataset.getOrElse("prefix", "")
                    output.dyn.value = prefix + event.target.dyn.value
                  }
                )
            }
          case _ =>
        }
      }
    )
  }

  // Find output DOM associated to the DOM element passed as parameter
  private def findOutputForSlider(element: Element): Option[html.Element] = {
    val idVal   = element.id
    val outputs = document.getElementsByTagName("output")
    outputs.find(_.dyn.htmlFor.as[DOMTokenList].contains(idVal)).asInstanceOf[Option[html.Element]]
  }

  private def getSliderOutputPosition(slider: Element) = {
    val style = window.getComputedStyle(slider, null)
    // Measure width of range input
    val sliderWidth = style.getPropertyValue("width").toInt

    // Figure out placement percentage between left and right of input
    val minValue = Option(slider.getAttribute("min")).fold(0)(_.toInt)
    val newPoint = (slider.dyn.value.as[Int] - minValue) / (slider.getAttribute("max").toInt - minValue)

    // Prevent bubble from going beyond left or right (unsupported browsers)
    val newPlace = if (newPoint < 0) {
      0
    } else if (newPoint > 1) {
      sliderWidth
    } else {
      sliderWidth * newPoint
    }

    s"${newPlace}px"
  }
}
