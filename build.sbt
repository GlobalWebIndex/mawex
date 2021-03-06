import Dependencies._
import Deploy._

crossScalaVersions in ThisBuild := Seq("2.12.6", "2.11.8")
libraryDependencies in ThisBuild ++= clist ++ loggingApi ++ Seq(akkaActor, akkaClusterTools)
resolvers in ThisBuild ++= Seq(
  "Maven Central Google Mirror EU" at "https://maven-central-eu.storage-download.googleapis.com/repos/central/data/",
  Resolver.bintrayRepo("tanukkii007", "maven"),
  Resolver.bintrayRepo("dnvriend", "maven"),
  Resolver.bintrayRepo("l15k4", "GlobalWebIndex")
)
version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))
cancelable in ThisBuild := true
publishArtifact in ThisBuild := false
stage in (ThisBuild, Docker) := null
publishConfiguration in ThisBuild := publishConfiguration.value.withOverwrite(true)

lazy val `mawex-api` = (project in file("src/api"))
  .settings(bintraySettings("GlobalWebIndex", "mawex"))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++=
    Seq(
      akkaCluster, akkaPersistence, akkaPersistenceQuery, akkaKryoSerialization, akkaClusterCustomDowning,
      fabric8JavaClient, k8sJavaClient, loggingImplLogback, akkaPersistenceInMemory % "test", akkaTestkit, scalatest
    )
  ).settings(bintraySettings("GlobalWebIndex", "mawex"))
  .settings(Deploy.settings("gwiq", "mawex-core", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example` = (project in file("src/example"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(version := "latest")
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaPersistence, akkaPersistenceInMemory, loggingImplLogback))
  .settings(Deploy.settings("gwiq", "mawex-example", "gwi.mawex.ExampleLauncher"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")
