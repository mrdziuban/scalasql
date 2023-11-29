package scalasql.utils
import scalasql.Table
import scalasql.Table.Metadata

import scala.language.experimental.macros

object TableMacros {
  def applyImpl[V[_[_]]](
      c: scala.reflect.macros.blackbox.Context
  )(implicit caseClassType: c.WeakTypeTag[V[Any]]): c.Expr[Metadata[V]] = {
    import c.universe._

    val tableRef = TermName(c.freshName("tableRef"))
    val constructor = weakTypeOf[V[Any]].members.find(_.isConstructor).head
    val constructorParameters = constructor.info.paramLists.head

    val columnParams = for (applyParam <- constructorParameters) yield {
      val name = applyParam.name
      if (applyParam.info.typeSymbol.companion != NoSymbol){
        val companion = applyParam.info.typeSymbol.companion
        q"_root_.scalasql.Table.tableMetadata($companion).vExpr($tableRef, dialect)"

      }else {
        q"""
          _root_.scalasql.Column[${applyParam.info.typeArgs.head}]()(
            implicitly,
            sourcecode.Name(
              _root_.scalasql.Table.tableColumnNameOverride(
                $tableRef.value.asInstanceOf[scalasql.Table[$caseClassType]]
              )(${name.toString})
            ),
            $tableRef.value
          ).expr($tableRef)
        """
      }
    }

    def subApplyParam(applyParam: Symbol) = {

      applyParam.info.substituteTypes(
        List(constructor.info.resultType.typeArgs.head.typeSymbol),
        List(typeOf[scalasql.Id[_]].asInstanceOf[ExistentialType].underlying.asInstanceOf[TypeRef].sym.info)
      )
    }
    val constructParams = for (param <- constructorParameters) yield {
      val tpe = subApplyParam(param)
      q"implicitly[_root_.scalasql.Queryable.Row[_, $tpe]].construct(args): scalasql.Id[$tpe]"
    }

    val deconstructParams = for (param <- constructorParameters) yield {
      val tpe = subApplyParam(param)
      q"(v: Any) => implicitly[_root_.scalasql.Queryable.Row[_, $tpe]].deconstruct(v.asInstanceOf[$tpe])"
    }

    val flattenLists = for (param <- constructorParameters) yield {
      if (param.info.typeSymbol.companion != NoSymbol) {
        val companion = param.info.typeSymbol.companion
        q"_root_.scalasql.Table.tableLabels($companion).map(List(_))"
      }else {
        val name = param.name
        q"_root_.scala.List(List(${name.toString}))"
      }
    }

    val flattenExprs = for (param <- constructorParameters) yield {
      val name = param.name
      q"_root_.scalasql.Table.Internal.flattenPrefixedExprs(table.${TermName(name.toString)})"
    }

    c.Expr[Metadata[V]](q"""
    import _root_.scalasql.renderer.SqlStr.SqlStringSyntax
    new _root_.scalasql.Table.Metadata[$caseClassType](
      () => ${flattenLists.reduceLeft((l, r) => q"$l ++ $r")},
      dialect => {
        import dialect._
        new _root_.scalasql.Table.Internal.TableQueryable(
          () => ${flattenLists.reduceLeft((l, r) => q"$l ++ $r")},
          table => ${flattenExprs.reduceLeft((l, r) => q"$l ++ $r")},
          construct0 = args => new $caseClassType(..$constructParams),
          deconstruct0 = Seq(..$deconstructParams)
        )
      },
      ($tableRef: _root_.scalasql.query.TableRef, dialect) => {
        import dialect._
        new $caseClassType(..$columnParams)
      }
    )
    """)
  }

}
trait TableMacros {
  implicit def initTableMetadata[V[_[_]]]: Metadata[V] = macro TableMacros.applyImpl[V]
}
