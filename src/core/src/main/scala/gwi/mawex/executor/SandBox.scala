package gwi.mawex.executor

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor.{Actor, ActorInitializationException, ActorKilledException, ActorLogging, ActorRef, DeathPactException, Deploy, OneForOneStrategy, Props, ReceiveTimeout, Terminated}
import akka.remote.RemoteScope
import akka.serialization.Serialization
import gwi.mawex._
import gwi.mawex.s2e.RegisterExecutorAck

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

  private[this] def shutDownRemoteActorSystem(frontDesk: ActorRef): Unit = {
    log.info("Shutting down Remote Executor Actor System !!!")
    context.unwatch(frontDesk)
    frontDesk ! s2e.TerminateExecutor
    controller.onStop()
  }

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(frontDesk) =>
      log.info(s"FrontDesk terminated $frontDesk ...")
      shutDownRemoteActorSystem(frontDesk)
    case x =>
      super.unhandled(x)
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case task@Task(id, _) =>
      context.become(awaitingForkRegistration(sender(), task))
      context.setReceiveTimeout(15.seconds)
      controller.start(id, executorCmd.activate(Serialization.serializedActorPath(self)))
  }

  def awaitingForkRegistration(worker: ActorRef, task: Task): Receive = {
    case e2s.RegisterExecutor =>
      context.setReceiveTimeout(Duration.Undefined)
      val frontDeskRef = sender()
      frontDeskRef ! RegisterExecutorAck
      val address = frontDeskRef.path.address
      log.info(s"Forked Executor registered at $address")
      context.watch(frontDeskRef)
      val executorRef = context.actorOf(controller.executorProps.withDeploy(Deploy(scope = RemoteScope(address))), ExecutorCmd.ActorName)
      executorRef ! task
      context.become(working(worker, frontDeskRef))
    case ReceiveTimeout =>
      if (controller.isRunning) {
        log.info("Forked Executor Remote actor system has not registered, waiting ...")
        context.setReceiveTimeout(controller.executorConf.checkInterval)
      } else {
        log.warning("Forked Executor Remote actor system has not registered !!!")
        context.become(idle)
        worker ! TaskResult(task.id, Left(s"Executor did not reply for task ${task.id} ..."))
        controller.onStop()
      }
  }

  def working(worker: ActorRef, frontDesk: ActorRef): Receive = {
    case taskResult: TaskResult =>
      log.info(s"Executor finished task with result : ${taskResult.result}")
      worker ! taskResult
      context.become(idle)
      shutDownRemoteActorSystem(frontDesk)
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
