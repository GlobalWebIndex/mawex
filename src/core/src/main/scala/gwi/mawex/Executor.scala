package gwi.mawex

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import akka.remote.{DisassociatedEvent, RemoteScope}
import akka.serialization.Serialization
import gwi.mawex.ForkingSandBox.ForkedJvm
import org.backuity.clist.{CliMain, arg}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.sys.process.Process
import scala.util.Try

/** Executor itself is delivered by users, Mawex provides only SandBox where Executor runs */
object Executor {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"
}

/** Mawex SandBoxes are resilient, let's escalate errors from underlying executors to Worker that is responsible for Executor failures */
sealed trait SandBox extends Actor with ActorLogging {
  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }
}

object SandBox {
  val ActorName = "SandBox"
  def defaultProps(executorProps: Props): Props = Props(classOf[DefaultSandBox], executorProps)
  def forkProps(executorProps: Props, forkedJvm: ForkedJvm): Props = Props(classOf[ForkingSandBox], executorProps, forkedJvm)
}

class DefaultSandBox(executorProps: Props) extends SandBox {
  override def receive: Receive = {
    case Task(_, job) =>
     val executor = context.child(Executor.ActorName) getOrElse context.actorOf(executorProps, Executor.ActorName)
      executor.forward(job)
    }
}

class ForkingSandBox(executorProps: Props, forkedJvm: ForkedJvm) extends SandBox {

  private[this] var process: Option[Process] = Option.empty
  private[this] var frontDesk: Option[ActorRef] = Option.empty

  private[this] def terminateProcess() = {
    frontDesk.foreach(_ ! s2e.TerminateExecutor)
    (1 to 10).foldLeft(process.exists(_.isAlive())) {
      case (acc, counter) if acc =>
        Thread.sleep(500)
        if (counter == 10) process.foreach(p => Try(p.destroy()))
        process.exists(_.isAlive())
      case _ =>
        false
    }
    process = Option.empty
    frontDesk = Option.empty
  }

  override def preStart(): Unit = context.system.eventStream.subscribe(self, classOf[DisassociatedEvent])
  override def postStop(): Unit = terminateProcess()

  override def receive: Receive = idle

  def idle: Receive = {
    case Task(_, job) =>
      context.become(awaitingForkRegistration(sender(), job))
      context.setReceiveTimeout(5.seconds)
      process = Some(ForkingSandBox.fork(self, forkedJvm))
  }

  def awaitingForkRegistration(worker: ActorRef, job: Any): Receive = {
    case e2s.RegisterExecutor =>
      val address = sender().path.address.copy(host = Some("127.0.1.1"))
      log.info(s"Forked Executor registered at $address")
      frontDesk = Some(sender())
      context.setReceiveTimeout(Duration.Undefined)
      val executor = context.actorOf(executorProps.withDeploy(Deploy(scope = RemoteScope(address))), Executor.ActorName)
      executor ! job
      context.become(working(worker, executor))
    case ReceiveTimeout =>
      log.error("Forked Executor Remote actor system has not registered !!!")
  }

  def working(worker: ActorRef, executor: ActorRef): Receive = {
    case taskExecuted: e2w.TaskExecuted =>
      executor ! PoisonPill
      worker ! taskExecuted
      terminateProcess()
      context.become(idle)
  }

  override def unhandled(message: Any): Unit = message match {
    case DisassociatedEvent(local, remote, _) =>
      log.info(s"Forked executor system $remote disassociated from $local ...")
    case x => super.unhandled(x)
  }
}

object ForkingSandBox extends CliMain[Unit]() {
  case class ForkedJvm(classPath: String, opts: String)
  class ExecutorDoorman(sandbox: ActorSelection) extends Actor {
    override def preStart(): Unit = sandbox ! e2s.RegisterExecutor
    override def receive: Receive = {
      case s2e.TerminateExecutor => context.system.terminate()
    }
  }

  var sandboxActorPath = arg[String](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private val systemTerminated = new AtomicBoolean(false)

  override def run: Unit = {
    val sandboxAddress = AddressFromURIString(sandboxActorPath)
    val executorAddress = Address("akka.tcp", Executor.SystemName, sandboxAddress.host.get, 0)
    val executorSystem = RemoteService.buildRemoteSystem(executorAddress)
    val sandbox = executorSystem.actorSelection(sandboxActorPath)
    executorSystem.actorOf(Props(classOf[ExecutorDoorman], sandbox))
    executorSystem.whenTerminated.onComplete { _ =>
      systemTerminated.set(true)
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(if (!systemTerminated.get) Await.result(executorSystem.terminate(), 10.seconds))
  }

  def fork(sandBox: ActorRef, forkedJvm: ForkedJvm): Process =
    Fork.run(
      ForkingSandBox.getClass.getName.replaceAll("\\$", ""),
      forkedJvm.classPath,
      Some(forkedJvm.opts),
      Some(s"--sandbox-actor-path=${Serialization.serializedActorPath(sandBox)}"))

}
