package gwi.mawex

import java.io.File

import akka.actor._
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.RemoteService._
import gwi.mawex.master._
import org.backuity.clist._
import org.backuity.clist.util.Read

import scala.collection.JavaConverters._

protected[mawex] trait MawexService { this: Command =>
  def run(): Unit
}

protected[mawex] sealed trait RemoteService extends MawexService { this: Command =>
  import RemoteService._
  implicit val addressRead = Read.reads("address") { str =>
    val Array(host,port) = str.split(":")
    HostAddress(host,port.toInt)
  }

  var hostAddress = opt[HostAddress](useEnv = true, default = HostAddress("localhost", 2551), description = "host:port of this node")
}

object RemoteService {
  case class HostAddress(host: String, port: Int)

  def buildRemoteSystem(address: Address, appConfFileOpt: Option[File]) =
    ActorSystem(
      address.system,
      appConfFileOpt
        .map(ConfigFactory.parseFile)
        .getOrElse(ConfigFactory.empty())
        .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(address.host.getOrElse("localhost")))
        .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(address.port.getOrElse(0)))
        .withFallback(ConfigFactory.parseResources("serialization.conf"))
        .withFallback(ConfigFactory.parseResources("worker.conf"))
        .withFallback(ConfigFactory.load())
    )
}

protected[mawex] trait MountingService extends LazyLogging { this: Command =>
  var mountPath      = opt[Option[String]](useEnv = true, name="mount-path", description = "mount path to pass files to executor")

  protected def getMountPath: Option[String] =
    mountPath.map( path => if (path.endsWith("/")) path else path + "/" )

  protected def getAppConf: Option[File] = {
    val appConfOpt = getMountPath.map { path =>
      new File(s"${path}application.conf")
    }.filter(_.exists)
    appConfOpt.foreach(f => logger.debug(s"App configuration loaded from ${f.getAbsolutePath}") )
    appConfOpt
  }
}

protected[mawex] trait ClusterService extends RemoteService { this: Command =>

  implicit val listRead = Read.reads("list") { str =>
    str.split(",").filter(_.nonEmpty).toList
  }

  implicit val addressesRead = Read.reads("addresses") { str =>
    str.split(",").map (_.split(":")).map( arr => HostAddress(arr(0), arr(1).toInt) ).toList
  }

  var seedNodes = opt[List[HostAddress]](useEnv = true, default = List(HostAddress("master", 2552)), description = "12.34.56.78:2551,12.34.56.79:2552")
}

object ClusterService {

  def buildClusterSystem(hostAddress: HostAddress, seedNodes: List[HostAddress], memberSize: Int, appConfFileOpt: Option[File]) =
    ActorSystem(
      "ClusterSystem",
      appConfFileOpt
        .map(ConfigFactory.parseFile)
        .getOrElse(ConfigFactory.empty())
        .withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seedNodes.map { case HostAddress(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }.asJava))
        .withValue("akka.cluster.min-nr-of-members", ConfigValueFactory.fromAnyRef(memberSize))
        .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(hostAddress.host))
        .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(hostAddress.port))
        .withFallback(ConfigFactory.parseResources("serialization.conf"))
        .withFallback(ConfigFactory.parseResources("master.conf"))
        .withFallback(ConfigFactory.load())
    )

  def clusterSingletonActorRef(masterConf: Master.Config, system: ActorSystem): ActorRef = {
    system.actorOf(
      ClusterSingletonManager.props(
        Master.props(masterConf),
        PoisonPill,
        ClusterSingletonManagerSettings(system).withRole("backend")
      ),
      masterConf.masterId
    )
  }

}
