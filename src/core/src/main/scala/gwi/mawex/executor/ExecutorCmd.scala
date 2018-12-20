package gwi.mawex.executor

import java.io.File

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Address, AddressFromURIString, Props, ReceiveTimeout}
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

  var appConfPath       = opt[String](name="app-conf-path", default = "etc/application.conf", description = "path of externally provided application.conf")
  var sandboxActorPath  = opt[Option[String]](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private def startAndRegisterExecutorToSandBox(): Unit = {
    require(sandboxActorPath.isDefined, s"Please supply sandbox-actor-path parameter !!!")
    val appConfPathOpt = Option(new File(appConfPath)).filter(_.exists)
    logger.debug(s"Starting executor and connecting to ${sandboxActorPath.get}")
    appConfPathOpt.foreach( f => logger.debug(s"App configuration loaded from ${f.getAbsolutePath}") )
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", SystemName, AddressFromURIString(sandboxActorPath.get).host.get, 0), appConfPathOpt)
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath.get)))
    executorSystem.whenTerminated.onComplete { _ =>
      logger.debug("Remote Actor System just shut down, exiting jvm process !!!")
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Option(executorSystem).foreach(_.terminate()))
  }

  def run(): Unit = startAndRegisterExecutorToSandBox()

  def apply(jvmOpts: Option[String]): ExecutorCmd =
    ExecutorCmd(List("executor"), jvmOpts)
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
