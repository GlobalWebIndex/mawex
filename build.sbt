import Dependencies._
import Deploy._

lazy val javaDockerImage  = "anapsix/alpine-java:8u144b01_jdk_unlimited"
lazy val s3Resolver       = "S3 Snapshots" at "s3://public.maven.globalwebindex.net.s3-eu-west-1.amazonaws.com/snapshots"

lazy val tempKryoDep          = "net.globalwebindex" %% "akka-kryo-serialization" % "0.5.3-SNAPSHOT" // adhoc published before PR is merged https://github.com/romix/akka-kryo-serialization/pull/124
lazy val k8sJavaClientDep     = "io.kubernetes"       % "client-java"             % "3.0.0"
lazy val fabric8JavaClientDep = "io.fabric8"          % "kubernetes-client"       % "4.1.0" exclude("io.sundr", "sundr-codegen")

crossScalaVersions in ThisBuild := Seq("2.12.6", "2.11.8")
organization in ThisBuild := "net.globalwebindex"
libraryDependencies in ThisBuild ++= clist ++ loggingApi
resolvers in ThisBuild ++= Seq(
  "Maven Central Google Mirror EU" at "https://maven-central-eu.storage-download.googleapis.com/repos/central/data/",
  Resolver.bintrayRepo("tanukkii007", "maven"),
  s3Resolver
)
version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))
cancelable in ThisBuild := true

lazy val `mawex-api` = (project in file("src/api"))
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))
  .settings(libraryDependencies ++= Seq(akkaActor, akkaClusterTools))

lazy val mawex = (project in file("src/core"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++=
    Seq(loggingImplLogback, akkaCluster, akkaPersistence, tempKryoDep, fabric8JavaClientDep, k8sJavaClientDep, akkaClusterCustomDowning, akkaPersistenceInMemory % "test", akkaTestkit, scalatest)
  ).settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(Deploy.settings("gwiq", "mawex-core", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example` = (project in file("src/example"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(publish := {})
  .settings(libraryDependencies ++= Seq(loggingImplLogback))
  .settings(Deploy.settings("gwiq", "mawex-example", "gwi.mawex.ExampleLauncher"))
  .dependsOn(mawex % "compile->compile;test->test")
