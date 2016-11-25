package gwi.mawex

import akka.actor._
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
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

  def startBackend(hostAddress: Address, seedNodes: List[Address], taskTimeout: FiniteDuration): ActorSystem = {
    val role = "backend"
    val seedNodeAddresses = seedNodes.map { case Address(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }

    val conf = ConfigFactory.parseString(
      s"""
      redis {
        host = $${REDIS_HOST}
        port = $${REDIS_PORT}
        password = $${REDIS_PASSWORD}
        sentinel = false
      }
      akka {
        actor.provider = cluster
        cluster.roles=[$role]
        extensions = ["akka.cluster.client.ClusterClientReceptionist", "akka.cluster.pubsub.DistributedPubSub", "com.romix.akka.serialization.kryo.KryoSerializationExtension$$"]
        akka-persistence-redis.journal.class = "com.hootsuite.akka.persistence.redis.journal.RedisJournal"
        persistence.journal.plugin = "akka-persistence-redis.journal"
        remote.netty.tcp {
           hostname = ${hostAddress.host}
           port = ${hostAddress.port}
           bind-hostname = 0.0.0.0
        }
      }
      """.stripMargin
    ).resolve().withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seedNodeAddresses.asJava))
      .withFallback(ConfigFactory.load("serialization"))

    val system = ActorSystem("ClusterSystem", conf)

    system.actorOf(
      ClusterSingletonManager.props(
        Master(taskTimeout),
        PoisonPill,
        ClusterSingletonManagerSettings(system).withRole(role)
      ),
      "master"
    )
    system
  }

  def startWorker(hostAddress: Address, contactPoints: List[Address], consumerGroup: String, executorClazz: Class[_], executorArgs: Seq[String]): ActorSystem = {
    val conf = ConfigFactory.parseString(
      s"""
      akka {
        actor.provider = remote
        remote.netty.tcp {
           hostname = ${hostAddress.host}
           port = ${hostAddress.port}
           bind-hostname = 0.0.0.0
        }
      }
      """.stripMargin
    ).withFallback(ConfigFactory.load("serialization"))

    val system = ActorSystem("WorkerSystem", conf)
    val initialContacts =
      contactPoints
        .map { case Address(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }
        .map { case AddressFromURIString(addr) => RootActorPath(addr) / "system" / "receptionist" }
        .toSet

    val clusterClient = system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
    system.actorOf(Worker.props(clusterClient, consumerGroup, Props(executorClazz, executorArgs)), "worker")
    system
  }

}

sealed trait Service extends Cmd { this: Command =>
  import Service._
  var seedNodes = opt[List[Address]](default = List(Address("master", 2552)), description = "12.34.56.78:2551,12.34.56.79:2552")
  var hostAddress = arg[Address](required = true, name="host-address", description = "host:port of this node")
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
  
  def run() = startBackend(hostAddress, seedNodes, taskTimeout.seconds)

}

object WorkerCmd extends Command(name = "workers", description = "launches workers") with Service {
  import Service._

  var consumerGroups = opt[List[String]](default = List("default"), description = "sum,sum,add,add,add,divide - 6 workers in 3 consumer groups")
  var executorClass = arg[String](required = true, name="executor-class", description = "Full class name of executor Actor, otherwise identity ping/pong executor will be used")
  var executorArgs = arg[Option[String]](required = false, name="executor-args", description = "Arguments to be passed to forked executor jvm process")

  def run() =
    consumerGroups.foreach { consumerGroup =>
      startWorker(hostAddress, seedNodes, consumerGroup, Class.forName(executorClass), executorArgs.map(_.split(" ").filter(_.nonEmpty).toSeq).getOrElse(Seq.empty))
    }

}

class IdentityExecutor(executorArgs: Seq[String]) extends Actor {
  def receive = {
    case task => sender() ! e2w.TaskExecuted(Success(task))
  }
}
