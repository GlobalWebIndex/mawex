import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtdocker.DockerPlugin
import sbtdocker.DockerPlugin.autoImport._
import sbtdocker.mutable.Dockerfile


object Build extends sbt.Build {

  lazy val akkaVersion = "2.4.11"

  lazy val testSettings = Seq(
    testOptions in Test += Tests.Argument("-oDFI"),
    fork in Test := true,
    initialCommands in (Test, console) := """ammonite.repl.Main().run()"""
  )

  lazy val libraryDeps = Seq(
    "org.backuity.clist"          %%  "clist-core"                  % "3.2.2",
    "org.backuity.clist"          %%  "clist-macros"                % "3.2.2"       % "provided",
    "org.iq80.leveldb"            %   "leveldb"                     % "0.9",
    "org.fusesource.leveldbjni"   %   "leveldbjni-all"              % "1.8",
    "com.typesafe.akka"           %%  "akka-cluster"                % akkaVersion,
    "com.typesafe.akka"           %%  "akka-cluster-tools"          % akkaVersion,
    "com.typesafe.akka"           %%  "akka-persistence"            % akkaVersion,
    "com.typesafe.akka"           %%  "akka-testkit"                % akkaVersion   % "test",
    "com.github.dnvriend"         %%  "akka-persistence-inmemory"   % "1.3.10"      % "test",
    "org.scalatest"               %%  "scalatest"                   % "3.0.0"       % "test",
    "com.lihaoyi"                 %   "ammonite-repl"               % "0.7.7"       % "test" cross CrossVersion.full
  )

  lazy val sharedSettings = Seq(
    organization := "net.globalwebindex",
    version := "0.01-SNAPSHOT",
    scalaVersion := "2.11.8",
    offline := true,
    assembleArtifact := false,
    scalacOptions ++= Seq(
      "-unchecked", "-deprecation", "-feature", "-Xfatal-warnings",
      "-Xlint", "-Xfuture",
      "-Yinline-warnings", "-Ywarn-adapted-args", "-Ywarn-inaccessible",
      "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Yno-adapted-args"
    ),
    libraryDependencies ++= libraryDeps,
    autoCompilerPlugins := true,
    cancelable in Global := true,
    assemblyMergeStrategy in assembly := {
        case PathList("org", "iq80", "leveldb", xs @ _*) => MergeStrategy.first
        case x => (assemblyMergeStrategy in assembly).value(x)
      },
    resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      Resolver.typesafeRepo("releases"),
      Resolver.mavenLocal
    )
  ) ++ testSettings


  val publishSettings = Seq(
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    publishTo := Some("S3 Snapshots" at "s3://maven.globalwebindex.net.s3-website-eu-west-1.amazonaws.com/snapshots"),
    pomExtra :=
      <url>https://github.com/GlobalWebIndex/mawex</url>
        <licenses>
          <license>
            <name>The MIT License (MIT)</name>
            <url>http://opensource.org/licenses/MIT</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:GlobalWebIndex/mawex.git</url>
          <connection>scm:git:git@github.com:GlobalWebIndex/mawex.git</connection>
        </scm>
        <developers>
          <developer>
            <id>l15k4</id>
            <name>Jakub Liska</name>
            <email>jakub@globalwebindex.net</email>
          </developer>
        </developers>
  )

  val workingDir = SettingKey[File]("working-dir", "Working directory path for running applications")
  def assemblySettings(appName: String, mainClassFqn: Option[String]) = {
    Seq(
      assembleArtifact := true,
      assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false, includeDependency = false),
      assemblyJarName in assembly := s"$appName.jar",
      assemblyJarName in assemblyPackageDependency := s"$appName-deps.jar",
      workingDir := baseDirectory.value / "deploy",
      cleanFiles += baseDirectory.value / "deploy" / "bin",
      baseDirectory in run := workingDir.value,
      baseDirectory in runMain := workingDir.value,
      test in assembly := {},
      test in assemblyPackageDependency := {},
      mainClass in assembly := mainClassFqn, // Note that sbt-assembly cannot assemble jar with multiple main classes use SBT instead
      aggregate in assembly := false,
      aggregate in assemblyPackageDependency := false,
      assemblyOutputPath in assembly := workingDir.value / "bin" / (assemblyJarName in assembly).value,
      assemblyOutputPath in assemblyPackageDependency := workingDir.value / "bin" / (assemblyJarName in assemblyPackageDependency).value
    )
  }

  def deploySettings(baseImageName: String, repoName: String, appName: String, mainClassFqn: String, extraClasspath: Option[String] = None) = {
    Seq(
      docker <<= (docker dependsOn(assembly, assemblyPackageDependency)),
      dockerfile in docker :=
        new Dockerfile {
          from(baseImageName)
          run("/bin/mkdir", s"/opt/$appName")
          DockerUtils.dockerCopySorted(workingDir.value.absolutePath, s"/opt/$appName") { case (sourcePath, targetPath) => copy(new File(sourcePath), new File(targetPath)) }
          workDir(s"/opt/$appName")
          entryPoint("java", "-cp", s"bin/*" + extraClasspath.map(":" + _).getOrElse(""), mainClassFqn)
        },
      imageNames in docker := Seq(
        ImageName(s"$repoName/$appName:${version.value}"),
        ImageName(s"$repoName/$appName:latest")
      )
    )
  }


  lazy val api = (project in file("src/api"))
    .settings(name := "mawex-api")
    .settings(publishSettings)
    .settings(sharedSettings)


  lazy val core = (project in file("src/core"))
    .enablePlugins(DockerPlugin)
    .settings(name := "mawex")
    .settings(sharedSettings)
    .settings(assemblySettings("mawex", Some("gwi.mawex.Launcher")))
    .settings(deploySettings("java:8", "gwiq", "mawex", "gwi.mawex.Launcher"))
    .dependsOn(api)

  lazy val example = (project in file("src/example"))
    .enablePlugins(DockerPlugin)
    .settings(name := "mawex-example")
    .settings(sharedSettings)
    .settings(assemblySettings("mawex-example", Some("example.Launcher")))
    .settings(deploySettings("java:8", "gwiq", "mawex-example", "example.Launcher"))
    .dependsOn(core)

}