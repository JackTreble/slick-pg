package com.github.tminglei.slickpg

import slick.jdbc.{GetResult, JdbcType, PositionedResult, PostgresProfile, SetParameter}

import scala.reflect.classTag

trait PgArgonautSupport extends json.PgJsonExtensions with utils.PgCommonJdbcTypes { driver: PostgresProfile =>
  import driver.api._
  import argonaut._, Argonaut._

  ///---
  def pgjson: String
  ///---

  trait ArgonautCodeGenSupport {
    // register types to let `ExModelBuilder` find them
    if (driver.isInstanceOf[ExPostgresProfile]) {
      driver.asInstanceOf[ExPostgresProfile].bindPgTypeToScala("json", classTag[Json])
      driver.asInstanceOf[ExPostgresProfile].bindPgTypeToScala("jsonb", classTag[Json])
    }
  }

  /// alias
  trait JsonImplicits extends ArgonautJsonImplicits

  trait ArgonautJsonImplicits extends ArgonautCodeGenSupport {
    import utils.JsonUtils.clean
    implicit val argonautJsonTypeMapper: JdbcType[Json] =
      new GenericJdbcType[Json](
        pgjson,
        (s) => s.parseOption.getOrElse(jNull),
        (v) => clean(v.nospaces),
        hasLiteralForm = false
      )

    implicit def argonautJsonColumnExtensionMethods(c: Rep[Json]): JsonColumnExtensionMethods[Json, Json] = {
        new JsonColumnExtensionMethods[Json, Json](c)
      }
    implicit def argonautJsonOptionColumnExtensionMethods(c: Rep[Option[Json]]): JsonColumnExtensionMethods[Json, Option[Json]] = {
        new JsonColumnExtensionMethods[Json, Option[Json]](c)
      }
  }

  trait ArgonautJsonPlainImplicits extends ArgonautCodeGenSupport {
    import utils.PlainSQLUtils._

    implicit class PgJsonPositionedResult(r: PositionedResult) {
      def nextJson() = nextJsonOption().getOrElse(jNull)
      def nextJsonOption() = r.nextStringOption().flatMap(_.parseOption)
    }

    ///////////////////////////////////////////////////////////
    implicit val getJson: GetResult[Json] = mkGetResult(_.nextJson())
    implicit val getJsonOption: GetResult[Option[Json]] = mkGetResult(_.nextJsonOption())
    implicit val setJson: SetParameter[Json] = mkSetParameter[Json](pgjson, _.nospaces)
    implicit val setJsonOption: SetParameter[Option[Json]] = mkOptionSetParameter[Json](pgjson, _.nospaces)
  }
}
