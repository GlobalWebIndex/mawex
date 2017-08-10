package gwi.mawex

import akka.actor._
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.backuity.clist._
import org.backuity.clist.util.Read

import scala.collection.JavaConverters._
import scala.concurrent.Await
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

  def buildClusterSystem(redisAddress: Address, redisPassword: String, hostAddress: Address, seedNodes: List[Address], memberSize: Int) =
    ActorSystem(
      "ClusterSystem",
      ConfigFactory.parseString(
        s"""
          redis {
            host = ${redisAddress.host}
            port = ${redisAddress.port}
            password = $redisPassword
            sentinel = false
          }
          akka {
            actor {
              provider = cluster
              kryo.idstrategy = automatic
            }
            cluster {
              downing-provider-class = "tanukki.akka.cluster.autodown.OldestAutoDowning"
              roles = [backend]
              min-nr-of-members = $memberSize
            }
            extensions = ["akka.cluster.client.ClusterClientReceptionist", "akka.cluster.pubsub.DistributedPubSub", "com.romix.akka.serialization.kryo.KryoSerializationExtension$$"]
            akka-persistence-redis.journal.class = "com.hootsuite.akka.persistence.redis.journal.RedisJournal"
            persistence.journal.plugin = "akka-persistence-redis.journal"
            remote.netty.tcp {
               hostname = ${hostAddress.host}
               port = ${hostAddress.port}
               bind-hostname = 0.0.0.0
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
      ).resolve()
        .withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(seedNodes.map { case Address(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }.asJava))
        .withFallback(ConfigFactory.load("serialization"))
        .withFallback(ConfigFactory.load())
    )

  def backendSingletonActorRef(taskTimeout: FiniteDuration, system: ActorSystem, name: String)(arf: ActorRefFactory = system): ActorRef = {
    arf.actorOf(
      ClusterSingletonManager.props(
        Master.props(name, taskTimeout),
        PoisonPill,
        ClusterSingletonManagerSettings(system).withRole("backend")
      ),
      name
    )
  }

  def buildWorkerSystem(hostAddress: Address) =
    ActorSystem(
      "WorkerSystem",
      ConfigFactory.parseString(
        s"""
        akka {
          actor {
            provider = remote
            kryo.idstrategy = automatic
            extensions = ["com.romix.akka.serialization.kryo.KryoSerializationExtension$$"]
          }
          remote.netty.tcp {
             hostname = ${hostAddress.host}
             port = ${hostAddress.port}
             bind-hostname = 0.0.0.0
          }
        }
        """.stripMargin
      ).withFallback(ConfigFactory.load("serialization"))
        .withFallback(ConfigFactory.load())
    )

  def workerActorRef(masterId: String, clusterClient: ActorRef, workerId: WorkerId, taskTimeout: FiniteDuration, executorClazz: Class[_], executorArgs: Seq[String], system: ActorSystem): ActorRef =
    system.actorOf(Worker.props(masterId, clusterClient, workerId, Props(executorClazz, executorArgs), taskTimeout), s"worker-${workerId.id}")

  def workerClusterClient(seedNodes: List[Address], system: ActorSystem): ActorRef = {
    val initialContacts =
      seedNodes
        .map { case Address(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }
        .map { case AddressFromURIString(addr) => RootActorPath(addr) / "system" / "receptionist" }
        .toSet
    system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
  }
}

sealed trait Service extends Cmd { this: Command =>
  import Service._
  var seedNodes = opt[List[Address]](default = List(Address("master", 2552)), description = "12.34.56.78:2551,12.34.56.79:2552")
  var hostAddress = arg[Address](name="host-address", description = "host:port of this node")
}

object SandBoxCmd extends Command(name = "sandbox", description = "executes arbitrary class") with Cmd {

  var timeout = opt[Int](default = 1800, description = "How many seconds the app can run before it times out")
  var mainClass = arg[String]()
  var mainClassArgs = arg[Option[String]](required = false)

  def run() = {
    val status = SandBox.fork("bin/*", mainClass, timeout.seconds, mainClassArgs.map(_.split(" ").toList).getOrElse(List.empty))
    println(s"Sandbox finished with status $status, exiting ...")
    System.exit(status)
  }
}

object MasterCmd extends Command(name = "master", description = "launches master") with Service {
  import Service._

  var taskTimeout  = opt[Int](default = 60*60, description = "timeout for a task in seconds")
  var masterId     = opt[String](default = "master", name="master-id")
  var redisAddress = arg[Address](name="redis-address", description = "host:port of redis")

  def run(): Unit = {
    val redisPassword = sys.env.getOrElse("REDIS_PASSWORD", throw new IllegalArgumentException("REDIS_PASSWORD env var must defined !!!"))
    val system = buildClusterSystem(redisAddress, redisPassword, hostAddress, seedNodes, seedNodes.size)
    backendSingletonActorRef(taskTimeout.seconds, system, masterId)()
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }

}

object WorkerCmd extends Command(name = "workers", description = "launches workers") with Service {
  import Service._

  var consumerGroups  = opt[List[String]](default = List("default"), description = "sum,add,divide - 3 workers in 3 consumer groups")
  var pod             = opt[String](default = "default", description = "Workers within the same pod are executing sequentially")
  var masterId        = opt[String](default = "master", name="master-id")
  var taskTimeout     = opt[Int](default = 60*60, description = "timeout for a task in seconds")
  var executorClass   = arg[String](name="executor-class", description = "Full class name of executor Actor, otherwise identity ping/pong executor will be used")
  var executorArgs    = arg[Option[String]](required = false, name="executor-args", description = "Arguments to be passed to forked executor jvm process")

  def run(): Unit = {
    val system = buildWorkerSystem(hostAddress)
    val clusterClient = workerClusterClient(seedNodes, system)
    consumerGroups.foreach { consumerGroup =>
      workerActorRef(
        masterId,
        clusterClient,
        WorkerId(consumerGroup, pod),
        taskTimeout.seconds,
        Class.forName(executorClass),
        executorArgs.map(_.split(" ").filter(_.nonEmpty).toSeq).getOrElse(Seq.empty),
        system
      )
    }
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }

}

class IdentityExecutor(executorArgs: Seq[String]) extends Actor {
  def receive = {
    case task => sender() ! e2w.TaskExecuted(Success(task))
  }
}
