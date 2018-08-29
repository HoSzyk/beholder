package org.virtuslab.beholder.filters.json

import org.joda.time.{ DateTime, LocalDate }
import org.virtuslab.beholder.filters._
import org.virtuslab.beholder.utils.ILikeExtension._
import org.virtuslab.beholder.utils.SeqParametersHelperComponent
import org.virtuslab.unicorn.UnicornWrapper
import play.api.libs.functional.syntax._
import play.api.libs.json._
import slick.ast.{ BaseTypedType, TypedType }

trait JsonFilterFieldsComponent extends FilterFieldComponent with SeqParametersHelperComponent {
  self: UnicornWrapper[Long] =>

  import unicorn._
  import unicorn.profile.api._
  import CustomTypeMappers._

  abstract class JsonFilterField[A: TypedType, B] extends MappedFilterField[A, B] {
    def fieldTypeDefinition: JsValue

    protected def filterFormat: Format[B]

    protected[json] def valueWrite: Writes[A]

    final def writeValue(value: Any): JsValue = valueWrite.writes(value.asInstanceOf[A])

    final def readFilter(value: JsValue): JsResult[Any] = filterFormat.reads(value)

    final def writeFilter(value: Any): JsValue = filterFormat.writes(value.asInstanceOf[B])

    def isIgnored = false
  }

  abstract class ImplicitlyJsonFilterFiled[A: TypedType: Writes, B: Format](dataTypeName: String)
      extends JsonFilterField[A, B] {
    override def fieldTypeDefinition: JsValue = JsString(dataTypeName)

    override protected[json] def valueWrite: Writes[A] = implicitly

    override protected def filterFormat: Format[B] = implicitly
  }

  object JsonFilterFields {

    /**
     * find exact number
     */
    object inIntField extends ImplicitlyJsonFilterFiled[Int, Int]("Int") {
      override protected def filterOnColumn(column: Rep[Int])(data: Int): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * check if value is in given sequence
     */
    object inIntFieldSeq extends ImplicitlyJsonFilterFiled[Int, Seq[Int]]("IntSeq") {
      override protected def filterOnColumn(column: Rep[Int])(dataSeq: Seq[Int]): Rep[Option[Boolean]] = {
        SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, data) => column.? === data)
      }
    }

    object inBigDecimal extends ImplicitlyJsonFilterFiled[BigDecimal, BigDecimal]("bigDecimal") {
      override protected def filterOnColumn(column: Rep[BigDecimal])(data: BigDecimal): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * simple check boolean
     */
    object inBoolean extends ImplicitlyJsonFilterFiled[Boolean, Boolean]("Boolean") {
      override def filterOnColumn(column: Rep[Boolean])(data: Boolean): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * search in text (ilike)
     */
    object inText extends ImplicitlyJsonFilterFiled[String, String]("Text") {
      override def filterOnColumn(column: Rep[String])(data: String): Rep[Option[Boolean]] = column.? ilike s"%${escape(data)}%"
    }

    /**
     * check if text is in given text sequence (ilike)
     */
    object inTextSeq extends ImplicitlyJsonFilterFiled[String, Seq[String]]("TextSeq") {
      override def filterOnColumn(column: Rep[String])(data: Seq[String]): Rep[Option[Boolean]] = {
        SeqParametersHelper.isColumnValueInsideSeq(column)(data)((column, d) => column.? ilike s"%${escape(d)}%")
      }
    }

    /**
     * search in text (ilike) for optional fields
     */
    object inOptionText extends ImplicitlyJsonFilterFiled[Option[String], String]("OptionalText") {
      override def filterOnColumn(column: Rep[Option[String]])(data: String): Rep[Option[Boolean]] = column ilike s"%${escape(data)}%"
    }

    object inDateTime extends ImplicitlyJsonFilterFiled[DateTime, DateTime]("DateTime") {
      override def filterOnColumn(column: Rep[DateTime])(data: DateTime): Rep[Option[Boolean]] = column.? === data

      override protected[json] def valueWrite: Writes[DateTime] = Writes.jodaDateWrites("yyyy-MM-dd HH:mm")

      override protected def filterFormat: Format[DateTime] = new Format[DateTime] {
        override def writes(o: DateTime): JsValue = valueWrite.writes(o)

        override def reads(json: JsValue): JsResult[DateTime] = Reads.jodaDateReads("yyyy-MM-dd HH:mm").reads(json)
      }
    }

    object inLocalDate extends ImplicitlyJsonFilterFiled[LocalDate, LocalDate]("LocalDate") {
      override def filterOnColumn(column: Rep[LocalDate])(data: LocalDate): Rep[Option[Boolean]] = column.? === data
    }

