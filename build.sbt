
version in ThisBuild := "0.3.6"
crossScalaVersions in ThisBuild := Seq("2.12.4", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
libraryDependencies in ThisBuild ++= clist ++ loggingApi

lazy val tempKryoDep = "net.globalwebindex" %% "akka-kryo-serialization" % "0.5.3-SNAPSHOT" // adhoc published before PR is merged https://github.com/romix/akka-kryo-serialization/pull/124

lazy val mawex = (project in file("."))
  .settings(aggregate in update := false)
  .settings(publish := { })
  .aggregate(`Mawex-api`, `Mawex-core`, `Mawex-example`)

lazy val `Mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))
  .settings(libraryDependencies ++= Seq(akkaActor, akkaClusterTools))

lazy val `Mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaPersistence, tempKryoDep, akkaClusterCustomDowning, akkaPersistenceInMemory % "test", akkaTestkit, scalatest))
  .settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(deploy(DeployDef(config("app") extend Compile, "openjdk:8", "gwiq", "mawex", "gwi.mawex.Launcher")))
  .dependsOn(`Mawex-api` % "compile->compile;test->test")

lazy val `Mawex-example` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(publish := {})
  .settings(
    deploy(
      DeployDef(config("server") extend Compile, "openjdk:8", "gwiq", "mawex-example-server", "gwi.mawex.Launcher"),
      DeployDef(config("client") extend Compile, "openjdk:8", "gwiq", "mawex-example-client", "gwi.mawex.Client")
    )
  ).dependsOn(`Mawex-core` % "compile->compile;test->test")
