import Dependencies._

crossScalaVersions in ThisBuild := Seq("2.12.6", "2.11.8")
libraryDependencies in ThisBuild ++= clist ++ loggingApi ++ Seq(akkaActor, akkaClusterTools)
licenses in ThisBuild += ("MIT", url("http://opensource.org/licenses/MIT"))
resolvers in ThisBuild ++= Seq(
  "Maven Central Google Mirror EU" at "https://maven-central-eu.storage-download.googleapis.com/repos/central/data/",
  Resolver.bintrayRepo("tanukkii007", "maven"),
  Resolver.bintrayRepo("dnvriend", "maven"),
  "S3 Snapshots" at "s3://public.maven.globalwebindex.net.s3-eu-west-1.amazonaws.com/snapshots" // TODO this is because of akka-kryo-serialization
)
version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))
cancelable in ThisBuild := true
publishArtifact in ThisBuild := false
stage in (ThisBuild, Docker) := null
publishConfiguration in ThisBuild := publishConfiguration.value.withOverwrite(true)

lazy val `mawex-api` = (project in file("src/api"))
  .settings(Deploy.publishSettings("mawex"))

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(fork in Test := true)
  .settings(libraryDependencies ++=
    Seq(
      akkaCluster, akkaPersistence, akkaPersistenceQuery, akkaKryoSerialization, akkaClusterCustomDowning,
      fabric8JavaClient, k8sJavaClient, loggingImplLogback, akkaPersistenceInMemory % "test", akkaTestkit, scalatest
    )
  ).settings(Deploy.publishSettings("mawex"))
  .settings(Deploy.settings("gwiq", "mawex-core", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

lazy val `mawex-example` = (project in file("src/example"))
  .enablePlugins(DockerPlugin, SmallerDockerPlugin, JavaAppPackaging)
  .settings(version := "latest")
  .settings(libraryDependencies ++= Seq(akkaCluster, akkaPersistence, akkaPersistenceInMemory, loggingImplLogback))
  .settings(skip in publish := true)
  .settings(Deploy.settings("gwiq", "mawex-example", "gwi.mawex.ExampleLauncher"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")
