import gwi.sbt.CommonPlugin
import gwi.sbt.CommonPlugin.autoImport._

crossScalaVersions in ThisBuild := Seq("2.11.8", "2.12.1")
organization in ThisBuild := "net.globalwebindex"
fork in Test in ThisBuild := true

lazy val mawex = (project in file("."))
  .aggregate(api, core, example)

lazy val api = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(name := "mawex-api")
  .settings(libraryDependencies ++= akkaDeps ++ testingDeps)
  .settings(publishSettings("GlobalWebIndex", "mawex-api", s3Resolver))

lazy val core = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-core")
  .settings(publishSettings("GlobalWebIndex", "mawex-core", s3Resolver))
  .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("java:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
  .dependsOn(api % "compile->compile;test->test")

lazy val example = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-example-worker")
  .settings(assemblySettings("mawex-example-worker", None))
  .settings(copyJarTo(s"gwiq/mawex:$version", "gwiq", "mawex-example-worker", "mawex"))
  .dependsOn(core % "compile->compile;test->test")

