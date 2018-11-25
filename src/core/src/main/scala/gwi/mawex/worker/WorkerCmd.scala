package gwi.mawex.worker

import akka.actor.{ActorRef, ActorSystem, Address, AddressFromURIString, Props, RootActorPath}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.RemoteService.HostAddress
import gwi.mawex._
import gwi.mawex.executor._
import org.backuity.clist.{Cli, Command, arg, opt}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object WorkerCmd extends Command(name = "workers", description = "launches workers") with ClusterService with LazyLogging {

  var consumerGroups                = opt[List[String]](useEnv = true, default = List("default"), description = "sum,add,divide - 3 workers in 3 consumer groups")
  var pod                           = opt[String](useEnv = true, default = "default", description = "Workers within the same pod are executing sequentially")
  var masterId                      = opt[String](useEnv = true, default = "master", name="master-id")
  var taskTimeout                   = opt[Int](useEnv = true, default = 60*60, description = "timeout for a task in seconds")
  var executorType                  = opt[String](useEnv = true, default = "forked", name = "executor-type", description = "local / forked / k8s")
  var executorResourcesLimitsCpu    = opt[String](useEnv = true, default = "150m", name = "executor-resources-limits-cpu", description = "k8s resource limits")
  var executorResourcesLimitsMem    = opt[String](useEnv = true, default = "100Mi", name = "executor-resources-limits-memory", description = "k8s resource limits")
  var executorResourcesRequestsCpu  = opt[String](useEnv = true, default = "50m", name = "executor-resources-requests-cpu", description = "k8s resource limits")
  var executorResourcesRequestsMem  = opt[String](useEnv = true, default = "100Mi", name = "executor-resources-requests-memory", description = "k8s resource limits")
  var sandboxJvmOpts                = opt[Option[String]](useEnv = true, name = "sandbox-jvm-opts", description = "Whether to execute task in a forked process and with what JVM options")
  var forkedJvmClassPath            = opt[String](useEnv = true, default = "lib/*", name = "forked-jvm-class-path", description = "Class path for the fork jvm executor")
  var k8sNamespace                  = opt[String](useEnv = true, default = "default", name = "k8s-namespace", description = "What namespace to execute k8s jobs at")
  var k8sDockerImage                = opt[Option[String]](useEnv = true, name = "k8s-docker-image", description = "What docker image to run job with")
  var executorClass                 = arg[String](name="executor-class", description = "Full class name of executor Actor")
  var commandBuilderClass           = arg[Option[String]](required = false, name="command-builder-class", description = "Full class name of MawexCommandBuilder")
  var commandBuilderArgs            = arg[Option[String]](required = false, name="command-args", description = "Arguments to be passed to MawexCommandBuilder")


  private def getExecutorResources =
    K8Resources(
      executorResourcesLimitsCpu,
      executorResourcesLimitsMem,
      executorResourcesRequestsCpu,
      executorResourcesRequestsMem
    )

  private def workerActorRef(masterId: String, clusterClient: ActorRef, workerId: WorkerId, taskTimeout: FiniteDuration, sandBoxProps: Props, system: ActorSystem): ActorRef =
    system.actorOf(Worker.props(masterId, clusterClient, workerId, sandBoxProps, taskTimeout), s"worker-${workerId.id}")

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

  private def getSandBoxProps(executorProps: Props, consumerGroup: String) = executorType match {
    case "local" =>
      logger.info(s"Local mode enabled on worker")
      SandBox.localJvmProps(executorProps)
    case "forked" =>
      logger.info(s"Forked mode enabled on worker")
      SandBox.forkingProps(executorProps, ForkedJvmConf(forkedJvmClassPath), ExecutorCmd(sandboxJvmOpts))
    case "k8s" =>
      logger.info(s"K8s mode enabled on worker")
      val k8Image = k8sDockerImage.getOrElse(throw new IllegalArgumentException("k8sDockerImage not specified !!!"))
      SandBox.k8JobProps(executorProps, K8JobConf(k8Image, k8sNamespace, getExecutorResources), ExecutorCmd(sandboxJvmOpts))
    case x =>
      throw new IllegalArgumentException(s"Executor type $x is not valid, please choose between local / forked / k8s")
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
        getSandBoxProps(executorProps, consumerGroup),
        system
      )
    }
    system.whenTerminated.onComplete(_ => System.exit(0))(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }

}
