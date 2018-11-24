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

/** SandBox for local JVM execution, it just simply forwards Task/Result from Worker to executor */
class LocalJvmSandBox(executorProps: Props) extends SandBox {
  override def receive: Receive = {
    case task: Task =>
      context
        .child(ExecutorCmd.ActorName)
        .getOrElse(context.actorOf(executorProps, ExecutorCmd.ActorName))
        .forward(task)
  }
}

/**
  * Execution happens safely in a forked JVM process or k8 job, actor system is started there in order for the input and complex results to be passed/returned through akka remoting
  */
class RemoteSandBox(controller: RemoteController, executorCmd: ExecutorCmd) extends SandBox {
  private[this] var frontDeskRef: Option[ActorRef] = Option.empty
  private[this] def shutDownRemoteActorSystem(): Unit = {
    log.info("Shutting down Remote Executor Actor System !!!")
    frontDeskRef.foreach(_ ! s2e.TerminateExecutor)
    frontDeskRef = Option.empty
    controller.onStop()
  }

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }

  override def preStart(): Unit = {
    log.info(s"Sandbox starting...")
    context.system.eventStream.subscribe(self, classOf[DisassociatedEvent])
  }
  override def postStop(): Unit = {
    log.info(s"Sandbox stopping...")
    shutDownRemoteActorSystem()
  }

  override def unhandled(message: Any): Unit = message match {
    case DisassociatedEvent(local, remote, _) =>
      log.info(s"Forked executor system $remote disassociated from $local ...")
    case x => super.unhandled(x)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case task@Task(id, _) =>
      context.become(awaitingForkRegistration(sender(), task))
      context.setReceiveTimeout(20.seconds)
      controller.start(id, executorCmd.activate(Serialization.serializedActorPath(self)))
  }

  def awaitingForkRegistration(worker: ActorRef, task: Task): Receive = {
    case e2s.RegisterExecutor =>
      val address = sender().path.address
      log.info(s"Forked Executor registered at $address")
      frontDeskRef = Some(sender())
      context.setReceiveTimeout(Duration.Undefined)
      val executorRef = context.actorOf(controller.executorProps.withDeploy(Deploy(scope = RemoteScope(address))), ExecutorCmd.ActorName)
      executorRef ! task
      context.become(working(worker, executorRef))
    case ReceiveTimeout =>
      log.warning("Forked Executor Remote actor system has not registered !!!")
      worker ! TaskResult(task.id, Left(s"Executor did not reply for task ${task.id} ..."))
      shutDownRemoteActorSystem()
  }

  def working(worker: ActorRef, executor: ActorRef): Receive = {
    case taskResult: TaskResult =>
      executor ! PoisonPill
      log.warning(s"Executor finished task with result : ${taskResult.result}")
      worker ! taskResult
      shutDownRemoteActorSystem()
      context.become(idle)
  }

}

object SandBox {
  val ActorName = "SandBox"
  def localJvmProps(executorProps: Props): Props =
    Props(classOf[LocalJvmSandBox], executorProps)
  def forkingProps(executorProps: Props, forkedJvm: ForkedJvmConf, executorCmd: ExecutorCmd): Props =
    Props(classOf[RemoteSandBox], ForkingController(executorProps, forkedJvm), executorCmd)
  def k8JobProps(executorProps: Props, k8JobConf: K8JobConf, executorCmd: ExecutorCmd): Props =
    Props(classOf[RemoteSandBox], K8JobController(executorProps, k8JobConf), executorCmd)
}
