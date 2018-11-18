package gwi.mawex

import akka.actor._
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
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

  def buildRemoteSystem(address: Address) =
    ActorSystem(
      address.system,
      ConfigFactory.parseString(
        s"""
        akka {
          actor.provider = "remote"
          remote {
            enabled-transports = ["akka.remote.netty.tcp"]
            netty.tcp {
              hostname = ${address.host.getOrElse("localhost")}
              port = ${address.port.getOrElse(0)}
              bind-hostname = "0.0.0.0"
            }
          }
        }
        """.stripMargin
      ).withFallback(ConfigFactory.parseResources("serialization.conf"))
        .withFallback(ConfigFactory.load())
    )
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

  def buildClusterSystem(hostAddress: HostAddress, seedNodes: List[HostAddress], memberSize: Int) =
    ActorSystem(
      "ClusterSystem",
      ConfigFactory.parseString(
        s"""
          akka {
            actor.provider = "cluster"
            cluster {
              downing-provider-class = "tanukki.akka.cluster.autodown.OldestAutoDowning"
              roles = [backend]
              min-nr-of-members = $memberSize
            }
            extensions = ["akka.cluster.client.ClusterClientReceptionist", "akka.cluster.pubsub.DistributedPubSub", "com.romix.akka.serialization.kryo.KryoSerializationExtension$$"]
            remote.netty.tcp {
               hostname = ${hostAddress.host}
               port = ${hostAddress.port}
               bind-hostname = "0.0.0.0"
            }
          }
          custom-downing {
            stable-after = 20s
            shutdown-actor-system-on-resolution = true
            oldest-auto-downing {
              oldest-member-role = "backend"
              down-if-alone = true
            }
          }
          """.stripMargin
      ).withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seedNodes.map { case HostAddress(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }.asJava))
        .withFallback(ConfigFactory.parseResources("serialization.conf"))
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
