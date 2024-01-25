ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2024)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))
ThisBuild / tlSonatypeUseLegacyHost := false

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x.cross(CrossVersion.full))

val compilerPlugins = List(
  crossPlugin("org.polyvariant" % "better-tostring" % "0.3.17")
)

val Scala3 = "3.3.1"

ThisBuild / scalaVersion := Scala3

ThisBuild / tlFatalWarnings := false
ThisBuild / tlFatalWarningsInCi := false

val commonSettings = Seq(
  libraryDependencies ++=
    List(
      "org.http4s" %%% "http4s-client" % "0.23.25",
      "org.http4s" %%% "http4s-circe" % "0.23.25",
      "com.kubukoz" %% "debug-utils" % "1.1.3",
      "org.http4s" %%% "http4s-ember-client" % "0.23.25" % Test,
      "org.http4s" %%% "http4s-ember-server" % "0.23.25" % Test,
    ) ++
      compilerPlugins,
  scalacOptions ++= Seq(
    "-Wunused:all"
  ),
  Test / fork := true,
)

lazy val core = project
  .settings(
    name := "respectfully",
    commonSettings,
  )

lazy val root = project
  .in(file("."))
  .aggregate(core)
  .enablePlugins(NoPublishPlugin)
