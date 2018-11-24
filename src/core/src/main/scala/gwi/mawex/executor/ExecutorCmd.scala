package gwi.mawex.executor

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorSelection, Address, AddressFromURIString, Props}
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

  class SandboxFrontDesk(sandbox: ActorSelection) extends Actor {
    override def preStart(): Unit = sandbox ! e2s.RegisterExecutor
    override def receive: Receive = {
      case s2e.TerminateExecutor => context.system.terminate()
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
