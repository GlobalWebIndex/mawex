
lazy val mawexVersion     = "0.1.6"

lazy val githubOrgUrl     = "https://github.com/GlobalWebIndex"

version in ThisBuild := mawexVersion
crossScalaVersions in ThisBuild := Seq("2.12.3", "2.11.8")
organization in ThisBuild := "net.globalwebindex"

lazy val mawex = (project in file("."))
  .aggregate(`mawex-api`, `mawex-core`, `mawex-example-worker`, `mawex-example-client`)

lazy val `mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(name := "mawex-api")
  .settings(libraryDependencies ++= clist ++ Seq(akkaCluster, akkaActor, akkaPersistence, akkaPersistenceRedis, akkaKryoSerialization, akkaClusterCustomDowning, akkaTestkit, scalatest))
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-core")
  .settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("openjdk:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example-worker` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-example-worker")
  .settings(fork in Test := true)
  .settings(assemblySettings("mawex-example-worker", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("openjdk:8", "gwiq", "mawex-example-worker", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")

/** Virtual project that exists merely for building a client docker image */
lazy val `mawex-example-client` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-example-client")
  .settings(test in Test := {})
  .settings(target := baseDirectory.value / "target-dry-run")
  .settings(assemblySettings("mawex-example-worker", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("java:8", "gwiq", "mawex-example-client", "gwi.mawex.Client"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")
