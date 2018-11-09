package gwi.mawex

import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.{Actor, ActorRef, ActorSelection, Address, AddressFromURIString, Deploy, PoisonPill, Props, ReceiveTimeout}
import akka.remote.{DisassociatedEvent, RemoteScope}
import akka.serialization.Serialization
import org.backuity.clist.{CliMain, arg}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.sys.process.Process
import scala.util.Try

/**
  * SandBox for execution in a forked JVM, actor system is started in forked jvm so that complex results can be
  * returned through akka remoting. Otherwise results would have to be collected from stdout.
  */
case class ForkedJvm(classPath: String, opts: String)
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
      val address = sender().path.address
      log.info(s"Forked Executor registered at $address")
      frontDesk = Some(sender())
      context.setReceiveTimeout(Duration.Undefined)
      val executor = context.actorOf(executorProps.withDeploy(Deploy(scope = RemoteScope(address))), Executor.ActorName)
      executor ! job
      context.become(working(worker, executor))
    case ReceiveTimeout =>
      log.warning("Forked Executor Remote actor system has not registered !!!")
  }

  def working(worker: ActorRef, executor: ActorRef): Receive = {
    case taskExecuted: e2w.TaskExecuted =>
      executor ! PoisonPill
      log.warning(s"Executor finished task with result : ${taskExecuted.result}")
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
