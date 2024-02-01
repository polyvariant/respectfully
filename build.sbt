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

val commonSettings = Seq(
  libraryDependencies ++=
    List(
      "org.http4s" %%% "http4s-client" % "0.23.25",
      "org.http4s" %%% "http4s-circe" % "0.23.25",
      "com.kubukoz" %% "debug-utils" % "1.1.3",
      "org.typelevel" %%% "kittens" % "3.2.0" % Test,
      "com.disneystreaming" %%% "weaver-cats" % "0.8.4" % Test,
      "com.disneystreaming" %%% "weaver-scalacheck" % "0.8.4" % Test,
    ) ++
      compilerPlugins,
  scalacOptions ++= Seq(
    "-Wunused:all"
  ),
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(
    name := "respectfully",
    commonSettings,
  )
  .jvmSettings(
    Test / fork := true
  )

lazy val example = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .dependsOn(core)
  .settings(
    name := "respectfully-example",
    commonSettings,
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-ember-client" % "0.23.25",
      "org.http4s" %%% "http4s-ember-server" % "0.23.25",
      "io.chrisdavenport" %%% "crossplatformioapp" % "0.1.0",
    ),
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  )
  .jvmSettings(
    Compile / fork := true
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "epollcat" % "0.1.6"
    )
  )
  .enablePlugins(NoPublishPlugin)

lazy val root = tlCrossRootProject
  .aggregate(
    core,
    example,
  )
