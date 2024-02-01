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

object ServerTests extends SimpleIOSuite {
  pureTest("no ops") {
    trait SimpleApi derives API
    success
  }

  private def request[A: Encoder](
    method: String
  )(
    body: A
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
      .run(request("op")(()))
      .flatMap(assertSuccess(_, 42))
  }

  test("one op with param") {
    trait SimpleApi derives API {
      def operation(a: Int): IO[Int]
    }

    val impl: SimpleApi = a => IO.pure(a + 1)

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("operation")(42))
      .flatMap(assertSuccess(_, 43))
  }

  test("one op with more complex param") {
    case class Person(name: String, age: Int) derives Codec.AsObject, Eq

    trait SimpleApi derives API {
      def operation(a: Person): IO[Person]
    }

    val impl: SimpleApi = a => IO.pure(a.copy(age = a.age + 1))

    API[SimpleApi]
      .toRoutes(impl)
      .run(request("operation")(Person("John", 42)))
      .flatMap(assertSuccess(_, Person("John", 43)))
  }
}
