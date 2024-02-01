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
import org.http4s.Request
import org.http4s.circe.CirceEntityCodec._
import respectfully.API
import weaver._
import org.http4s.Response
import io.circe.Decoder
import cats.kernel.Eq
import cats.derived._
import io.circe.Codec
import org.http4s.client.Client
import org.typelevel.ci.CIString
import org.http4s.Uri
import org.http4s.implicits._
import cats.effect.kernel.Ref
import io.circe.Json
import org.typelevel.vault.Key
import cats.effect.SyncIO

object ClientTests extends SimpleIOSuite {

  private val BodyJson = Key.newKey[SyncIO, Json].unsafeRunSync()

  private def fakeClient[Out: Encoder](
    returnValue: Out
  ): IO[(Client[IO], Uri, IO[Request[IO]])] = Ref[IO].of(Option.empty[Request[IO]]).map { ref =>
    val client: Client[IO] = Client { req =>
      req
        .as[Json]
        .flatMap { body =>
          ref.update {
            case None    => Some(req.withAttribute(BodyJson, body))
            case Some(_) => sys.error("Request already captured.")
          }
        }
        .as(Response[IO]().withEntity(returnValue))
        .toResource

    }

    (
      client,
      uri"/",
      ref.get.map(_.getOrElse(sys.error("No request captured."))),
    )
  }

  private def methodHeader(req: Request[IO]): String =
    req.headers.get(CIString("X-Method")).get.head.value

  private def bodyJsonDecode[A: Decoder](req: Request[IO]): A =
    req.attributes.lookup(BodyJson).get.as[A].toTry.get

  test("one op") {
    trait SimpleApi derives API {
      def op(): IO[Int]
    }

    fakeClient(42).flatMap { (client, uri, captured) =>
      API[SimpleApi].toClient(client, uri).op().map(assert.eql(42, _)) *>
        captured.map { req =>
          assert.eql("op", methodHeader(req)) &&
          succeed(bodyJsonDecode[Unit](req))
        }
    }
  }

  test("one op with param") {
    trait SimpleApi derives API {
      def operation(a: Int): IO[String]
    }

    fakeClient("output").flatMap { (client, uri, captured) =>
      API[SimpleApi].toClient(client, uri).operation(42).map(assert.eql("output", _)) *>
        captured.map { req =>
          assert.eql("operation", methodHeader(req)) &&
          assert.eql(42, bodyJsonDecode[Int](req))
        }
    }
  }

  test("one op with more complex param") {
    case class Person(name: String, age: Int) derives Codec.AsObject, Eq

    trait SimpleApi derives API {
      def operation(a: Person): IO[Person]
    }

    fakeClient(Person("John", 43)).flatMap { (client, uri, captured) =>
      API[SimpleApi]
        .toClient(client, uri)
        .operation(Person("John", 42))
        .map(assert.eql(Person("John", 43), _)) *>
        captured.map { req =>
          assert.eql("operation", methodHeader(req)) &&
          assert.eql(Person("John", 42), bodyJsonDecode[Person](req))
        }
    }
  }

}
