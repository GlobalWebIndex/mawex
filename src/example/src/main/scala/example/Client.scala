package example

import akka.actor.{ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.cluster.Cluster
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import com.typesafe.config.ConfigFactory
import gwi.mawex.RemoteMasterProxy
import gwi.mawex.Service.Address

object Client {

  def startRemoteClient(hostAddress: Address, seedNodes: List[Address]): Unit = {
    val conf = ConfigFactory.parseString(
      s"""
         |akka {
         |  actor.provider = cluster
         |  remote.netty.tcp.hostname = ${hostAddress.host}
         |  remote.netty.tcp.port = ${hostAddress.port}
         |}
      """.stripMargin
    )

    implicit val system = ActorSystem("ClusterSystem", conf)

    val initialContacts = seedNodes.map { case Address(host, port) => RootActorPath(AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port")) / "system" / "receptionist" }.toSet
    val seedNodeAddresses = seedNodes.map { case Address(host, port) => AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port") }.toVector

    Cluster(system).joinSeedNodes(seedNodeAddresses)

    val clusterClient = system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
    val masterProxy = system.actorOf(RemoteMasterProxy.props(clusterClient), "masterProxy")
    system.actorOf(Props(classOf[Producer], masterProxy), "producer")
    system.actorOf(Props[Consumer], "consumer")
  }

}