    /**
     * check enum value
     * @tparam T - enum class (eg. Colors.type)
     */
    def inEnum[T <: Enumeration](enum: T)(implicit tm: BaseTypedType[T#Value], formatter: Format[T#Value]): JsonFilterField[T#Value, T#Value] = {
      new JsonFilterField[T#Value, T#Value] {
        override def fieldTypeDefinition: JsValue = JsArray(
          enum.values.toList.map(v => Json.toJson(v.asInstanceOf[T#Value]))
        )

        override protected[json] def valueWrite: Writes[T#Value] = formatter

        override protected def filterFormat: Format[T#Value] = formatter

        override protected def filterOnColumn(column: Rep[T#Value])(value: T#Value): Rep[Option[Boolean]] = column.? === value
      }
    }

    /**
     * check if enum value is in given sequence
     * @tparam T - enum class (eg. Colors.type)
     */
    def inEnumSeq[T <: Enumeration](enum: T)(implicit tm: BaseTypedType[T#Value], formatter: Format[T#Value]): JsonFilterField[T#Value, Seq[T#Value]] = {
      new JsonFilterField[T#Value, Seq[T#Value]] {
        override def fieldTypeDefinition: JsValue = JsArray(
          enum.values.toList.map(v => Json.toJson(v.asInstanceOf[T#Value]))
        )

        override protected[json] def valueWrite: Writes[T#Value] = formatter

        override protected def filterFormat: Format[Seq[T#Value]] = new Format[Seq[T#Value]] {
          override def reads(json: JsValue): JsResult[Seq[T#Value]] = JsSuccess(json.as[Seq[T#Value]])

          override def writes(o: Seq[T#Value]): JsValue = JsArray(o.map(Json.toJson(_)))
        }

        override protected def filterOnColumn(column: Rep[T#Value])(dataSeq: Seq[T#Value]): Rep[Option[Boolean]] = {
          SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, data) => column.? === data)
        }
      }
    }

    private implicit def rangeFormat[T: Format]: Format[FilterRange[T]] =
      ((__ \ "from").formatNullable[T] and
        (__ \ "to").formatNullable[T])(FilterRange.apply, unlift(FilterRange.unapply))

    def inField[T: BaseTypedType: Format](typeName: String) =
      new ImplicitlyJsonFilterFiled[T, T](typeName) {
        override def filterOnColumn(column: Rep[T])(data: T): Rep[Option[Boolean]] = column.? === data
      }

    def inFieldSeq[T: BaseTypedType: Format](typeName: String) =
      new ImplicitlyJsonFilterFiled[T, Seq[T]](typeName) {
        override def filterOnColumn(column: Rep[T])(dataSeq: Seq[T]): Rep[Option[Boolean]] = {
          SeqParametersHelper.isColumnValueInsideSeq(column)(dataSeq)((column, data) => column.? === data)
        }
      }

    def inRange[T: BaseTypedType: Format](baseType: JsonFilterField[T, T]): JsonFilterField[T, FilterRange[T]] =
      new JsonFilterField[T, FilterRange[T]] {
        override def filterOnColumn(column: Rep[T])(value: FilterRange[T]): Rep[Option[Boolean]] = {
          value match {
            case FilterRange(Some(from), Some(to)) => column.? >= from && column.? <= to
            case FilterRange(None, Some(to)) => column.? <= to
            case FilterRange(Some(from), None) => column.? >= from
            case _ => LiteralColumn(Some(true))
          }
        }

        override def fieldTypeDefinition: JsValue = JsObject(Seq(
          "type" -> JsString("range"),
          "dataType" -> baseType.fieldTypeDefinition
        ))

        override protected[json] def valueWrite: Writes[T] = baseType.valueWrite

        override protected def filterFormat: Format[FilterRange[T]] = implicitly
      }

    def inOptionRange[T: BaseTypedType: Format](baseType: JsonFilterField[T, T]): JsonFilterField[Option[T], FilterRange[T]] =
      new JsonFilterField[Option[T], FilterRange[T]] {
        override def filterOnColumn(column: Rep[Option[T]])(value: FilterRange[T]): Rep[Option[Boolean]] = {
          value match {
            case FilterRange(Some(from), Some(to)) => column >= from && column <= to
            case FilterRange(None, Some(to)) => column <= to
            case FilterRange(Some(from), None) => column >= from
            case _ => LiteralColumn(Some(true))
          }
        }

        override def fieldTypeDefinition: JsValue = JsObject(Seq(
          "type" -> JsString("range"),
          "dataType" -> baseType.fieldTypeDefinition
        ))

        override protected[json] def valueWrite: Writes[Option[T]] = new Writes[Option[T]] {
          override def writes(o: Option[T]): JsValue = o.map(baseType.valueWrite.writes).getOrElse(JsNull)
        }

        override protected def filterFormat: Format[FilterRange[T]] = rangeFormat
      }

    /**
     * Ignores given field in filter.
     */
    def ignore[T: TypedType: Writes]: JsonFilterField[T, T] = new JsonFilterField[T, T] {

      override def fieldTypeDefinition: JsValue = JsNull

      override protected[json] def valueWrite: Writes[T] = implicitly

      override protected def filterFormat: Format[T] = new Format[T] {
        override def reads(json: JsValue): JsResult[T] = JsError()

        override def writes(o: T): JsValue = JsNull
      }

      override def filterOnColumn(column: Rep[T])(value: T): Rep[Option[Boolean]] = LiteralColumn(Some(true))

      override def isIgnored: Boolean = true
    }
  }
}
