package gwi.mawex

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorSelection, Address, AddressFromURIString, Props}
import org.backuity.clist.arg

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class SandboxFrontDesk(sandbox: ActorSelection) extends Actor {
  override def preStart(): Unit = sandbox ! e2s.RegisterExecutor
  override def receive: Receive = {
    case s2e.TerminateExecutor => context.system.terminate()
  }
}

trait SandBoxLauncherSupport {

  var sandboxActorPath = arg[String](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private val systemTerminated = new AtomicBoolean(false)

  def startAndRegisterExecutorToSandBox = {
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", Executor.SystemName, AddressFromURIString(sandboxActorPath).host.get, 0))
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath)))
    executorSystem.whenTerminated.onComplete { _ =>
      systemTerminated.set(true)
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(if (!systemTerminated.get) Await.result(executorSystem.terminate(), 10.seconds))
  }


}
