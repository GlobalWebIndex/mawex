package gwi.mawex.executor

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Address, AddressFromURIString, PoisonPill, Props, ReceiveTimeout}
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.{MawexService, RemoteService, e2s, s2e}
import org.backuity.clist.{Command, opt}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.sys.ShutdownHookThread

case class ExecutorCmd(commands: List[String], jvmOpts: Option[String] = None) {
  def activate(sandBoxSerializedActorPath: String): ExecutorCmd =
    copy(commands = commands :+ s"--sandbox-actor-path=$sandBoxSerializedActorPath")
}

object ExecutorCmd extends Command(name = "executor", description = "launches executor") with MawexService with LazyLogging {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"

  case object ExecutorTerminated
  case object SandBoxTerminated

  class SandboxFrontDesk(sandbox: ActorSelection) extends Actor with ActorLogging {
    override def preStart(): Unit = {
      log.info(s"SandboxFrontDesk starting...")
      sandbox ! e2s.RegisterExecutor
      context.setReceiveTimeout(5.seconds)
    }

    override def receive: Receive = awaitingExecutorRegistration(1)

    def awaitingExecutorRegistration(attempts: Int): Receive = {
      case s2e.RegisterExecutorAck(executorRef) =>
        val sandboxRef = sender()
        context.watchWith(sandboxRef, SandBoxTerminated)
        context.watchWith(executorRef, ExecutorTerminated)
        context.setReceiveTimeout(Duration.Undefined)
        context.become(awaitingTermination(executorRef, sandboxRef))
      case ReceiveTimeout =>
        if (attempts <= 3 ) {
          sandbox ! e2s.RegisterExecutor
          context.become(awaitingExecutorRegistration(attempts+1))
          context.setReceiveTimeout(5.seconds)
        } else {
          logger.error(s"Sandbox doesn't respond to executor registration !!!")
          context.system.terminate()
        }
    }

    def awaitingTermination(executorRef: ActorRef, sandboxRef: ActorRef): Receive = {
      case s2e.TerminateExecutor =>
        executorRef ! PoisonPill
      case SandBoxTerminated =>
        log.warning(s"SandBox terminated ...")
        executorRef ! PoisonPill
      case ExecutorTerminated =>
        log.info(s"Executor terminated ...")
        context.system.terminate()
    }

  }

  var sandboxActorPath = opt[Option[String]](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private val systemTerminated = new AtomicBoolean(false)

  private def startAndRegisterExecutorToSandBox: ShutdownHookThread = {
    require(sandboxActorPath.isDefined, s"Please supply sandbox-actor-path parameter !!!")
    logger.info(s"Starting executor and connecting to ${sandboxActorPath.get}")
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", SystemName, AddressFromURIString(sandboxActorPath.get).host.get, 0))
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath.get)))
    executorSystem.whenTerminated.onComplete { _ =>
      logger.info("Remote Actor System just shut down, exiting jvm process !!!")
      systemTerminated.set(true)
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(if (!systemTerminated.get) Await.result(executorSystem.terminate(), 10.seconds))
  }

  def run(): Unit = startAndRegisterExecutorToSandBox

  def apply(jvmOpts: Option[String]): ExecutorCmd =
    ExecutorCmd(List("executor"), jvmOpts)
}
