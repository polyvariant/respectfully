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
      val typeParameters = meth.paramSymss.flatten.filter(_.isTypeParam)
      if (typeParameters.nonEmpty)
        report.errorAndAbort(
          s"Methods with type parameters are not supported. `${meth.name}` has type parameters: ${typeParameters.map(_.name).mkString(", ")}"
        )

    val inputCodec: Expr[Codec[List[List[Any]]]] = combineCodecs {
      meth.paramSymss.map {
        _.map { one =>
          val codec =
            one.termRef.typeSymbol.typeRef.asType match {
              case '[t] =>
                '{
                  Codec.from(
                    summonInline[Decoder[t]],
                    summonInline[Encoder[t]],
                  )
                }
            }
          one.termRef.termSymbol.name -> codec
        }
      }
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

    def functionsFor(
      algExpr: Expr[Alg]
    ): Expr[List[(String, List[List[Any]] => IO[Any])]] = Expr.ofList {
      algTpe
        .typeSymbol
        .declaredMethods
        .map { meth =>
          val selectMethod = algExpr.asTerm.select(meth)

          Expr(meth.name) -> meth.paramSymss.match {
            case Nil :: Nil =>
              // special-case: nullary method (one, zero-parameter list)
              '{ Function.const(${ selectMethod.appliedToNone.asExprOf[IO[Any]] }) }

            case _ =>
              val types = meth.paramSymss.map(_.map(_.termRef.typeSymbol.typeRef.asType))

              '{ (input: List[List[Any]]) =>
                ${
                  selectMethod
                    .appliedToArgss {
                      types
                        .zipWithIndex
                        .map { (tpeList, idx0) =>
                          tpeList.zipWithIndex.map { (tpe, idx1) =>
                            tpe match {
                              case '[t] =>
                                '{ input(${ Expr(idx0) })(${ Expr(idx1) }).asInstanceOf[t] }.asTerm
                            }
                          }
                        }
                        .toList
                    }
                    .asExprOf[IO[Any]]
                }
              }
          }
        }
        .map(Expr.ofTuple(_))
    }

    val asFunction: Expr[Alg => AsFunction] =
      '{ (alg: Alg) =>
        val functionsByName: Map[String, List[List[Any]] => IO[Any]] = ${ functionsFor('alg) }.toMap
        new AsFunction {
          def apply[In, Out](
            endpointName: String,
            in: In,
          ): IO[Out] = functionsByName(endpointName)(in.asInstanceOf[List[List[Any]]])
            .asInstanceOf[IO[Out]]

        }
      }

    val fromFunction: Expr[AsFunction => Alg] = '{ asf => ${ proxy[Alg]('asf).asExprOf[Alg] } }

    '{ API.instance[Alg](${ Expr.ofList(endpoints) }, ${ asFunction }, ${ fromFunction }) }
  }

  private inline def combineCodecs(
    codecss: List[List[(String, Expr[Codec[?]])]]
  )(
    using Quotes
  ): Expr[Codec[List[List[Any]]]] =
    '{
      combineCodecsRuntime(
        ${
          Expr.ofList {
            codecss.map { codecs =>
              Expr.ofList(
                codecs.map { case (k, v) => Expr.ofTuple((Expr(k), v)) }
              )
            }
          }
        }
      )
    }

  private def combineCodecsRuntime(
    codecss: List[List[(String, Codec[?])]]
  ): Codec[List[List[Any]]] = Codec.from(
    codecss.traverse(_.traverse { case (k, decoder) => decoder.at(k).widen }),
    inputss =>
      Json.obj(
        inputss.zip(codecss).flatMap { (inputs, codecs) =>
          inputs.zip(codecs).map { case (param, (k, encoder)) =>
            k -> encoder.asInstanceOf[Encoder[Any]](param)
          }
        }: _*
      ),
  )

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
      def impl(argss: List[List[Tree]]) = {
        argss match {
          case Nil :: Nil => '{ ${ asf }.apply(${ Expr(sym.name) }, Nil) }
          case _ =>
            '{
              ${ asf }.apply(
                endpointName = ${ Expr(sym.name) },
                in =
                  ${
                    Expr.ofList(argss.map { argList =>
                      Expr.ofList(
                        argList.map(_.asExprOf[Any])
                      )
                    })
                  },
              )
            }
        }

      }.asTerm

      DefDef(sym, argss => Some(impl(argss)))
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
