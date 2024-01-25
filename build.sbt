ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List( /* tlGitHubDev() */ )
ThisBuild / tlSonatypeUseLegacyHost := false

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x.cross(CrossVersion.full))

val compilerPlugins = List(
  crossPlugin("org.polyvariant" % "better-tostring" % "0.3.17")
)

val Scala213 = "2.13.8"

ThisBuild / scalaVersion := Scala213

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / tlFatalWarnings := false
ThisBuild / tlFatalWarningsInCi := false

val commonSettings = Seq(
  libraryDependencies ++= compilerPlugins
)

lazy val core = project
  .settings(
    name := "todo-core",
    commonSettings,
  )

lazy val root = project
  .in(file("."))
  .aggregate(core)
  .settings(
    Compile / doc / sources := Seq()
  )
  .enablePlugins(NoPublishPlugin)
