package gwi.mawex.executor

import akka.actor.{Actor, ActorLogging, ActorSelection, Address, AddressFromURIString, Props, ReceiveTimeout}
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.{MawexService, RemoteService, e2s, s2e}
import org.backuity.clist.{Command, opt}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class ExecutorCmd(commands: List[String], jvmOpts: Option[String] = None) {
  def activate(sandBoxSerializedActorPath: String): ExecutorCmd =
    copy(commands = commands :+ s"--sandbox-actor-path=$sandBoxSerializedActorPath")
}

object ExecutorCmd extends Command(name = "executor", description = "launches executor") with MawexService with LazyLogging {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"

  var sandboxActorPath = opt[Option[String]](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private def startAndRegisterExecutorToSandBox(): Unit = {
    require(sandboxActorPath.isDefined, s"Please supply sandbox-actor-path parameter !!!")
    logger.info(s"Starting executor and connecting to ${sandboxActorPath.get}")
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", SystemName, AddressFromURIString(sandboxActorPath.get).host.get, 0))
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath.get)))
    executorSystem.whenTerminated.onComplete { _ =>
      logger.info("Remote Actor System just shut down, exiting jvm process !!!")
      System.exit(0)
    }(ExecutionContext.Implicits.global)
  }

  def run(): Unit = startAndRegisterExecutorToSandBox()

  def apply(jvmOpts: Option[String]): ExecutorCmd =
    ExecutorCmd(List("executor"), jvmOpts)
}

case object ExecutorTerminated
case object SandBoxTerminated

class SandboxFrontDesk(sandbox: ActorSelection) extends Actor with ActorLogging {
  override def preStart(): Unit = {
    log.info(s"SandboxFrontDesk starting...")
    context.setReceiveTimeout(5.seconds)
    sandbox ! e2s.RegisterExecutor
  }

  override def receive: Receive = awaitingExecutorRegistration(1)

  def awaitingExecutorRegistration(attempts: Int): Receive = {
    case s2e.RegisterExecutorAck(executorRef) =>
      log.error("Executor acknowledged registration ...")
      val sandboxRef = sender()
      context.watchWith(sandboxRef, SandBoxTerminated)
      context.watchWith(executorRef, ExecutorTerminated)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(running(attempts))
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

  def running(attempts: Int): Receive = {
    case s2e.TerminateExecutor =>
      log.info(s"Executor terminating ...")
      context.system.terminate()
    case SandBoxTerminated =>
      log.warning(s"SandBox terminated ...")
      context.system.terminate()
    case ExecutorTerminated =>
      log.info(s"Executor terminated ...")
      context.system.terminate()
  }

}
