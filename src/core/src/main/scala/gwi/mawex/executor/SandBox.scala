package gwi.mawex.executor

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor.{Actor, ActorInitializationException, ActorKilledException, ActorLogging, ActorRef, DeathPactException, Deploy, OneForOneStrategy, Props, ReceiveTimeout, Terminated}
import akka.remote.RemoteScope
import akka.serialization.Serialization
import gwi.mawex._
import gwi.mawex.s2e.RegisterExecutorAck

import scala.concurrent.duration._

sealed trait SandBox extends Actor with ActorLogging {
  def executorProps: Props
  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }
}

/** SandBox for local JVM execution, it just simply forwards Task/Result from Worker to executor */
class LocalJvmSandBox(val executorProps: Props) extends SandBox {
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
class RemoteSandBox(executorSupervisorProps: Props, val executorProps: Props, executorCmd: ExecutorCmd) extends SandBox {

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }

  private[this] def stopExecutorSupervisor(worker: ActorRef, task: Task, taskResult: TaskResult, executorSupervisorRef: ActorRef, frontDeskOpt: Option[ActorRef] = None): Unit = {
    frontDeskOpt.foreach { frontDesk =>
      log.info(s" Stopping frontDesk $frontDesk")
      frontDesk ! s2e.TerminateExecutor
      context.unwatch(frontDesk)
    }
    executorSupervisorRef ! s2es.Stop
    worker ! taskResult
    context.become(idle(context.actorOf(executorSupervisorProps)))
  }

  override def receive: Receive = idle(context.actorOf(executorSupervisorProps))

  def idle(executorSupervisorRef: ActorRef): Receive = {
    case task@Task(id, _) =>
      context.become(awaitingForkRegistration(sender(), task, executorSupervisorRef))
      executorSupervisorRef ! s2es.Start(id, executorCmd.activate(Serialization.serializedActorPath(self)))
  }

  def awaitingForkRegistration(worker: ActorRef, task: Task, executorSupervisorRef: ActorRef): Receive = {
    case es2s.Crashed =>
      stopExecutorSupervisor(worker, task, TaskResult(task.id, Left(s"Spinning Remote Executor system crashed while executing task ${task.id} !!!")), executorSupervisorRef)
    case es2s.TimedOut =>
      stopExecutorSupervisor(worker, task, TaskResult(task.id, Left(s"Spinning Remote Executor system timed out while executing task ${task.id} !!!")), executorSupervisorRef)
    case e2s.RegisterExecutor =>
      context.setReceiveTimeout(Duration.Undefined)
      val frontDeskRef = sender()
      val address = frontDeskRef.path.address
      log.info(s"Forked Executor registered at $address")
      context.watch(frontDeskRef)
      val executorRef = context.actorOf(executorProps.withDeploy(Deploy(scope = RemoteScope(address))), ExecutorCmd.ActorName)
      frontDeskRef ! RegisterExecutorAck(executorRef)
      executorRef ! task
      context.become(working(worker, task, frontDeskRef, executorSupervisorRef))
  }

  def working(worker: ActorRef, task: Task, frontDesk: ActorRef, executorSupervisorRef: ActorRef): Receive = {
    case taskResult: TaskResult =>
      log.info(s"Executor finished task with result : ${taskResult.result}")
      stopExecutorSupervisor(worker, task, taskResult, executorSupervisorRef, Option(frontDesk))
    case Terminated(_) =>
      log.info(s"FrontDesk terminated $frontDesk ...")
      val taskResult = TaskResult(task.id, Left(s"Remote Executor system disconnected while executing task ${task.id} !!!"))
      stopExecutorSupervisor(worker, task, taskResult, executorSupervisorRef, Option(frontDesk))
  }

}

object SandBox {
  val ActorName = "SandBox"
  def localJvmProps(executorProps: Props): Props =
    Props(classOf[LocalJvmSandBox], executorProps)
  def forkingProps(executorProps: Props, forkedJvmConf: ForkedJvmConf, executorCmd: ExecutorCmd): Props =
    Props(classOf[RemoteSandBox], Props(classOf[ForkingExecutorSupervisor], forkedJvmConf), executorProps, executorCmd)
  def k8JobProps(executorProps: Props, k8JobConf: K8JobConf, executorCmd: ExecutorCmd): Props =
    Props(classOf[RemoteSandBox], Props(classOf[K8JobExecutorSupervisor], k8JobConf), executorProps, executorCmd)
}
