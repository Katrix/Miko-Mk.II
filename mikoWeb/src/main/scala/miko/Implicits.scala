package miko

import scala.language.implicitConversions
import scala.scalajs.js

object Implicits {

  implicit class ToDynamic(val any: Any) extends AnyVal {

    def dyn: js.Dynamic = any.asInstanceOf[js.Dynamic]

    def truthValue: Boolean = js.DynamicImplicits.truthValue(any.dyn)
  }

  implicit class DynamicOps(val dyn: js.Dynamic) extends AnyVal {
    def as[A]: A = dyn.asInstanceOf[A]
  }

  @inline implicit def fromUnit(value: Unit):       js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromBoolean(value: Boolean): js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromByte(value: Byte):       js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromShort(value: Short):     js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromInt(value: Int):         js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromLong(value: Long):       js.UndefOr[js.Any] = value.toDouble.dyn
  @inline implicit def fromFloat(value: Float):     js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromDouble(value: Double):   js.UndefOr[js.Any] = value.dyn
  @inline implicit def fromString(s: String):       js.UndefOr[js.Any] = s.dyn
}
