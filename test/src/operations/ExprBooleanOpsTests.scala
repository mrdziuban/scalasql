package usql.operations

import usql._
import usql.query.Expr
import utest._


/**
 * Tests for all the individual symbolic operators and functions that we provide by default
 */
trait ExprBooleanOpsTests extends UsqlTestSuite {
  def tests = Tests {
    test("and") - checker(
      query = Expr(true) && Expr(true),
      sql = "SELECT ? AND ? as res",
      value = true
    )

    test("or") - checker(
      query = Expr(false) || Expr(false),
      sql = "SELECT ? OR ? as res",
      value = false
    )

    test("or") - checker(
      query = !Expr(false),
      sql = "SELECT NOT ? as res",
      value = true
    )
  }
}
