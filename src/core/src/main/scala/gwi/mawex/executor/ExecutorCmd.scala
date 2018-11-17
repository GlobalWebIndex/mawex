package gwi.mawex.executor

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorSelection, Address, AddressFromURIString, Props}
import gwi.mawex.{MawexService, RemoteService, e2s, s2e}
import org.backuity.clist.{Command, arg}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.sys.ShutdownHookThread

object ExecutorCmd extends Command(name = "executor", description = "launches executor") with MawexService {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"

  class SandboxFrontDesk(sandbox: ActorSelection) extends Actor {
    override def preStart(): Unit = sandbox ! e2s.RegisterExecutor
    override def receive: Receive = {
      case s2e.TerminateExecutor => context.system.terminate()
    }
  }

  var sandboxActorPath = arg[String](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private val systemTerminated = new AtomicBoolean(false)

  private def startAndRegisterExecutorToSandBox: ShutdownHookThread = {
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", SystemName, AddressFromURIString(sandboxActorPath).host.get, 0))
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath)))
    executorSystem.whenTerminated.onComplete { _ =>
      systemTerminated.set(true)
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(if (!systemTerminated.get) Await.result(executorSystem.terminate(), 10.seconds))
  }

  def run(): Unit = startAndRegisterExecutorToSandBox
}
