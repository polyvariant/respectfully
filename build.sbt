ThisBuild / tlBaseVersion := "0.2"
ThisBuild / organization := "org.polyvariant"
ThisBuild / organizationName := "Polyvariant"
ThisBuild / startYear := Some(2024)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))
ThisBuild / tlJdkRelease := Some(17)

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x.cross(CrossVersion.full))

val compilerPlugins = List(
  crossPlugin("org.polyvariant" % "better-tostring" % "0.3.17")
)

val Scala3 = "3.8.4"

ThisBuild / scalaVersion := Scala3

ThisBuild / tlFatalWarnings := false

ThisBuild / mergifyStewardConfig ~= (_.map(_.withMergeMinors(true)))

val commonSettings = Seq(
  libraryDependencies ++=
    List(
      "org.http4s" %%% "http4s-client" % "0.23.35",
      "org.http4s" %%% "http4s-circe" % "0.23.35",
      "com.kubukoz" %% "debug-utils" % "1.1.3",
      "org.typelevel" %%% "kittens" % "3.5.0" % Test,
      "org.typelevel" %%% "weaver-cats" % "0.13.0" % Test,
      "org.typelevel" %%% "weaver-scalacheck" % "0.13.0" % Test,
    ) ++
      compilerPlugins,
  scalacOptions ++= Seq(
    "-Wunused:all",
    "-no-indent",
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
      "org.http4s" %%% "http4s-ember-client" % "0.23.35",
      "org.http4s" %%% "http4s-ember-server" % "0.23.35",
    ),
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  )
  .jvmSettings(
    Compile / fork := true
  )
  .enablePlugins(NoPublishPlugin)

lazy val root = tlCrossRootProject
  .aggregate(
    core,
    example,
  )
