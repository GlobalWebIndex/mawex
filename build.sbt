
version in ThisBuild := "0.0.2"
crossScalaVersions in ThisBuild := Seq("2.12.1", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
fork in Test in ThisBuild := true

lazy val mawex = (project in file("."))
  .aggregate(`mawex-api`, `mawex-core`, `mawex-example-worker`)

lazy val `mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(name := "mawex-api")
  .settings(libraryDependencies ++= akkaDeps ++ testingDeps)
  .settings(publishSettings("GlobalWebIndex", "mawex-api", s3Resolver))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-core")
  .settings(publishSettings("GlobalWebIndex", "mawex-core", s3Resolver))
  .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("java:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example-worker` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-example-worker")
  .settings(assemblySettings("mawex-example-worker", None))
  .settings(copyJarTo(s"gwiq/mawex:$version", "gwiq", "mawex-example-worker", "mawex"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")

