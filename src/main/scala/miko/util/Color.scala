package miko.util

import scala.language.implicitConversions

case class Color(r: Int, g: Int, b: Int) {

  //https://stackoverflow.com/questions/3018313/algorithm-to-convert-rgb-to-hsv-and-hsv-to-rgb-in-range-0-255-for-both
  def toHSV: Color.ColorHSV = {
    val rd = r / 255d
    val gd = g / 255d
    val bd = b / 255d

    val min = math.min(rd, math.min(gd, bd))
    val max = math.max(rd, math.max(gd, bd))

    val value = max
    val delta = max - min

    if (delta < 0.00001) {
      Color.ColorHSV(0d, 0d, value)
    } else if (max <= 0) {
      Color.ColorHSV(Double.NaN, 0d, value)
    } else {
      val sat = delta / max

      val hue =
        if (rd >= max) (gd - bd) / delta
        else if (gd >= max) 2d + (bd - rd) / delta
        else 4d + (rd - gd) / delta

      val hueDegrees = hue * 60d

      val usedHue = if (hueDegrees < 0d) hueDegrees + 360d else hueDegrees

      Color.ColorHSV(usedHue, sat, value)
    }
  }

  def toInt: Int = (r << 16) | (g << 8) | b
}
object Color {

  case class ColorHSV(h: Double, s: Double, v: Double) {

    def toRGB: Color = {
      if (s <= 0) Color(v, v, v)
      else {
        val hh = if (h >= 360) 0 else h / 60
        val i  = hh.toLong
        val ff = hh - i

        val p = v * (1d - s)
        val q = v * (1d - (s * ff))
        val t = v * (1d - (s * (1 - ff)))

        i match {
          case 0 => Color(v, t, p)
          case 1 => Color(q, v, p)
          case 2 => Color(p, v, t)
          case 3 => Color(p, q, v)
          case 4 => Color(t, p, v)
          case _ => Color(v, p, q)
        }
      }
    }
  }

  def apply(r: Double, g: Double, b: Double): Color = Color((r * 255).toInt, (g * 255).toInt, (b * 255).toInt)

  def fromInt(rgb: Int): Color = Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)

  implicit def asInt(color: Color): Int = color.toInt

  final val Success = Color(0, 255, 0)
  final val Warning = Color(255, 255, 0)
  final val Failure = Color(255, 0, 0)
  final val Fatal   = Color(16, 16, 16)

  final val Red   = Color(255, 0, 0)
  final val Green = Color(0, 255, 0)

  final val Created = Green
  final val Updated = Color(0, 0, 255)
  final val Deleted = Red

  val forVolume: Int => Color =
    gradient(
      50,
      150,
      Seq(50 -> Color(0, 0, 255), 100 -> Color(0, 255, 0), 150 -> Color(255, 0, 0)),
      GradientType.HSV.CounterClockwise
    )

  private def clamp(min: Double, max: Double, value: Double): Double =
    if (value < min) min
    else if (value > max) max
    else value

  def factor(min: Double, max: Double, value: Double): Double = {
    require(min < max)
    val newMax   = max - min
    val newValue = value - min
    val clamped  = clamp(0, newMax, newValue)

    clamped.toDouble / newMax.toDouble
  }

  private def linear(from: Int, to: Int, factor: Double): Int          = from + ((to - from) * factor).toInt
  private def linear(from: Double, to: Double, factor: Double): Double = from + ((to - from) * factor)

  //TODO: More testing needed
  private def rotateHue(from: Double, to: Double, factor: Double, clockwise: Boolean): Double = {
    val usedDir = if (from < to) clockwise else !clockwise
    val res     = linear(if (usedDir) from else from - 360d, to, factor)
    if (res < 0) res + 360d else res
  }

  def gradient(min: Int, max: Int, breakpoints: Seq[(Int, Color)], tpe: GradientType): Int => Color = {
    require(breakpoints.sizeIs >= 2)
    val breakpointsFactor =
      breakpoints.map(t => factor(min, max, t._1) -> t._2).sortBy(_._1)(Ordering.Double.IeeeOrdering)

    value: Int => {
      val searchFactor      = factor(min, max, value)
      val (smaller, bigger) = breakpointsFactor.span(_._1 < searchFactor)

      if (smaller.isEmpty) bigger.head._2
      else if (bigger.isEmpty) smaller.head._2
      else {
        tpe match {
          case GradientType.RGB.Linear =>
            val (facFrom, from) = smaller.last
            val (facTo, to)     = bigger.head
            val colorFactor     = factor(facFrom, facTo, searchFactor)

            Color(
              linear(from.r, to.r, colorFactor),
              linear(from.g, to.g, colorFactor),
              linear(from.r, to.r, colorFactor)
            )

          case GradientType.HSV.Clockwise | GradientType.HSV.CounterClockwise =>
            val (facFrom, fromRGB) = smaller.last
            val (facTo, toRGB)     = bigger.head
            val colorFactor        = factor(facFrom, facTo, searchFactor)
            val from               = fromRGB.toHSV
            val to                 = toRGB.toHSV

            ColorHSV(
              rotateHue(from.h, to.h, colorFactor, tpe == GradientType.HSV.Clockwise),
              linear(from.s, to.s, colorFactor),
              linear(from.v, to.v, colorFactor)
            ).toRGB
        }
      }
    }
  }

  sealed trait ColorSpace
  object ColorSpace {
    case object RGB extends ColorSpace
  }

  sealed trait GradientType
  object GradientType {
    object RGB {
      case object Linear extends GradientType
    }

    object HSV {
      case object Clockwise        extends GradientType
      case object CounterClockwise extends GradientType
    }
  }
}
