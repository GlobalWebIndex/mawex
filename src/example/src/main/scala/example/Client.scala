package example

import akka.actor.{ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory
import gwi.mawex.RemoteMasterProxy
import gwi.mawex.Service.Address

object Client {

  def startRemoteClient(hostAddress: Address, seedNodes: List[Address]): Unit = {
    val conf = ConfigFactory.parseString(
      s"""
      akka {
        actor {
          provider = cluster
          kryo.idstrategy = automatic
        }
        remote.netty.tcp.hostname = ${hostAddress.host}
        remote.netty.tcp.port = ${hostAddress.port}
      }
      """.stripMargin
    ).withFallback(ConfigFactory.load("serialization"))
      .withFallback(ConfigFactory.load())

    implicit val system = ActorSystem("ClusterSystem", conf)

    val initialContacts = seedNodes.map { case Address(host, port) => RootActorPath(AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port")) / "system" / "receptionist" }.toSet
    val seedNodeAddresses = seedNodes.map { case Address(host, port) => AddressFromURIString(s"akka.tcp://ClusterSystem@$host:$port") }.toVector

    Cluster(system).joinSeedNodes(seedNodeAddresses)

    val masterProxy = system.actorOf(RemoteMasterProxy.props(initialContacts), "masterProxy")
    system.actorOf(Props(classOf[Producer], masterProxy), "producer")
    system.actorOf(Props[Consumer], "consumer")
  }

}
