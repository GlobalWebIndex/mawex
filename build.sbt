import sbt.Keys.name

lazy val mawexVersion     = "0.1.8"

lazy val githubOrgUrl     = "https://github.com/GlobalWebIndex"

version in ThisBuild := mawexVersion
crossScalaVersions in ThisBuild := Seq("2.12.3", "2.11.8")
organization in ThisBuild := "net.globalwebindex"

lazy val mawex = (project in file("."))
  .settings(aggregate in update := false)
  .settings(publish := { })
  .aggregate(`mawex-api`, `mawex-core`, `mawex-example-worker`, `mawex-example-client`, `mawex-example-master`)

lazy val `mawex-api` = (project in file("src/api"))
  .enablePlugins(CommonPlugin)
  .settings(name := "mawex-api")
  .settings(publishSettings("globalWebIndex", "mawex-api", s3Resolver))
  .settings(libraryDependencies ++= Seq(
      akkaCluster, akkaClusterTools, akkaActor, akkaPersistence, akkaKryoSerialization, akkaClusterCustomDowning
    ) ++ clist
  )

lazy val `mawex-core` = (project in file("src/core"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(name := "mawex-core")
  .settings(fork in Test := true)
  .settings(libraryDependencies ++= Seq(akkaPersistenceInMemory % "test", akkaTestkit, scalatest))
  .settings(publishSettings("globalWebIndex", "mawex-core", s3Resolver))
  .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
  .settings(deploySettings("openjdk:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
  .dependsOn(`mawex-api` % "compile->compile;test->test")

/** EXAMPLES */

def virtualCommonSettings(name: String, targetDir: String) = Seq(
    test in Test := {},
    publish := {},
    target := baseDirectory.value / targetDir
  ) ++ assemblySettings(name, Some("gwi.mawex.Launcher")) ++ deploySettings("openjdk:8", "gwiq", name, "gwi.mawex.Launcher")

/** Virtual project that exists merely for building a client docker image */
lazy val `mawex-example-worker` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(virtualCommonSettings("mawex-example-worker", "target-worker"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")

/** Virtual project that exists merely for building a client docker image */
lazy val `mawex-example-client` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(virtualCommonSettings("mawex-example-client", "target-client"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")

lazy val `mawex-example-master` = (project in file("src/example"))
  .enablePlugins(CommonPlugin, DockerPlugin)
  .settings(virtualCommonSettings("mawex-example-master", "target"))
  .dependsOn(`mawex-core` % "compile->compile;test->test")
