package scalasql.dialects

import scalasql._
import scalasql.query.{
  Expr,
  InsertReturning,
  InsertSelect,
  InsertValues,
  Joinable,
  OnConflict,
  Query,
  TableRef,
  Update
}
import scalasql.renderer.SqlStr.{SqlStringSyntax, optSeq}
import scalasql.renderer.{Context, SelectToSql, SqlStr}
import scalasql.utils.OptionPickler

trait MySqlDialect extends Dialect {
  def defaultQueryableSuffix = ""
  def castParams = false

  override implicit def ExprStringOpsConv(v: Expr[String]): MySqlDialect.ExprStringOps =
    new MySqlDialect.ExprStringOps(v)

  override implicit def TableOpsConv[V[_[_]]](t: Table[V]): scalasql.operations.TableOps[V] =
    new MySqlDialect.TableOps(t)

  implicit def OnConflictableUpdate[Q, R](query: InsertValues[Q, R]) =
    new MySqlDialect.OnConflictable[Q, Int](query, query.expr, query.table)
}

object MySqlDialect extends MySqlDialect {
  class ExprStringOps(val v: Expr[String]) extends operations.ExprStringOps(v) with PadOps {
    override def +(x: Expr[String]): Expr[String] = Expr { implicit ctx => sql"CONCAT($v, $x)" }

    def indexOf(x: Expr[String]): Expr[Int] = Expr { implicit ctx => sql"POSITION($x IN $v)" }
    def reverse: Expr[String] = Expr { implicit ctx => sql"REVERSE($v)" }
  }

  class TableOps[V[_[_]]](t: Table[V]) extends scalasql.operations.TableOps[V](t) {
    override def update: Update[V[Column.ColumnExpr], V[Id]] = {
      val ref = t.tableRef
      new Update(Update.fromTable(t.metadata.vExpr(ref), ref)(t.containerQr))
    }
  }

  class Update[Q, R](update: Update.Impl[Q, R]) extends scalasql.query.Update[Q, R] {
    def filter(f: Q => Expr[Boolean]): Update[Q, R] = new Update(update.filter(f))

    def set(f: Q => (Column.ColumnExpr[_], Expr[_])*): Update[Q, R] = new Update(update.set(f: _*))

    def join0[Q2, R2](other: Joinable[Q2, R2], on: Option[(Q, Q2) => Expr[Boolean]])(
        implicit joinQr: Queryable[Q2, R2]
    ): Update[(Q, Q2), (R, R2)] = new Update(update.join0(other, on))

    def expr: Q = update.expr

    def table: TableRef = update.table

    override def toSqlQuery(implicit ctx: Context): (SqlStr, Seq[MappedType[_]]) = {
      toSqlQuery0(update, ctx)
    }

    def toSqlQuery0[Q, R](
        q: Update.Impl[Q, R],
        prevContext: Context
    ): (SqlStr, Seq[MappedType[_]]) = {
      val computed = Context
        .compute(prevContext, q.joins.flatMap(_.from).map(_.from), Some(q.table))
      import computed.implicitCtx

      val tableName = SqlStr.raw(prevContext.config.tableNameMapper(q.table.value.tableName))
      val updateList = q.set0.map { case (k, v) =>
        val colStr = SqlStr.raw(prevContext.config.columnNameMapper(k.name))
        sql"$tableName.$colStr = $v"
      }
      val sets = SqlStr.join(updateList, sql", ")

      val where = SqlStr.optSeq(q.where) { where =>
        sql" WHERE " + SqlStr.join(where.map(_.toSqlQuery._1), sql" AND ")
      }

      val joins = optSeq(q.joins)(SelectToSql.joinsToSqlStr(_, computed.fromSelectables))

      (sql"UPDATE $tableName" + joins + sql" SET " + sets + where, Nil)
    }

    def qr: Queryable[Q, R] = update.qr

    override def valueReader: OptionPickler.Reader[Int] = implicitly
  }

  class OnConflictable[Q, R](val query: Query[R], expr: Q, table: TableRef) {

    def onConflictUpdate(c2: Q => (Column.ColumnExpr[_], Expr[_])*): OnConflictUpdate[Q, R] =
      new OnConflictUpdate(this, c2.map(_(expr)), table)
  }

  class OnConflictUpdate[Q, R](
      insert: OnConflictable[Q, R],
      updates: Seq[(Column.ColumnExpr[_], Expr[_])],
      table: TableRef
  ) extends Query[R] {

    override def isExecuteUpdate = true
    def walk() = insert.query.walk()

    def singleRow = insert.query.singleRow

    def valueReader = insert.query.valueReader

    def toSqlQuery(implicit ctx: Context): (SqlStr, Seq[MappedType[_]]) = toSqlQuery0(ctx)
    def toSqlQuery0(ctx: Context): (SqlStr, Seq[MappedType[_]]) = {
      val computed = Context.compute(ctx, Nil, Some(table))
      import computed.implicitCtx
      val (str, mapped) = insert.query.toSqlQuery
      val updatesStr = SqlStr
        .join(updates.map { case (c, e) => SqlStr.raw(c.name) + sql" = $e" }, sql", ")
      (str + sql" ON DUPLICATE KEY UPDATE $updatesStr", mapped)
    }
  }
}
