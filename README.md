# ResPeCtfully, an instant RPC microlibrary

Respectfully, sometimes you want to just make that effin RPC call. You don't really care whether it's a POST, a GET, whether something is a query or body parameter. Sometimes you just want it to be over so that you can go home and REST.

Pun intended.

Does this sound like you? You've found the one library trully respectful of your time and energy. No-nonsense RPC is just around the corner!

## Setup

Get the dependency:

sbt:

```scala
// %%% for JS/native
"org.polyvariant" %% "respectfully" % version
```

scala-cli

```scala
//> using dep "org.polyvariant::respectfully::version"
```

## Usage

Define your interface and derive `API` for it:

```scala
import respectfully._
import cats.effect._

trait MyApi derives API {
  def send(s: String): IO[Unit]
  def receive(): IO[String]
}
```

Now, you can get a server for "free":

```scala
val impl = new MyApi {
  def send(s: String): IO[Unit] = IO.stub
  def receive(): IO[String] = IO.stub
}

val r: org.http4s.HttpApp[IO] = API[MyApi].toRoutes
```

or a client, given an http4s Client:

```scala
def client(http: org.http4s.Client[IO], baseUri: org.http4s.Uri): MyApi =
  API[MyAPI].toClient(http, baseUri)
```

You know what to do next: plug your routes into a server, or plug a backend into your client - and go home early.
