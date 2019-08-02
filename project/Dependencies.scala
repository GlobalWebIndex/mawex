import sbt._

object Dependencies {

  val akkaVersion                       = "2.5.23"

  lazy val clist                        = Seq(
    "org.backuity.clist"            %%    "clist-core"                                % "3.5.0",
    "org.backuity.clist"            %%    "clist-macros"                              % "3.5.0"                 % "provided"
  )
  lazy val loggingApi                   = Seq(
    "org.slf4j"                     %     "slf4j-api"                                 % "1.7.25",
    "com.typesafe.scala-logging"    %%    "scala-logging"                             % "3.9.0"
  )

  lazy val loggingImplLogback           = "ch.qos.logback"                %     "logback-classic"                           % "1.2.3"

  lazy val akkaActor                    = "com.typesafe.akka"             %%    "akka-actor"                                % akkaVersion
  lazy val akkaCluster                  = "com.typesafe.akka"             %%    "akka-cluster"                              % akkaVersion
  lazy val akkaClusterTools             = "com.typesafe.akka"             %%    "akka-cluster-tools"                        % akkaVersion
  lazy val akkaClusterCustomDowning     = "com.github.TanUkkii007"        %%    "akka-cluster-custom-downing"               % "0.0.12"
  lazy val akkaPersistence              = "com.typesafe.akka"             %%    "akka-persistence"                          % akkaVersion
  lazy val akkaPersistenceQuery         = "com.typesafe.akka"             %%    "akka-persistence-query"                    % akkaVersion
  lazy val akkaPersistenceInMemory      = "com.github.dnvriend"           %%    "akka-persistence-inmemory"                 % "2.5.15.1"
  lazy val akkaKryoSerialization        = "net.globalwebindex"            %%    "akka-kryo-serialization"                   % "0.5.4" // adhoc published before PR is merged https://github.com/romix/akka-kryo-serialization/pull/124
  lazy val akkaSlf4j                    = "com.typesafe.akka"             %%    "akka-slf4j"                                % akkaVersion
  lazy val akkaTestkit                  = "com.typesafe.akka"             %%    "akka-testkit"                              % akkaVersion             % "test"

  lazy val scalatest                    = "org.scalatest"                 %%    "scalatest"                                 % "3.0.5"                 % "test"
  lazy val k8sJavaClient                = "io.kubernetes"                 %     "client-java"                               % "3.0.0"
  lazy val fabric8JavaClient            = "io.fabric8"                    %     "kubernetes-client"                         % "4.1.0" exclude("io.sundr", "sundr-codegen")

}
