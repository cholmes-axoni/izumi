package com.github.pshirshov.izumi.logstage.api

import com.github.pshirshov.izumi.logstage.model.Message

import scala.language.experimental.macros
import scala.reflect.macros.blackbox


object ArgumentNameExtractionMacro extends ArgumentNameExtractionMacro {
  def fetchArgsNames(c: blackbox.Context)(args: c.Expr[Any]*): c.Expr[Message] = {
    import c.universe._

    val expressions = args.map {
      param =>
        param.tree match {
          case c.universe.Ident(TermName(s)) =>
            reifiedExtracted(c)(param, s)

          case c.universe.Select(_, TermName(s)) =>
            reifiedExtracted(c)(param, s)

          case c.universe.Literal(c.universe.Constant(_)) =>
            reifiedPrefixed(c)(param, "UNNAMED")

          case _ =>
            reifiedPrefixed(c)(param, "CANTEXTRACT")
        }
    }

    val expressionsList = Apply(
      Select(
        reify(List).tree
        , TermName("apply")
      )
      , expressions.toList
    )


    reify {
      Message(
        c.prefix.splice.asInstanceOf[LogSC].sc,
        c.Expr[List[(String, Any)]](expressionsList).splice
      )
    }

  }

  private def reifiedPrefixed(c: blackbox.Context)(param: c.Expr[Any], prefix: String) = {
    import c.universe._
    val paramRepTree = c.Expr[String](Literal(Constant(prefix)))
    reify {
      (s"${paramRepTree.splice}:${param.splice}", param.splice)
    }.tree
  }

  private def reifiedExtracted(c: blackbox.Context)(param: c.Expr[Any], s: String) = {
    import c.universe._
    val paramRepTree = c.Expr[String](Literal(Constant(s)))
    reify {
      (paramRepTree.splice, param.splice)
    }.tree
  }
}

trait ArgumentNameExtractionMacro {

  implicit class LogSC(val sc: StringContext) {
    def m(args: Any*): Message = macro ArgumentNameExtractionMacro.fetchArgsNames
  }

}