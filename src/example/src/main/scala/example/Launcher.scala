package example

import akka.actor.{ActorSystem, AddressFromURIString, Props, RootActorPath}
import akka.cluster.Cluster
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import com.typesafe.config.ConfigFactory
import gwi.mawex.Service.Address
import gwi.mawex.{LocalMasterProxy, RemoteMasterProxy, Service}

import scala.concurrent.duration._

object Launcher extends App {

  lazy val seedNodes = List(Address("master", 2552))

  args.toList match {
    case service :: Nil if service == "master-with-local-client" =>
      val Address(host, port) = seedNodes.head
      Service.startBackend("master", host, port, 10.seconds)
      Thread.sleep(5000)
      startLocalClient(0)
    case service :: Nil if service == "master" =>
      val Address(host, port) = seedNodes.head
      Service.startBackend("master", host, port, 10.seconds)
      Thread.sleep(5000)
    case service :: Nil if service == "remote-client" =>
      startRemoteClient()
    case service :: Nil if service == "workers" =>
      Service.startWorker("workers", seedNodes, "default", Class.forName("example.Executor"), Seq.empty)
  }

  private def startLocalClient(port: Int): Unit = {
    val conf = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).withFallback(ConfigFactory.load("master"))
    val system = ActorSystem("ClusterSystem", conf)
    val proxy = system.actorOf(Props[LocalMasterProxy], "localMasterProxy")
    system.actorOf(Props(classOf[Producer], proxy), "producer")
    system.actorOf(Props[Consumer], "consumer")
  }

  private def startRemoteClient(): Unit = {
    val conf = ConfigFactory.load("remote-client")
    implicit val system = ActorSystem("ClusterSystem", conf)
    val initialContacts = Set(RootActorPath(AddressFromURIString("akka.tcp://ClusterSystem@master:2552")) / "system" / "receptionist")
    val seedNodesAddresses =  Vector(AddressFromURIString("akka.tcp://ClusterSystem@master:2552"))

    Cluster(system).joinSeedNodes(seedNodesAddresses)

    val clusterClient = system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
    val masterProxy = system.actorOf(RemoteMasterProxy.props(clusterClient), "masterProxy")
    system.actorOf(Props(classOf[Producer], masterProxy), "producer")
    system.actorOf(Props[Consumer], "consumer")
  }

}
