package gwi.mawex

import akka.actor.{ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import gwi.mawex.RemoteService.HostAddress
import gwi.mawex.executor.ExecutorCmd
import gwi.mawex.master.MasterCmd
import gwi.mawex.worker.WorkerCmd
import org.backuity.clist.{Cli, Command}

object ExampleLauncher {
  def main(args: Array[String]): Unit =
    Cli.parse(args)
      .withProgramName("mawex")
      .withCommands(MasterCmd, WorkerCmd, ExecutorCmd, ClientCmd)
      .foreach(_.run())
}

object ClientCmd extends Command(name = "client", description = "launches client") with ClusterService {

  val MasterId = "master"

  private def startRemoteClient(hostAddress: HostAddress, seedNodes: List[HostAddress]): Unit = {
    val conf = ConfigFactory.parseString(
      s"""
      akka {
        actor.provider = "cluster"
        remote.netty.tcp.hostname = ${hostAddress.host}
        remote.netty.tcp.port = ${hostAddress.port}
      }
      """.stripMargin
    ).withFallback(ConfigFactory.parseResources("serialization.conf"))
      .withFallback(ConfigFactory.parseResources("reference.conf")).resolve()

    implicit val system = ActorSystem("ClusterSystem", conf)

    val initialContacts = seedNodes.map { case HostAddress(host, port) => RootActorPath(AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port")) / "system" / "receptionist" }.toSet
    val seedNodeAddresses = seedNodes.map { case HostAddress(host, port) => AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port") }.toVector

    Cluster(system).joinSeedNodes(seedNodeAddresses)

    val masterProxy = system.actorOf(RemoteMasterProxy.props(MasterId, initialContacts), "masterProxy")
    system.actorOf(Props(classOf[Producer], masterProxy), "producer")
    system.actorOf(Props[Consumer], "consumer")
  }

  def run(): Unit = startRemoteClient(hostAddress, seedNodes)
}
