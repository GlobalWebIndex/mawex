
version in ThisBuild := "0.2.3"
crossScalaVersions in ThisBuild := Seq("2.12.3", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
libraryDependencies in ThisBuild ++= clist ++ loggingApi

lazy val mawex = (project in file("."))
  .settings(aggregate in update := false)
  .settings(publish := { })
  .aggregate(`mawex-api`, `mawex-core`, `mawex-example`)

lazy val `mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaClusterTools, akkaActor, akkaPersistence, akkaKryoSerialization, akkaClusterCustomDowning))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++= Seq(akkaPersistenceInMemory % "test", akkaTestkit, scalatest))
  .settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(deploy(DeployDef(config("app") extend Compile, "openjdk:8", "gwiq", "mawex", "gwi.mawex.Launcher")))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(publish := {})
  .settings(
    deploy(
      DeployDef(config("server") extend Compile, "openjdk:8", "gwiq", "mawex-example-server", "gwi.mawex.Launcher"),
      DeployDef(config("client") extend Compile, "openjdk:8", "gwiq", "mawex-example-client", "gwi.mawex.Client")
    )
  ).dependsOn(`mawex-core` % "compile->compile;test->test")
