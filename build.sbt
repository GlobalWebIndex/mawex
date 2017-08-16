
lazy val mawexVersion     = "0.1.1"
lazy val saturatorVersion = "0.0.7"

lazy val githubOrgUrl     = "https://github.com/GlobalWebIndex"

version in ThisBuild := mawexVersion
crossScalaVersions in ThisBuild := Seq("2.12.3", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
fork in Test in ThisBuild := true

lazy val mawex = (project in file("."))
  .aggregate(`mawex-api`, `mawex-core`, `mawex-example-worker`)

lazy val `mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(name := "mawex-api")
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaActor, akkaPersistence, akkaPersistenceRedis, akkaKryoSerialization, akkaClusterCustomDowning, akkaTestkit, scalatest))
  .settings(publishSettings("GlobalWebIndex", "mawex-api", s3Resolver))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-core")
  .settings(publishSettings("GlobalWebIndex", "mawex-core", s3Resolver))
  .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("java:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
  .dependsOn(
    `mawex-api` % "compile->compile;test->test",
    ProjectRef(uri(s"$githubOrgUrl/saturator.git#v$saturatorVersion"), "saturator-api")
  )

lazy val `mawex-example-worker` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-example-worker")
  .settings(assemblySettings("mawex-example-worker", None))
  .settings(copyJarTo(s"gwiq/mawex:$mawexVersion", "gwiq", "mawex-example-worker", "mawex"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")

