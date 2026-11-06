/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package avocet.dsl

import avocet.Document
import avocet.Document.{Attr, Empty, Node, Style}
import avocet.RenderContext

import scala.quoted.*
import scala.annotation.tailrec

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import DslOptimizerUtils.*

object DslOptimizerMacro:

  def optimize[T: Type](node: Expr[Node[T]])(using Quotes): Expr[Node[T]] =

    import quotes.reflect.*
    import util.*

    cleanupUnableToOptimizeFile // Run once at first start of macro

    @tailrec
    def orderDocument(expr: Expr[Document[T]]): Int = expr match {
      case '{when[T]($cond)($expr)} => orderDocument(expr)
      case '{Empty} => -3
      case '{void} => -3
      case x if x.isExprOf[Style[T]] => -3
      case x if x.isExprOf[Attr[T]] => -2
      case x if x.isExprOf[Node[T]] => -1
      case _ => 0
    }

    def containsUnspecifiedDocument(xs: Seq[Expr[Document[T]]]): Boolean =
      xs.exists(x => orderDocument(x) == 0)

    def logUnableToOptimizeTerm(term: Term): Unit =
      val pos = term.pos
      logUnableToOptimize(
        pos.sourceFile.toString, pos.startLine + 1,
        pos.startColumn + 1, util.Try(pos.sourceCode).getOrElse(None)
      )

    def optimizeNode(rc: Expr[RenderContext[T]], tagDef: Expr[TagDef], children: Seq[Expr[Document[T]]]) =
      val xs = children
        .sortBy { x =>
          val order = orderDocument(x)
          if (order == 0 && unableToSortTagWarningsEnabled)
            report.warning(unableToSortTagWarning, x)
          order
        }
        .map(aux(rc, _))
        .toList
      val block = xs match {
        case Nil => '{}
        case x :: Nil => x
        case _ => Expr.block(xs.dropRight(1), xs.last)
      }
      '{
        $rc.openNode($tagDef.ns, $tagDef.name)
        $block
        $rc.closeNode($tagDef.name)
      }
    // A lambda literal has no evaluation of its own, so its body can be inlined per element.
    def isLambdaLiteral(f: Expr[Any]): Boolean =
      f.asTerm.underlyingArgument match {
        case Block(List(_: DefDef), Closure(_, _)) => true
        case _ => false
      }

    // `opt.map(f)` in the plain form evaluates `opt` and `f` exactly once each.
    def optionLowering[X, D <: Document[T]](rc: Expr[RenderContext[T]], opt: Expr[Option[X]], f: Expr[X => D])(using Type[X], Type[D]): Expr[Any] =
      if isLambdaLiteral(f) then
        '{
          val o = $opt
          if (o.nonEmpty) {
            val x = o.get
            ${aux(rc, Expr.betaReduce('{($f)(x)}).asExprOf[Document[T]])}
          }
        }
      else
        '{
          val o = $opt
          val fn = $f
          if (o.nonEmpty) fn(o.get).apply($rc)
        }

    // `seq.map(f)` in the plain form evaluates `seq` and `f` exactly once each.
    def seqLowering[X, D <: Document[T]](rc: Expr[RenderContext[T]], seq: Expr[Seq[X]], f: Expr[X => D])(using Type[X], Type[D]): Expr[Any] =
      if isLambdaLiteral(f) then
        '{
          val i = $seq.iterator
          while (i.hasNext) {
            val x = i.next()
            ${aux(rc, Expr.betaReduce('{($f)(x)}).asExprOf[Document[T]])}
          }
        }
      else
        '{
          // Plain `seq.map(f)` evaluates the collection before the callback.
          val seq0 = $seq
          val fn = $f
          val i = seq0.iterator
          while (i.hasNext) fn(i.next()).apply($rc)
        }

    def aux(rc: Expr[RenderContext[T]], targetExpr: Expr[Document[T]]): Expr[Any] = targetExpr match {
      // Optimize tag open/close
      case '{(${tagDef}: TagDef).apply[T](${Varargs(children)}:_*)} =>
        if !containsUnspecifiedDocument(children) | unableToSortForceOptimization then
          optimizeNode(rc, tagDef, children)
        else
          logUnableToOptimizeTerm(targetExpr.asTerm)
          '{$targetExpr.apply($rc)}
      // Optimize attributes and styles. A dynamic receiver (definition or key
      // expression) is bound once so it is evaluated exactly once, as in the plain form.
      case '{(${styleDef}: StyleDef).@=[T]($styleValue)} =>
        '{ val d = $styleDef; $rc.setStyle(d.name, $styleValue) }
      case '{(${attrDef}: AttrDef).:=[T]($attrValue)} =>
        '{ val d = $attrDef; $rc.setAttr(d.ns, d.name, $attrValue) }
      // Typed keys: the encoder runs once, at render time, like the plain form
      case '{(${key}: AttrKey[a]).:=[T]($value)} =>
        '{ val k = $key; $rc.setAttr(k.ns, k.name, k.encode($value)) }
      case '{(${key}: BooleanAttrKey).:=[T]($present)} =>
        '{ val k = $key; if ($present) $rc.setAttr(k.ns, k.name, "") }
      case '{(${key}: StyleKey[a]).@=[T]($value)} =>
        '{ val k = $key; $rc.setStyle(k.name, k.encode($value)) }
      // Explicit fragment and iteration combinators
      case '{fragment[T](${Varargs(nodes)}:_*)} =>
        val xs = nodes.map(aux(rc, _)).toList
        xs match {
          case Nil => '{}
          case x :: Nil => x
          case _ => Expr.block(xs.dropRight(1), xs.last)
        }
      // Callbacks: a lambda literal is inlined; any other callback expression is
      // evaluated once, outside the loop, and applied per element.
      case '{each[T, a]($xs)($f)} =>
        if isLambdaLiteral(f) then
          '{
            val i = $xs.iterator
            while (i.hasNext) {
              val x = i.next()
              ${aux(rc, Expr.betaReduce('{($f)(x)}))}
            }
          }
        else
          '{
            // Plain `each(xs)(f)` evaluates the collection before the callback.
            val xs0 = $xs
            val fn = $f
            val i = xs0.iterator
            while (i.hasNext) fn(i.next()).apply($rc)
          }
      case '{noNode} => '{}
      case '{noAttr} => '{}
      case '{noStyle} => '{}
      // Optimize converters
      case '{stringToNode($value)} => '{$rc.addTextNode($value)}
      case '{miscToNode[T]($value)} => '{$rc.addMisc($value)}
      case '{optionToNode[T](($opt: Option[x]).map($f))} => optionLowering(rc, opt, f)
      case '{optionToAttr[T](($opt: Option[x]).map($f))} => optionLowering(rc, opt, f)
      case '{optionToStyle[T](($opt: Option[x]).map($f))} => optionLowering(rc, opt, f)
      case '{seqToNode[T](($seq: Seq[x]).map($f))} => seqLowering(rc, seq, f)
      case '{seqToAttr[T](($seq: Seq[x]).map($f))} => seqLowering(rc, seq, f)
      case '{seqToStyle[T](($seq: Seq[x]).map($f))} => seqLowering(rc, seq, f)
      // Optimize empty nodes
      case '{void} => '{}
      case '{Empty} => '{}
      // Optimize control flow
      case '{if ($cond) ($lhs: Document[T]) else ($rhs: Document[T])} =>
        '{if ($cond) ${aux(rc, lhs)} else ${aux(rc, rhs)}}
      case '{when($cond)(($expr: Document[T]))} =>
        '{if ($cond) ${aux(rc, expr)}}
      // Corner cases
      case _ =>
        def trasformBlock(statements: List[Statement], expr: Term): Term =
          val uExpr = aux(rc, expr.asExprOf[Document[T]])
          Block(statements, uExpr.asTerm)
        def trnasfromCases(cases: List[CaseDef]): List[CaseDef] =
          cases.map {
            case CaseDef(pat, guard, body) =>
              val uBody = aux(rc, body.asExprOf[Document[T]])
              CaseDef(pat, guard, uBody.asTerm)
          }

        targetExpr.asTerm match {
          case Inlined(_, _, Block(statements, expr)) => trasformBlock(statements, expr).asExpr
          case Inlined(_, _, tree @ Match(selector, cases)) =>
            Match
              .copy(tree)(selector, trnasfromCases(cases))
              .asExpr
          case Block(statements, expr) => trasformBlock(statements, expr).asExpr
          case tree @ Match(selector, cases) =>
            Match
            .copy (tree) (selector, trnasfromCases (cases) )
            .asExpr
          // Unable to optimize
          case term =>
            // DEBUG
            // report.info(term.show)
            // ---
            logUnableToOptimizeTerm(term)
            '{$targetExpr.apply($rc)}
        }
    }

    val optimized = '{
      avocet.Document.Node[T]{ rc =>
        ${aux('{rc}, node)}
      }
    }

    // DEBUG
    //report.info(optimized.show)
    // ---
    optimized

end DslOptimizerMacro