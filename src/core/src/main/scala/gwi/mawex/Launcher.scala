package gwi.mawex

import java.net.InetAddress

import akka.actor._
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import gwi.mawex.OpenProtocol.e2w
import org.backuity.clist._
import org.backuity.clist.util.Read

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Success

object Launcher {
  def main(args: Array[String]): Unit =
    Cli.parse(args).withProgramName("mawex").withCommands(MasterCmd, WorkerCmd, SandBoxCmd).foreach(_.run())
}

sealed trait Cmd { this: Command =>
  def run(): Unit
}

object Service {
  case class Address(host: String, port: Int)

  implicit val listRead = Read.reads("list") { str =>
    str.split(",").filter(_.nonEmpty).toList
  }

  implicit val addressRead = Read.reads("address") { str =>
    val Array(host,port) = str.split(":")
    Address(host,port.toInt)
  }

  implicit val addressesRead = Read.reads("addresses") { str =>
    str.split(",").map (_.split(":")).map( arr => Address(arr(0), arr(1).toInt) ).toList
  }

  def startBackend(hostName: String, host: String, port: Int, taskTimeout: FiniteDuration): Unit = {
    val role = "backend"
    val seedNode = s"akka.tcp://ClusterSystem@$host:$port"
    val conf =
      ConfigFactory
        .parseString(s"akka.cluster.roles=[$role]")
        .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(hostName))
        .withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(List(seedNode).asJava))
        .withFallback(ConfigFactory.load("master"))
    val system = ActorSystem("ClusterSystem", conf)

    system.actorOf(
      ClusterSingletonManager.props(
        Master(taskTimeout),
        PoisonPill,
        ClusterSingletonManagerSettings(system).withRole(role)
      ),
      "master"
    )
  }

  def startWorker(hostName: String, contactPoints: List[Address], consumerGroup: String, executorClazz: Class[_], executorArgs: Seq[String]): Unit = {
    val conf =
      ConfigFactory
        .parseString(s"akka.remote.netty.tcp.hostname=$hostName")
        .withFallback(ConfigFactory.load("workers"))
    val system = ActorSystem("WorkerSystem", conf)
    val initialContacts =
      contactPoints
        .map { case Address(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }
        .map { case AddressFromURIString(addr) => RootActorPath(addr) / "system" / "receptionist" }
        .toSet

    val clusterClient = system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
    system.actorOf(Worker.props(clusterClient, consumerGroup, Props(executorClazz, executorArgs)), "worker")
  }

}

sealed trait Service extends Cmd { this: Command =>
  import Service._
  var seedNodes = opt[List[Address]](default = List(Address("master", 2552)), description = "12.34.56.78:2551,12.34.56.79:2552")
  var hostName = opt[String](default = InetAddress.getLocalHost.getHostName, description = "Hostname of this node")
}

object SandBoxCmd extends Command(name = "sandbox", description = "executes arbitrary class") with Cmd {

  var timeout = opt[Int](default = 1800, description = "How many seconds the app can run before it times out")
  var mainClass = arg[String](required = true)
  var mainClassArgs = arg[Option[String]](required = false)

  def run() = {
    val status = SandBox.fork("bin/*", mainClass, timeout.seconds, mainClassArgs.map(_.split(" ").toList).getOrElse(List.empty))
    println(s"Sandbox finished with status $status, exiting ...")
    System.exit(status)
  }
}

object MasterCmd extends Command(name = "master", description = "launches master") with Service {
  import Service._

  var taskTimeout = opt[Int](default = 60*60, description = "timeout for a task in seconds")
  
  require(seedNodes.size == 1, "Multiple masters not supported yet!")

  def run() = {
    val Address(host, port) = seedNodes.head
    startBackend(hostName, host, port, taskTimeout.seconds)
  }

}

object WorkerCmd extends Command(name = "workers", description = "launches workers") with Service {
  import Service._

  var consumerGroups = opt[List[String]](default = List("default"), description = "sum,sum,add,add,add,divide - 6 workers in 3 consumer groups")
  var executorClass = arg[String](required = true, name="executor-class", description = "Full class name of executor Actor, otherwise identity ping/pong executor will be used")
  var executorArgs = arg[Option[String]](required = false, name="executor-args", description = "Arguments to be passed to forked executor jvm process")

  def run() =
    consumerGroups.foreach { consumerGroup =>
      startWorker(hostName, seedNodes, consumerGroup, Class.forName(executorClass), executorArgs.map(_.split(" ").filter(_.nonEmpty).toSeq).getOrElse(Seq.empty))
    }

}

class IdentityExecutor(executorArgs: Seq[String]) extends Actor {
  def receive = {
    case task => sender() ! e2w.TaskExecuted(Success(task))
  }
}
