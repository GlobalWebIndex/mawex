package gwi.mawex.executor

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Address, AddressFromURIString, Props, ReceiveTimeout}
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex._
import org.backuity.clist.{Command, opt}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

sealed trait ExecutorCmd {
  def commands: List[String]
  def jvmOpts: Option[String]
  def mountPathOpt: Option[String]
  def activate(sandBoxSerializedActorPath: String): ExecutorCmd
}

case class ForkedExecutorCmd(commands: List[String], jvmOpts: Option[String], mountPathOpt: Option[String]) extends ExecutorCmd {
  def activate(sandBoxSerializedActorPath: String): ForkedExecutorCmd =
    copy(commands = commands :+ s"--sandbox-actor-path=$sandBoxSerializedActorPath")
}

case class K8sExecutorCmd(commands: List[String], jvmOpts: Option[String], mountPathOpt: Option[String], configMapName: Option[String]) extends ExecutorCmd {
  def activate(sandBoxSerializedActorPath: String): K8sExecutorCmd =
    copy(commands = commands :+ s"--sandbox-actor-path=$sandBoxSerializedActorPath")
}

object ExecutorCmd extends Command(name = "executor", description = "launches executor") with MawexService with MountingService with LazyLogging {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"

  var sandboxActorPath      = opt[Option[String]](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private def startAndRegisterExecutorToSandBox(): Unit = {
    require(sandboxActorPath.isDefined, s"Please supply sandbox-actor-path parameter !!!")
    logger.debug(s"Starting executor and connecting to ${sandboxActorPath.get}")
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", SystemName, AddressFromURIString(sandboxActorPath.get).host.get, 0), getAppConf)
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath.get)))
    executorSystem.whenTerminated.onComplete {
      case Success(_) =>
        logger.debug("Remote Actor System just shut down, exiting jvm process !!!")
        System.exit(0)
      case Failure(ex) =>
        logger.error("Remote Actor System just shut down with failure", ex)
        System.exit(1)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Option(executorSystem).foreach(_.terminate()))
  }

  def run(): Unit = startAndRegisterExecutorToSandBox()

  def forkedCmd(jvmOpts: Option[String], mountPath: Option[String]): ForkedExecutorCmd =
    ForkedExecutorCmd(List("executor") ++ mountPath.map(mp => s"--mount-path=$mp" ), jvmOpts, mountPath)

  def k8sCmd(jvmOpts: Option[String], mountPath: Option[String], configMapName: Option[String]): K8sExecutorCmd =
    K8sExecutorCmd(List("executor") ++ mountPath.map(mp => s"--mount-path=$mp" ), jvmOpts, mountPath, configMapName)
}

class SandboxFrontDesk(sandbox: ActorSelection) extends Actor with ActorLogging {
  override def preStart(): Unit = {
    log.debug(s"SandboxFrontDesk starting...")
    context.setReceiveTimeout(5.seconds)
    sandbox ! e2s.RegisterExecutor
  }

  override def receive: Receive = awaitingExecutorRegistration(1)

  def awaitingExecutorRegistration(attempts: Int): Receive = {
    case s2e.RegisterExecutorAck(executorRef) =>
      log.debug("Executor acknowledged registration ...")
      context.setReceiveTimeout(Duration.Undefined)
      context.become(running(executorRef))
    case ReceiveTimeout =>
      if (attempts <= 3) {
        sandbox ! e2s.RegisterExecutor
        context.become(awaitingExecutorRegistration(attempts + 1))
        context.setReceiveTimeout(5.seconds)
      } else {
        log.error(s"Sandbox doesn't respond to executor registration !!!")
        context.system.terminate()
      }
  }

  def running(executorRef: ActorRef): Receive = {
    case s2e.TerminateExecutor =>
      log.debug(s"Executor terminating ...")
      context.stop(executorRef)
      context.system.terminate()
  }

}
