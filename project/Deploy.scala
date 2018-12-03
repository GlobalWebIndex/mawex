import bintray.BintrayKeys._
import sbt.Keys.{developers, homepage, licenses, scmInfo, _}
import sbt.{Developer, _}
import net.globalwebindex.sbt.docker.SmallerDockerPlugin.autoImport._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.SbtNativePackager.autoImport._

object Deploy {

  private[this] def isFrequentlyChangingFile(file: sbt.File): Boolean = {
    val fileName = file.name
    if (fileName.startsWith("net.globalwebindex")) true
    else if (fileName.endsWith(".jar")) false
    else true
  }

  def settings(repository: String, appName: String, mainClassFqn: String): Seq[Def.Setting[_]] = {
    Seq(
      dockerUpdateLatest := false,
      dockerRepository := Some(repository),
      packageName in Docker := appName,
      dockerBaseImage in Docker := "anapsix/alpine-java:8u192b12_jdk_unlimited",
      mainClass in Compile := Some(mainClassFqn),
      defaultLinuxInstallLocation in Docker := s"/opt/$appName",
      dockerUpdateLatest in Docker := false
    ) ++ smallerDockerSettings(isFrequentlyChangingFile)
  }

  def publishSettings(ghProjectName: String) = Seq(
    publishArtifact := true,
    publishMavenStyle := true,
    publishArtifact in Test := false,
    organization := "net.globalwebindex",
    homepage := Some(url(s"https://github.com/GlobalWebIndex/$ghProjectName/blob/master/README.md")),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    developers += Developer("l15k4", "Jakub Liska", "liska.jakub@gmail.com", url("https://github.com/l15k4")),
    scmInfo := Some(ScmInfo(url(s"https://github.com/GlobalWebIndex/$ghProjectName"), s"git@github.com:GlobalWebIndex/$ghProjectName.git")),
    bintrayVcsUrl := Some(s"git@github.com:GlobalWebIndex/$ghProjectName.git"),
    bintrayRepository := "GlobalWebIndex",
    pomIncludeRepository := { _ => false },
    pomExtra :=
      <url>https://github.com/{organization}/{ghProjectName}</url>
        <licenses>
          <license>
            <name>The MIT License (MIT)</name>
            <url>http://opensource.org/licenses/MIT</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:{organization}/{ghProjectName}.git</url>
          <connection>scm:git:git@github.com:{organization}/{ghProjectName}.git</connection>
        </scm>
        <developers>
          <developer>
            <id>l15k4</id>
            <name>Jakub Liska</name>
            <email>liska.jakub@gmail.com</email>
          </developer>
        </developers>
  )
}