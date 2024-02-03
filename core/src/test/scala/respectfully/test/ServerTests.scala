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

package respectfully.test

import cats.effect.IO
import io.circe.Encoder
import org.http4s.Header
import org.http4s.Method
import org.http4s.Request
import org.http4s.circe.CirceEntityCodec._
import respectfully.API
import weaver._
import org.http4s.Status
import cats.syntax.all.*
import org.http4s.Response
import io.circe.Decoder
import cats.kernel.Eq
import cats.derived._
import io.circe.Codec
import io.circe.syntax._
import io.circe.Json
import io.circe.JsonObject
import cats.Show

object ServerTests extends SimpleIOSuite {
  pureTest("no ops") {
    trait SimpleApi derives API
    success
  }

  private def request(
    method: String
  )(
    body: JsonObject
  ) = Request[IO](Method.POST)
    .withEntity(body)
    .withHeaders("X-Method" -> method)

  private def assertSuccess[A: Decoder: Eq](
    response: Response[IO],
    expected: A,
  ) = response.as[A].map { body =>
    assert(response.status == Status.Ok) &&
    assert.eql(expected, body)
  }

  test("one op") {
    trait SimpleApi derives API {
      def op(): IO[Int]
    }

    val impl: SimpleApi = () => IO.pure(42)

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("op")(JsonObject.empty))
      .flatMap(assertSuccess(_, 42))
  }

  test("one op with param") {
    trait SimpleApi derives API {
      def operation(a: Int): IO[Int]
    }

    val impl: SimpleApi = a => IO.pure(a + 1)

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("operation")(JsonObject("a" := 42)))
      .flatMap(assertSuccess(_, 43))
  }

  test("one op with more complex param") {
    case class Person(name: String, age: Int) derives Codec.AsObject, Eq, Show

    trait SimpleApi derives API {
      def operation(a: Person): IO[Person]
    }

    val impl: SimpleApi = a => IO.pure(a.copy(age = a.age + 1))

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("operation")(JsonObject("a" := Person("John", 42))))
      .flatMap(assertSuccess(_, Person("John", 43)))
  }

  test("two params") {

    trait SimpleApi derives API {
      def operation(a: Int, b: String): IO[String]
    }

    val impl: SimpleApi = (a, b) => IO.pure(s"$a $b")

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("operation")(JsonObject("a" := 42, "b" := "John")))
      .flatMap(assertSuccess(_, "42 John"))
  }
}
