package gwi.mawex

import java.util.UUID

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import akka.cluster.client.ClusterClient.SendToAll
import gwi.mawex.InternalProtocol._
import gwi.mawex.OpenProtocol._

import scala.concurrent.duration._
import scala.util.{Failure, Try}

class Worker(clusterClient: ActorRef, consumerGroup: String, workExecutorProps: Props, registerInterval: FiniteDuration) extends Actor with ActorLogging {

  val workerId = WorkerId(UUID.randomUUID().toString, consumerGroup)

  import context.dispatcher
  val registerTask = context.system.scheduler.schedule(500.millis, registerInterval, clusterClient, SendToAll("/user/master/singleton", w2m.Register(workerId)))

  val taskExecutor = context.watch(context.actorOf(workExecutorProps, "exec"))

  var currentTaskId: Option[TaskId] = None
  def taskId: TaskId = currentTaskId match {
    case Some(taskId) => taskId
    case None         => throw new IllegalStateException("Not working")
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case ex: Exception =>
      log.error(ex, "Executor crashed !!!")
      currentTaskId foreach { taskId => sendToMaster(w2m.TaskFinished(workerId, taskId, Failure(ex))) }
      context.become(idle)
      Restart
  }

  override def postStop(): Unit = registerTask.cancel()

  def receive = idle

  def idle: Receive = {
    case m2w.TaskReady =>
      sendToMaster(w2m.TaskRequest(workerId))

    case Task(id, job) =>
      log.info("Got task: {}", job)
      currentTaskId = Some(id)
      taskExecutor ! job
      context.become(working)
  }

  def working: Receive = {
    case e2w.TaskExecuted(result) =>
      log.info("Task is complete. Result {}.", result)
      sendToMaster(w2m.TaskFinished(workerId, taskId, result))
      context.setReceiveTimeout(5.seconds)
      context.become(waitForWorkIsDoneAck(result))

    case _: Task =>
      log.warning("Yikes. Master told me to do work, while I'm working.")
  }

  def waitForWorkIsDoneAck(result: Try[Any]): Receive = {
    case m2w.TaskChecked(id) if id == taskId =>
      sendToMaster(w2m.TaskRequest(workerId))
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
    case ReceiveTimeout =>
      log.info("No ack from master, retrying")
      sendToMaster(w2m.TaskFinished(workerId, taskId, result))
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(`taskExecutor`) => context.stop(self)
    case m2w.TaskReady                => log.warning("TaskIsReady unhandled !")
    case _                          => super.unhandled(message)
  }

  def sendToMaster(msg: Any): Unit = {
    clusterClient ! SendToAll("/user/master/singleton", msg)
  }

}

object Worker {

  def props(clusterClient: ActorRef, consumerGroup: String, executorProps: Props, registerInterval: FiniteDuration = 10.seconds): Props =
    Props(classOf[Worker], clusterClient, consumerGroup, executorProps, registerInterval)
}
