package com.github.tminglei.slickpg
package array

import scala.reflect.ClassTag
import slick.ast.FieldSymbol
import java.sql.{Connection, PreparedStatement, ResultSet}

import org.postgresql.jdbc.PgConnection
import slick.jdbc.{JdbcTypesComponent, PostgresProfile}

trait PgArrayJdbcTypes extends JdbcTypesComponent { driver: PostgresProfile =>

  ///---
  protected def useNativeArray: Boolean = true
  ///---

  class SimpleArrayJdbcType[T](sqlBaseType: String,
                               tmap: Any => T,
                               tcomap: T => Any,
                               zero: Seq[T] = null.asInstanceOf[Seq[T]],
                               override val hasLiteralForm: Boolean = true)(
              implicit override val classTag: ClassTag[Seq[T]], ctag: ClassTag[T], checked: ElemWitness[T])
                    extends DriverJdbcType[Seq[T]] { self =>

    def this(sqlBaseType: String)(implicit ctag: ClassTag[T], checked: ElemWitness[T]) =
      this(sqlBaseType, _.asInstanceOf[T], (r: T) => identity(r))

    def this(sqlBaseType: String, hasLiteralForm: Boolean)(implicit ctag: ClassTag[T], checked: ElemWitness[T]) =
      this(sqlBaseType, _.asInstanceOf[T], (r: T) => identity(r), hasLiteralForm = hasLiteralForm)

    override def sqlType: Int = java.sql.Types.ARRAY

    override def sqlTypeName(size: Option[FieldSymbol]): String = s"$sqlBaseType []"

    override def getValue(r: ResultSet, idx: Int): Seq[T] = {
      val value = r.getArray(idx)
      if (r.wasNull) zero else value.getArray.asInstanceOf[Array[Any]].toSeq.map(tmap)
    }

    override def setValue(vList: Seq[T], p: PreparedStatement, idx: Int): Unit = p.setArray(idx, mkArray(vList, Some(p.getConnection)))

    override def updateValue(vList: Seq[T], r: ResultSet, idx: Int): Unit = r.updateArray(idx, mkArray(vList))

    override def valueToSQLLiteral(vList: Seq[T]) = if(vList eq null) "NULL" else s"'${buildArrayStr(vList.map(tcomap))}'"

    //--
    protected def mkArray(v: Seq[T], conn: Option[Connection] = None): java.sql.Array = (v, conn) match {
      case (v, Some(c)) if useNativeArray && isPrimitive(v) && c.isWrapperFor(classOf[PgConnection]) =>
        c.unwrap(classOf[PgConnection]).createArrayOf(sqlBaseType, v.toArray)
      case (v, _) => utils.SimpleArrayUtils.mkArray(buildArrayStr)(sqlBaseType, v.map(tcomap))
    }

    protected def isPrimitive(v: Seq[T]): Boolean = v.size > 0 && (
      v.head.isInstanceOf[Short] ||
      v.head.isInstanceOf[Int] ||
      v.head.isInstanceOf[Long] ||
      v.head.isInstanceOf[Float] ||
      v.head.isInstanceOf[Double] ||
      v.head.isInstanceOf[Boolean] ||
      v.head.isInstanceOf[String])

    protected def buildArrayStr(vList: Seq[Any]): String = utils.SimpleArrayUtils.mkString[Any](_.toString)(vList)

    ///
    def mapTo[U](tmap: T => U, tcomap: U => T)(implicit ctags: ClassTag[Seq[U]], ctag: ClassTag[U]): SimpleArrayJdbcType[U] =
      new SimpleArrayJdbcType[U](sqlBaseType, v => tmap(self.tmap(v)), r => self.tcomap(tcomap(r)))(ctags, ctag, ElemWitness.AnyWitness.asInstanceOf[ElemWitness[U]])

    def to[SEQ[T]](conv: Seq[T] => SEQ[T], rconv: SEQ[T] => Seq[T] = (v: SEQ[T]) => v.asInstanceOf[Seq[T]])(implicit classTag: ClassTag[SEQ[T]]): DriverJdbcType[SEQ[T]] =
      new WrappedConvArrayJdbcType[T, SEQ](this, conv, rconv)
  }

