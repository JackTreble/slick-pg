package com.github.tminglei.slickpg
package agg

import slick.ast._
import slick.lifted._
import slick.util.ConstArray
import slick.ast.Util.nodeToNodeOps
import slick.lifted.Rep.TypedRep

/** An aggregate function call expression */
final case class AggFuncExpr(
  aggFunc: FunctionSymbol,
  params: ConstArray[Node],
  orderBy: ConstArray[(Node, Ordering)] = ConstArray.empty,
  filter: Option[Node] = None,
  distinct: Boolean = false,
  forOrderedSet: Boolean = false
)(val buildType: Type) extends SimplyTypedNode {
  type Self = AggFuncExpr
  override def self: AggFuncExpr = this
  override def children: ConstArray[Node] = params ++ orderBy.map(_._1) ++ ConstArray.from(filter)
  override protected[this] def rebuild(ch: ConstArray[Node]): Self = {
    val newAggParams = ch.slice(0, params.length)
    val orderByOffset = params.length
    val newOrderBy = ch.slice(orderByOffset, orderByOffset + orderBy.length)
    val filterOffset = orderByOffset + orderBy.length
    val filter = if (ch.length > filterOffset) Some(ch.last) else None
    copy(params = newAggParams, filter = filter,
      orderBy = orderBy.zip(newOrderBy).map { case ((_, o), n) => (n, o) }
    )(buildType)
  }
}

/**
  * pg aggregate function support, usage:
  * {{{
  *  object AggregateLibrary {
  *    val PercentileDisc = new SqlFunction("percentile_disc")
  *  }
  *  def percentileDisc(f: Double) = agg.OrderedAggFuncBuilder(AggLibrary.PercentileDisc, List(LiteralNode(f)))
  *  ...
  *  percentileDisc(0.5d).filter(t.y < 130d).within(t.x desc) <=> percentile_disc(0.5) within group ( order by "x" desc) filter ( where "y" < 130.0)
  * }}}
  */

case class AggFuncParts(
  aggFunc: FunctionSymbol,
  params: Seq[Node] = Nil,
  orderBy: Option[Ordered] = None,
  filter: Option[Node] = None,
  distinct: Boolean = false,
  forOrderedSet: Boolean = false
) {
  def toNode(tpe: Type) = {
    if (forOrderedSet) require(orderBy.isDefined, "WITHIN GROUP (order_by_clause) is REQUIRED for ordered-set aggregate function!!")
    val params1 = ConstArray.from(params)
    val orderBy1 = ConstArray.from(orderBy).flatMap(o => ConstArray.from(o.columns))
    AggFuncExpr(aggFunc, params1, orderBy1, filter, distinct = distinct,
        forOrderedSet = forOrderedSet)(tpe).replace({
      case n @ LiteralNode(v) => n.infer()
    })
  }
}

///--- for normal data set
object AggFuncRep {
  def apply[R: TypedType](aggFunc: FunctionSymbol, params: Seq[Node]): AggFuncRep[R] =
    AggFuncRep[R](AggFuncParts(aggFunc, params))
}

case class AggFuncRep[R: TypedType](_parts: AggFuncParts) extends TypedRep[R] {
  def distinct() = copy(_parts.copy(distinct = true))
  def sortBy(ordered: Ordered) = copy(_parts.copy(orderBy = Some(ordered)))
  def filter[F <: Rep[_]](where: => F)(implicit wt: CanBeQueryCondition[F]) =
    copy(_parts.copy(filter = Some(wt(where).toNode)))
  def toNode: Node = _parts.toNode(implicitly[TypedType[R]])
}

///--- for ordered data set
object OrderedAggFuncRep {
  def apply(aggFunc: FunctionSymbol, params: Seq[Node])(implicit tt: TypedType[Int]): OrderedAggFuncRep[Int] =
    OrderedAggFuncRep[Int](AggFuncParts(aggFunc, params, forOrderedSet = true))
  def withTypes[T,R:TypedType](aggFunc: FunctionSymbol, params: Seq[Node]): OrderedAggFuncRepWithTypes[T,R] =
    OrderedAggFuncRepWithTypes[T,R](AggFuncParts(aggFunc, params, forOrderedSet = true))
}

case class OrderedAggFuncRep[R: TypedType](_parts: AggFuncParts) extends TypedRep[R] {
  def within[T: TypedType](ordered: ColumnOrdered[T]) =
    copy[T](_parts.copy(orderBy = Some(ordered)))
  def filter[F <: Rep[_]](where: => F)(implicit wt: CanBeQueryCondition[F]) =
    copy(_parts.copy(filter = Some(wt(where).toNode)))
  def toNode: Node = _parts.toNode(implicitly[TypedType[R]])
}
case class OrderedAggFuncRepWithTypes[T,R: TypedType](_parts: AggFuncParts) extends TypedRep[R] {
  def within[T1 <: T,P,PR](ordered: ColumnOrdered[P])(implicit om: OptionMapperDSL.arg[T1,P]#to[R,PR]) =
    copy[T,R](_parts.copy(orderBy = Some(ordered)))
  def filter[F <: Rep[_]](where: => F)(implicit wt: CanBeQueryCondition[F]) =
    copy(_parts.copy(filter = Some(wt(where).toNode)))
  def toNode: Node = _parts.toNode(implicitly[TypedType[R]])
}
