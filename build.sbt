import Dependencies._
import Deploy._

lazy val s3Resolver = "S3 Snapshots" at "s3://public.maven.globalwebindex.net.s3-eu-west-1.amazonaws.com/snapshots"

crossScalaVersions in ThisBuild := Seq("2.12.6", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
libraryDependencies in ThisBuild ++= clist ++ loggingApi ++ Seq(akkaActor, akkaClusterTools)
resolvers in ThisBuild ++= Seq(
  "Maven Central Google Mirror EU" at "https://maven-central-eu.storage-download.googleapis.com/repos/central/data/",
  Resolver.bintrayRepo("tanukkii007", "maven"),
  "dnvriend" at "http://dl.bintray.com/dnvriend/maven",
  s3Resolver
)
version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))
cancelable in ThisBuild := true
publishArtifact in ThisBuild := false
stage in (ThisBuild, Docker) := null

lazy val `mawex-api` = (project in file("src/api"))
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++=
    Seq(loggingImplLogback, akkaCluster, akkaPersistence, akkaPersistenceQuery, akkaKryoSerialization, fabric8JavaClient, k8sJavaClient, akkaClusterCustomDowning, akkaPersistenceInMemory, akkaTestkit, scalatest)
  ).settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(Deploy.settings("gwiq", "mawex-core", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example` = (project in file("src/example"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaPersistence, akkaPersistenceRedis, akkaPersistenceDynamoDB, loggingImplLogback))
  .settings(Deploy.settings("gwiq", "mawex-example", "gwi.mawex.ExampleLauncher"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")
