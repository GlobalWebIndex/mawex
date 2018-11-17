package gwi.mawex.executor

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor.{Actor, ActorInitializationException, ActorKilledException, ActorLogging, ActorRef, DeathPactException, Deploy, OneForOneStrategy, PoisonPill, Props, ReceiveTimeout}
import akka.remote.{DisassociatedEvent, RemoteScope}
import akka.serialization.Serialization
import gwi.mawex._

import scala.concurrent.duration._

sealed trait SandBox extends Actor with ActorLogging {
  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }
}

/** SandBox for local JVM execution */
class LocalJvmSandBox(executorProps: Props) extends SandBox {
  override def receive: Receive = {
    case Task(_, job) =>
      val executor = context.child(ExecutorCmd.ActorName) getOrElse context.actorOf(executorProps, ExecutorCmd.ActorName)
      executor.forward(job)
  }
}

/**
  * Execution happens in a forked JVM or k8 job, actor system is started there in order for the input and complex results to be passed/returned through akka remoting
  * Mawex SandBoxes are like a runtime environment for executors, resilient, it escalates errors from underlying executors to Worker that is responsible for Executor failures
  */
class RemoteSandBox(executor: Executor) extends SandBox {
  private[this] var frontDeskRef: Option[ActorRef] = Option.empty
  private[this] def onTerminate(): Unit = {
    frontDeskRef.foreach(_ ! s2e.TerminateExecutor)
    frontDeskRef = Option.empty
    executor.onStop()
  }

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }

  override def preStart(): Unit = context.system.eventStream.subscribe(self, classOf[DisassociatedEvent])
  override def postStop(): Unit = onTerminate()

  override def unhandled(message: Any): Unit = message match {
    case DisassociatedEvent(local, remote, _) =>
      log.info(s"Forked executor system $remote disassociated from $local ...")
    case x => super.unhandled(x)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case Task(_, job) =>
      context.become(awaitingForkRegistration(sender(), job))
      context.setReceiveTimeout(5.seconds)
      executor.run(Serialization.serializedActorPath(self))
  }

  def awaitingForkRegistration(worker: ActorRef, job: Any): Receive = {
    case e2s.RegisterExecutor =>
      val address = sender().path.address
      log.info(s"Forked Executor registered at $address")
      frontDeskRef = Some(sender())
      context.setReceiveTimeout(Duration.Undefined)
      val executorRef = context.actorOf(executor.props.withDeploy(Deploy(scope = RemoteScope(address))), ExecutorCmd.ActorName)
      executorRef ! job
      context.become(working(worker, executorRef))
    case ReceiveTimeout =>
      log.warning("Forked Executor Remote actor system has not registered !!!")
  }

  def working(worker: ActorRef, executor: ActorRef): Receive = {
    case taskExecuted: e2w.TaskExecuted =>
      executor ! PoisonPill
      log.warning(s"Executor finished task with result : ${taskExecuted.result}")
      worker ! taskExecuted
      onTerminate()
      context.become(idle)
  }

}

object SandBox {
  val ActorName = "SandBox"
  def localJvmProps(executorProps: Props): Props =
    Props(classOf[LocalJvmSandBox], executorProps)
  def forkingProps(executorProps: Props, forkedJvm: ForkedJvmConf): Props =
    Props(classOf[RemoteSandBox], ForkingExecutor(executorProps, forkedJvm))
  def k8JobProps(executorProps: Props, k8JobConf: K8JobConf): Props =
    Props(classOf[RemoteSandBox], K8JobExecutor(executorProps, k8JobConf))
}
