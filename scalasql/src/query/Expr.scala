package scalasql.query

import scalasql.renderer.SqlStr.Interp.TypeInterp
import scalasql.renderer.SqlStr.SqlStringSyntax
import scalasql.{Queryable, ResultSetIterator, TypeMapper}
import scalasql.renderer.{Context, ExprsToSql, SqlStr}

/**
 * A single "value" in your SQL query that can be mapped to and from
 * a Scala value of a particular type [[T]]
 */
trait Expr[T] extends SqlStr.Renderable {
  protected final def renderToSql(ctx: Context): SqlStr = {
    ctx.exprNaming.get(this.exprIdentity).getOrElse(toSqlExpr0(ctx))
  }

  protected def toSqlExpr0(implicit ctx: Context): SqlStr

  override def toString: String =
    throw new Exception("Expr#toString is not defined. Use Expr#exprToString")

  override def equals(other: Any): Boolean = throw new Exception(
    "Expr#equals is not defined. Use Expr#exprIdentity for your equality checks"
  )
  private lazy val exprIdentity: Expr.Identity = new Expr.Identity()
  private def exprToString: String = super.toString

  /**
   * Some syntax like `for` comprehensions likes to generate spurious `Expr(true)`
   * clauses. We need to mark them as such so we can filter them out later during
   * code generation
   */
  protected def exprIsLiteralTrue: Boolean = false
}

object Expr {
  def exprIsLiteralTrue[T](e: Expr[T]): Boolean = e.exprIsLiteralTrue
  def exprToString[T](e: Expr[T]): String = e.exprToString

  def exprIdentity[T](e: Expr[T]): Identity = e.exprIdentity
  class Identity()

  implicit def ExprQueryable[E[_] <: Expr[_], T](
      implicit mt: TypeMapper[T]
  ): Queryable.Row[E[T], T] = new ExprQueryable[E, T]()

  class ExprQueryable[E[_] <: Expr[_], T](
      implicit tm: TypeMapper[T]
  ) extends Queryable.Row[E[T], T] {
    def walkLabels() = Seq(Nil)
    def walkExprs(q: E[T]) = Seq(q)

    def toSqlStr(q: E[T], ctx: Context) = ExprsToSql(this.walk(q), SqlStr.empty, ctx)

    override def construct(args: ResultSetIterator): T = args.get(tm)

    override def deconstruct(r: T) = Seq(TypeInterp(r))
    def deconstruct2(r: T): E[T] = Expr[T] { implicit ctx: Context =>
      sql"$r"
    }.asInstanceOf[E[T]]
  }

  def apply[T](f: Context => SqlStr): Expr[T] = new Simple[T](f)
  implicit def optionalize[T](e: Expr[T]): Expr[Option[T]] = {
    new Simple[Option[T]](e.toSqlExpr0(_))
  }
  class Simple[T](f: Context => SqlStr) extends Expr[T] {
    def toSqlExpr0(implicit ctx: Context): SqlStr = f(ctx)
  }

  implicit def apply[T](
      x: T
  )(implicit conv: T => SqlStr.Interp, mappedType0: TypeMapper[T]): Expr[T] = {
    apply0[T](x)(conv, mappedType0)
  }
  def apply0[T](
      x: T,
      exprIsLiteralTrue0: Boolean = false
  )(implicit conv: T => SqlStr.Interp, mappedType0: TypeMapper[T]): Expr[T] = new Expr[T] {
    def mappedType = mappedType0
    override def toSqlExpr0(implicit ctx: Context): SqlStr =
      new SqlStr(Array("", ""), Array(conv(x)), false, Array.empty[Expr.Identity])
    protected override def exprIsLiteralTrue = exprIsLiteralTrue0
  }

}
