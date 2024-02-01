/*
 * Copyright 2024 Polyvariant
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

package respectfully

import cats.effect.IO
import cats.implicits._
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import org.http4s.Header
import org.http4s.HttpApp
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.typelevel.ci.CIString

import scala.compiletime.summonInline
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.ToExpr
import scala.quoted.Type
import scala.quoted.quotes

trait API[Alg] {
  def toRoutes: Alg => HttpApp[IO]
  def toClient: (Client[IO], Uri) => Alg
}

object API {

  def apply[Alg](using api: API[Alg]): API[Alg] = api

  inline def derived[Alg]: API[Alg] = ${ derivedImpl[Alg] }

  private def derivedImpl[Alg: Type](using Quotes): Expr[API[Alg]] = {
    import quotes.reflect.{TypeRepr, report, DefDef, Position, asTerm}

    val algTpe = TypeRepr.of[Alg]
    val endpoints = algTpe.typeSymbol.declaredMethods.map { meth =>
      require(
        meth.paramSymss.size == 1,
        "Only methods with one parameter list are supported, got: " + meth.paramSymss + " for " + meth.name,
      )

      val inputCodec =
        meth.paramSymss.head match {
          case Nil => '{ Codec.from(Decoder[Unit], Encoder[Unit]) }

          case one :: Nil => /* ok */
            one.termRef.typeSymbol.typeRef.asType match {
              case '[t] =>
                '{
                  Codec.from(
                    summonInline[Decoder[t]],
                    summonInline[Encoder[t]],
                  )
                }
            }

          case _ =>
            report.errorAndAbort(
              "Only methods with one parameter are supported",
              meth.pos.getOrElse(Position.ofMacroExpansion),
            )
        }

      val outputCodec =
        meth.tree.asInstanceOf[DefDef].returnTpt.tpe.asType match {
          case '[IO[t]] =>
            '{
              Codec.from(
                summonInline[Decoder[t]],
                summonInline[Encoder[t]],
              )
            }
          case other =>
            val typeStr =
              TypeRepr
                .of(
                  using other
                )
                .show

            report.errorAndAbort(
              s"Only methods returning IO are supported. Found: $typeStr",
              meth.pos.getOrElse(Position.ofMacroExpansion),
            )
        }

      '{
        Endpoint[Any, Any](
          ${ Expr(meth.name) },
          ${ inputCodec }.asInstanceOf[Codec[Any]],
          ${ outputCodec }.asInstanceOf[Codec[Any]],
        )
      }
    }

    def functionsFor(algExpr: Expr[Alg]): Expr[List[(String, Any => IO[Any])]] = Expr.ofList {
      algTpe
        .typeSymbol
        .declaredMethods
        .map { meth =>
          meth.paramSymss.head match {
            case Nil =>
              // special-case: nullary method
              Expr(meth.name) -> '{ (_: Any) =>
                ${ algExpr.asTerm.select(meth).appliedToNone.asExprOf[IO[Any]] }
              }

            case sym :: Nil =>
              sym.termRef.typeSymbol.typeRef.asType match {
                case '[t] =>
                  Expr(meth.name) -> '{ (input: Any) =>
                    ${
                      //format: off
                      algExpr
                        .asTerm
                        .select(meth)
                        .appliedTo('{ input.asInstanceOf[t] }.asTerm)
                        .asExprOf[IO[Any]]
                      //format: on
                    }
                  }
              }
            case _ =>
              report.errorAndAbort(
                "Only methods with one parameter are supported",
                meth.pos.getOrElse(Position.ofMacroExpansion),
              )
          }

        }
        .map(Expr.ofTuple(_))
    }

    val asFunction: Expr[Alg => AsFunction] =
      '{ (alg: Alg) =>
        val functionsByName: Map[String, Any => IO[Any]] = ${ functionsFor('alg) }.toMap
        new AsFunction {
          def apply[In, Out](
            endpointName: String,
            in: In,
          ): IO[Out] = functionsByName(endpointName)(in).asInstanceOf[IO[Out]]

        }
      }

    val fromFunction: Expr[AsFunction => Alg] = '{ asf => ${ proxy[Alg]('asf).asExprOf[Alg] } }

    '{ API.instance[Alg](${ Expr.ofList(endpoints) }, ${ asFunction }, ${ fromFunction }) }
  }

  private def proxy[Trait: Type](using Quotes)(asf: Expr[AsFunction]) = {
    import quotes.reflect.*
    val parents = List(TypeTree.of[Object], TypeTree.of[Trait])

    val meths = TypeRepr.of[Trait].typeSymbol.declaredMethods

    def decls(cls: Symbol): List[Symbol] = meths.map { method =>
      val methodType = TypeRepr.of[Trait].memberType(method)

      Symbol.newMethod(
        cls,
        method.name,
        methodType,
        flags = Flags.EmptyFlags,
        privateWithin = method.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol),
      )
    }

    // The definition is experimental and I didn't want to bother.
    // If it breaks in 3.4, so be it ;)
    val cls = classOf[SymbolModule]
      .getDeclaredMethods()
      .filter(_.getName == "newClass")
      .head
      .invoke(
        Symbol,
        Symbol.spliceOwner,
        "Anon",
        parents.map(_.tpe),
        decls,
        None,
      )
      .asInstanceOf[Symbol]

    val body: List[DefDef] = cls.declaredMethods.map { sym =>
      def undefinedTerm(args: List[List[Tree]]) = {
        args.head match {
          case Nil        => '{ ${ asf }.apply(${ Expr(sym.name) }, ()) }
          case one :: Nil => '{ ${ asf }.apply(${ Expr(sym.name) }, ${ one.asExprOf[Any] }) }
          case _ =>
            report.errorAndAbort(
              "Only methods with one parameter are supported",
              sym.pos.getOrElse(Position.ofMacroExpansion),
            )
        }

      }.asTerm

      DefDef(sym, args => Some(undefinedTerm(args)))
    }

    // The definition is experimental and I didn't want to bother.
    // If it breaks in 3.4, so be it ;)
    val clsDef = classOf[ClassDefModule]
      .getDeclaredMethods()
      .filter(_.getName == "apply")
      .head
      .invoke(
        ClassDef,
        cls,
        parents,
        body,
      )
      .asInstanceOf[ClassDef]

    val newCls = Typed(
      Apply(
        Select(New(TypeIdent(cls)), cls.primaryConstructor),
        Nil,
      ),
      TypeTree.of[Trait],
    )

    Block(List(clsDef), newCls)
  }

  private def instance[Alg](
    endpoints: List[Endpoint[?, ?]],
    asFunction: Alg => AsFunction,
    fromFunction: AsFunction => Alg,
  ): API[Alg] =
    new API[Alg] {
      private val endpointsByName = endpoints.groupBy(_.name).fmap(_.head)

      override val toClient: (Client[IO], Uri) => Alg =
        (c, uri) =>
          fromFunction {
            new AsFunction {
              override def apply[In, Out](endpointName: String, in: In): IO[Out] = {
                val e = endpointsByName(endpointName).asInstanceOf[Endpoint[In, Out]]

                given Codec[e.Out] = e.output

                def write(
                  methodName: String,
                  input: Json,
                ): Request[IO] = Request[IO](uri = uri, method = Method.POST)
                  .withHeaders(Header.Raw(CIString("X-Method"), methodName))
                  .withEntity(input)

                c.expect[e.Out](write(e.name, e.input.apply(in)))
              }
            }
          }

      override val toRoutes: Alg => HttpApp[IO] =
        impl =>
          val implFunction = asFunction(impl)

          HttpApp { req =>
            val methodName: String =
              req
                .headers
                .get(CIString("X-Method"))
                .getOrElse(sys.error("missing X-Method header"))
                .head
                .value
            req
              .as[Json]
              .flatMap { input =>
                val e = endpointsByName(methodName)

                e.input
                  .decodeJson(input)
                  .liftTo[IO]
                  .flatMap(implFunction.apply[e.In, e.Out](e.name, _).map(e.output.apply(_)))
              }
              .map(Response[IO]().withEntity(_))
          }

    }

  private case class Endpoint[In_, Out_](
    name: String,
    input: Codec[In_],
    output: Codec[Out_],
  ) {
    type In = In_
    type Out = Out_
  }

  private trait AsFunction {
    def apply[In, Out](endpointName: String, in: In): IO[Out]
  }

}
