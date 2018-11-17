package gwi.mawex.worker

import akka.actor.{ActorRef, ActorSystem, Address, AddressFromURIString, Props, RootActorPath}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import gwi.mawex.RemoteService.HostAddress
import gwi.mawex._
import gwi.mawex.executor.{ForkedJvmConf, K8JobConf, SandBox}
import org.backuity.clist.{Cli, Command, arg, opt}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

object WorkerCmd extends Command(name = "workers", description = "launches workers") with ClusterService {

  var consumerGroups      = opt[List[String]](useEnv = true, default = List("default"), description = "sum,add,divide - 3 workers in 3 consumer groups")
  var pod                 = opt[String](useEnv = true, default = "default", description = "Workers within the same pod are executing sequentially")
  var masterId            = opt[String](useEnv = true, default = "master", name="master-id")
  var taskTimeout         = opt[Int](useEnv = true, default = 60*60, description = "timeout for a task in seconds")
  var sandboxJvmOpts      = opt[Option[String]](useEnv = true, name = "sandbox-jvm-opts", description = "Whether to execute task in a forked process and with what JVM options")
  var k8sNamespace        = opt[Option[String]](useEnv = true, name = "k8s-namespace", description = "What namespace to execute k8s jobs at")
  var k8sDockerImage      = opt[Option[String]](useEnv = true, name = "k8s-docker-image", description = "What docker image to run job with")
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
      val k8JobConfOpt =
        for {
          k8nspace  <- k8sNamespace
          k8image   <- k8sDockerImage
        } yield K8JobConf(s"${consumerGroup}_$pod", k8image, k8nspace, List("executor"), sandboxJvmOpts)

      val sandBoxProps =
        k8JobConfOpt
          .map(SandBox.k8JobProps(executorProps, _))
          .getOrElse(sandboxJvmOpts.fold(SandBox.localJvmProps(executorProps))(opts => SandBox.forkingProps(executorProps, ForkedJvmConf("lib/*", List("executor"), Some(opts)))))

      workerActorRef(
        masterId,
        clusterClient,
        WorkerId(consumerGroup, pod),
        taskTimeout.seconds,
        sandBoxProps,
        system
      )
    }
    system.whenTerminated.onComplete(_ => System.exit(0))(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }

}