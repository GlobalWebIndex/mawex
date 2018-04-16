package gwi.mawex

import akka.actor._
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import gwi.mawex.ForkingSandBox.ForkedJvm
import gwi.mawex.RemoteService._
import org.backuity.clist._
import org.backuity.clist.util.Read

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object Launcher {
  def main(args: Array[String]): Unit =
    Cli.parse(args).withProgramName("mawex").withCommands(MasterCmd, WorkerCmd, SandBoxCmd).foreach(_.run())
}

sealed trait MawexService { this: Command =>
  def run(): Unit
}

sealed trait RemoteService extends MawexService { this: Command =>
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

trait ClusterService extends RemoteService { this: Command =>

  implicit val listRead = Read.reads("list") { str =>
    str.split(",").filter(_.nonEmpty).toList
  }

  implicit val addressesRead = Read.reads("addresses") { str =>
    str.split(",").map (_.split(":")).map( arr => HostAddress(arr(0), arr(1).toInt) ).toList
  }

  var seedNodes = opt[List[HostAddress]](useEnv = true, default = List(HostAddress("master", 2552)), description = "12.34.56.78:2551,12.34.56.79:2552")
}

object SandBoxCmd extends Command(name = "sandbox", description = "executes arbitrary app in forked jvm") with MawexService {

  var timeout       = opt[Int](default = 1800, description = "How many seconds the app can run before it times out")
  var jvmOpts       = opt[Option[String]]()
  var mainClass     = arg[String]()
  var mainClassArgs = arg[Option[String]](required = false)

  def run(): Unit =
    System.exit(Fork.await(Fork.run(mainClass, "bin/*", jvmOpts, mainClassArgs), timeout.seconds))
}

object MasterCmd extends Command(name = "master", description = "launches master") with ClusterService {
  import ClusterService._

  var progressingTaskTimeout  = opt[Int](useEnv = true, default = 60*60, description = "timeout for a task progression in seconds")
  var pendingTaskTimeout      = opt[Int](useEnv = true, default = 3*24, description = "timeout for a pending task in hours")
  var masterId                = opt[String](useEnv = true, default = "master", name="master-id", description = "Unique identifier of this master node")

  def run(): Unit = {
    val system = buildClusterSystem(hostAddress, seedNodes, seedNodes.size)
    clusterSingletonActorRef(Master.Config(masterId, progressingTaskTimeout.seconds, pendingTaskTimeout.hours), system)
    system.whenTerminated.onComplete(_ => System.exit(0))(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }
}

object WorkerCmd extends Command(name = "workers", description = "launches workers") with ClusterService {

  var consumerGroups      = opt[List[String]](useEnv = true, default = List("default"), description = "sum,add,divide - 3 workers in 3 consumer groups")
  var pod                 = opt[String](useEnv = true, default = "default", description = "Workers within the same pod are executing sequentially")
  var masterId            = opt[String](useEnv = true, default = "master", name="master-id")
  var taskTimeout         = opt[Int](useEnv = true, default = 60*60, description = "timeout for a task in seconds")
  var sandboxJvmOpts      = opt[Option[String]](useEnv = true, name = "sandbox-jvm-opts", description = "Whether to execute task in a forked process and with what JVM options")
  var executorClass       = arg[String](name="executor-class", description = "Full class name of executor Actor")
  var commandBuilderClass = arg[Option[String]](required = false, name="command-builder-class", description = "Full class name of MawexCommandBuilder")
  var commandBuilderArgs  = arg[Option[String]](required = false, name="command-args", description = "Arguments to be passed to MawexCommandBuilder")

  private def workerActorRef(masterId: String, clusterClient: ActorRef, workerId: WorkerId, taskTimeout: FiniteDuration, executorProps: Props, system: ActorSystem): ActorRef =
    system.actorOf(Worker.props(masterId, clusterClient, workerId, executorProps, taskTimeout), s"worker-${workerId.id}")

  private def workerClusterClient(seedNodes: List[HostAddress], system: ActorSystem): ActorRef = {
    val initialContacts =
      seedNodes
        .map { case HostAddress(host, port) => s"akka.tcp://ClusterSystem@$host:$port" }
        .map { case AddressFromURIString(addr) => RootActorPath(addr) / "system" / "receptionist" }
        .toSet
    system.actorOf(ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)), "clusterClient")
  }

  private def buildCommand(clazz: Class[_], args: Seq[String]) = {
    Cli.parse(("command" +: args).toArray)
      .withCommands(clazz.newInstance().asInstanceOf[MawexCommandBuilder[MawexCommand]])
      .map(_.build)
      .getOrElse(throw new IllegalArgumentException(s"Invalid arguments : " + args.mkString("\n", "\n", "\n")))
  }

  def run(): Unit = {
    val commandArgSeq = commandBuilderArgs.map(_.split(" ").filter(_.nonEmpty).toSeq).getOrElse(Seq.empty)
    val commandOpt = commandBuilderClass.map( className => buildCommand(Class.forName(className), commandArgSeq) )
    val executorClazz = Class.forName(executorClass)
    val executorProps = commandOpt.fold(Props(executorClazz))(cmd => Props(executorClazz, cmd))
    val system = RemoteService.buildRemoteSystem(Address("akka.tcp", Worker.SystemName, Some(hostAddress.host), Some(hostAddress.port)))
    val clusterClient = workerClusterClient(seedNodes, system)
    consumerGroups.foreach { consumerGroup =>
      workerActorRef(
        masterId,
        clusterClient,
        WorkerId(consumerGroup, pod),
        taskTimeout.seconds,
        sandboxJvmOpts.fold(SandBox.defaultProps(executorProps))(opts => SandBox.forkProps(executorProps, ForkedJvm("bin/*", opts))),
        system
      )
    }
    system.whenTerminated.onComplete(_ => System.exit(0))(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }

}