  ///
  class AdvancedArrayJdbcType[T](sqlBaseType: String,
                                 fromString: (String => Seq[T]),
                                 mkString: (Seq[T] => String),
                                 zero: Seq[T] = null.asInstanceOf[Seq[T]],
                                 override val hasLiteralForm: Boolean = false)(
              implicit override val classTag: ClassTag[Seq[T]], tag: ClassTag[T])
                    extends DriverJdbcType[Seq[T]] {

    override def sqlType: Int = java.sql.Types.ARRAY

    override def sqlTypeName(size: Option[FieldSymbol]): String = s"$sqlBaseType []"

    override def getValue(r: ResultSet, idx: Int): Seq[T] = {
      val value = r.getString(idx)
      if (r.wasNull) zero else fromString(value)
    }

    override def setValue(vList: Seq[T], p: PreparedStatement, idx: Int): Unit = p.setArray(idx, mkArray(vList))

    override def updateValue(vList: Seq[T], r: ResultSet, idx: Int): Unit = r.updateArray(idx, mkArray(vList))

    override def valueToSQLLiteral(vList: Seq[T]) = if(vList eq null) "NULL" else s"'${mkString(vList)}'"

    //--
    private def mkArray(vList: Seq[T]): java.sql.Array = utils.SimpleArrayUtils.mkArray(mkString)(sqlBaseType, vList)

    def to[SEQ[T]](conv: Seq[T] => SEQ[T], rconv: SEQ[T] => Seq[T] = (v: SEQ[T]) => v.asInstanceOf[Seq[T]])(implicit classTag: ClassTag[SEQ[T]]): DriverJdbcType[SEQ[T]] =
      new WrappedConvArrayJdbcType[T, SEQ](this, conv, rconv)
  }

  /////////////////////////////////////////////////////////////////////////////////////////////
  private[array] class WrappedConvArrayJdbcType[T, SEQ[T]](val delegate: DriverJdbcType[Seq[T]], val conv: Seq[T] => SEQ[T], val rconv: SEQ[T] => Seq[T])(
      implicit override val classTag: ClassTag[SEQ[T]], ctag: ClassTag[T]) extends DriverJdbcType[SEQ[T]] {

    override def sqlType: Int = delegate.sqlType

    override def sqlTypeName(size: Option[FieldSymbol]): String = delegate.sqlTypeName(size)

    override def getValue(r: ResultSet, idx: Int): SEQ[T] = Option(delegate.getValue(r, idx)).map(conv).getOrElse(null.asInstanceOf[SEQ[T]])

    override def setValue(vList: SEQ[T], p: PreparedStatement, idx: Int): Unit = delegate.setValue(rconv(vList), p, idx)

    override def updateValue(vList: SEQ[T], r: ResultSet, idx: Int): Unit = delegate.updateValue(rconv(vList), r, idx)

    override def hasLiteralForm: Boolean = delegate.hasLiteralForm

    override def valueToSQLLiteral(vList: SEQ[T]) = delegate.valueToSQLLiteral(Option(rconv(vList)).orNull)
  }

  /// added to help check built-in support array types statically
  sealed trait ElemWitness[T]

  object ElemWitness {
    implicit object LongWitness extends ElemWitness[Long]
    implicit object IntWitness extends ElemWitness[Int]
    implicit object ShortWitness extends ElemWitness[Short]
    implicit object FloatWitness extends ElemWitness[Float]
    implicit object DoubleWitness extends ElemWitness[Double]
    implicit object BooleanWitness extends ElemWitness[Boolean]
    implicit object StringWitness extends ElemWitness[String]
    implicit object UUIDWitness extends ElemWitness[java.util.UUID]
    implicit object DateWitness extends ElemWitness[java.sql.Date]
    implicit object TimeWitness extends ElemWitness[java.sql.Time]
    implicit object TimestampWitness extends ElemWitness[java.sql.Timestamp]
    implicit object BigDecimalWitness extends ElemWitness[java.math.BigDecimal]

    object AnyWitness extends ElemWitness[Nothing]
  }
}
