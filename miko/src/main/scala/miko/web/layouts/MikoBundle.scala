package miko.web.layouts

import controllers.ReverseAssets
import miko.web.controllers.{ReverseGuestController, ReverseWebController, javascript}
import play.api.mvc.Call
import scalatags._
import views.html.helper.CSPNonce

object MikoBundle extends Text.Cap with DataConverters with Text.Aggregate {

  object < extends Text.Cap with text.Tags with text.Tags2

  object ^ extends Text.Cap with Attrs with Styles

  val Assets: ReverseAssets = controllers.routes.Assets

  object routes {
    val WebController: ReverseWebController     = miko.web.controllers.routes.WebController
    val GuestController: ReverseGuestController = miko.web.controllers.routes.GuestController

    object js {
      val WebController: javascript.ReverseWebController     = miko.web.controllers.routes.javascript.WebController
      val GuestController: javascript.ReverseGuestController = miko.web.controllers.routes.javascript.GuestController
    }
  }

  type RequestHeader = play.api.mvc.RequestHeader
  type GuildViewInfo = miko.web.models.GuildViewInfo

  implicit class ModOps(private val mod: Modifier) extends AnyVal {

    def when(cond: Boolean): Modifier = if (cond) mod else ()

    def unless(cond: Boolean): Modifier = if (!cond) mod else ()
  }

  implicit class FragOps(private val frag: Frag) extends AnyVal {

    def when(cond: Boolean): Frag = if (cond) frag else ()

    def unless(cond: Boolean): Frag = if (!cond) frag else ()
  }

  def cspNonce(implicit req: RequestHeader): Modifier = CSPNonce.get.map(attr("nonce") := _).getOrElse(())

  def callHref(call: Call)(implicit req: RequestHeader): Modifier = ^.href := call.absoluteURL()

  val columns: Text.TypedTag[String] = <.div(^.cls := "columns")

  val column: Text.TypedTag[String] = <.div(^.cls := "column")

  val container: Text.TypedTag[String] = <.div(^.cls := "container")

  object is {
    private def create(num: Any): Modifier = ^.cls := s"is-$num"

    val _2: Modifier = create(2)
    val _3: Modifier = create(3)
    val _4: Modifier = create(4)
    val _5: Modifier = create(5)
    val _6: Modifier = create(6)
    val _7: Modifier = create(7)
    val _8: Modifier = create(8)

    val primary: Modifier  = create("primary")
    val active: Modifier   = create("active")
    val rounded: Modifier  = create("rounded")
    val expanded: Modifier = create("expanded")
    val gapless: Modifier  = create("gapless")
    val circle: Modifier   = create("circle")
    val large: Modifier    = create("large")
    val grouped: Modifier  = create("grouped")
    val multiple: Modifier = create("multiple")

    object offset {
      private def create(num: Int): Modifier = ^.cls := s"is-offset-$num"

      val _2: Modifier = create(2)
      val _3: Modifier = create(3)
      val _4: Modifier = create(4)
      val _5: Modifier = create(5)
      val _6: Modifier = create(6)
    }
  }

  val field: Text.TypedTag[String] = <.div(^.cls := "field")

  val control: Text.TypedTag[String] = <.div(^.cls := "control")

  def label(forA: String, lbl: String): Text.TypedTag[String] =
    <.label(^.cls := "label", ^.`for` := forA, lbl)

  def labelWithInput(lbl: String, input: Frag): Text.TypedTag[String] =
    <.label(^.cls := "label")(
      input,
      lbl
    )

  def checkbox(id: String, lbl: String, name: String, checked: Boolean): Text.TypedTag[String] =
    <.label(^.cls := "checkbox", ^.`for` := id)(
      <.input(^.id := id, ^.tpe := "checkbox", ^.name := name, ^.value := "true", ^.checked.when(checked)),
      lbl
    )
}
