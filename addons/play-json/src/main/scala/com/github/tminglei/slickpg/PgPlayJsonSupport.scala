package com.github.tminglei.slickpg

import slick.jdbc.{GetResult, JdbcType, PositionedResult, PostgresProfile, SetParameter}

import scala.reflect.classTag

trait PgPlayJsonSupport extends json.PgJsonExtensions with utils.PgCommonJdbcTypes { driver: PostgresProfile =>
  import driver.api._
  import play.api.libs.json._

  ///---
  def pgjson: String
  ///---

  trait PlayJsonCodeGenSupport {
    // register types to let `ExModelBuilder` find them
    if (driver.isInstanceOf[ExPostgresProfile]) {
      driver.asInstanceOf[ExPostgresProfile].bindPgTypeToScala("json", classTag[JsValue])
      driver.asInstanceOf[ExPostgresProfile].bindPgTypeToScala("jsonb", classTag[JsValue])
    }
  }

  /// alias
  trait JsonImplicits extends PlayJsonImplicits

  trait PlayJsonImplicits extends PlayJsonCodeGenSupport {
    import utils.JsonUtils.clean
    implicit val playJsonTypeMapper: JdbcType[JsValue] =
      new GenericJdbcType[JsValue](
        pgjson,
        (v) => Json.parse(v),
        (v) => clean(Json.stringify(v)),
        hasLiteralForm = false
      )

    implicit def playJsonColumnExtensionMethods(c: Rep[JsValue]): JsonColumnExtensionMethods[JsValue, JsValue] = {
        new JsonColumnExtensionMethods[JsValue, JsValue](c)
      }
    implicit def playJsonOptionColumnExtensionMethods(c: Rep[Option[JsValue]]): JsonColumnExtensionMethods[JsValue, Option[JsValue]] = {
        new JsonColumnExtensionMethods[JsValue, Option[JsValue]](c)
      }
  }

  trait PlayJsonPlainImplicits extends PlayJsonCodeGenSupport {
    import utils.PlainSQLUtils._

    implicit class PgJsonPositionedResult(r: PositionedResult) {
      def nextJson() = nextJsonOption().getOrElse(JsNull)
      def nextJsonOption() = r.nextStringOption().map(Json.parse)
    }

    ////////////////////////////////////////////////////////////
    implicit val getJson: GetResult[JsValue] = mkGetResult(_.nextJson())
    implicit val getJsonOption: GetResult[Option[JsValue]] = mkGetResult(_.nextJsonOption())
    implicit val setJson: SetParameter[JsValue] = mkSetParameter[JsValue](pgjson, Json.stringify)
    implicit val setJsonOption: SetParameter[Option[JsValue]] = mkOptionSetParameter[JsValue](pgjson, Json.stringify)
  }
}
