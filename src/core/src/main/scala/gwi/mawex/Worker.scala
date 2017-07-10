package gwi.mawex

import java.util.UUID

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import akka.cluster.client.ClusterClient.SendToAll

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Worker(masterId: String, clusterClient: ActorRef, consumerGroup: String, workExecutorProps: Props, registerInterval: FiniteDuration) extends Actor with ActorLogging {
  import Worker._

  import context.dispatcher

  private[this] val workerId = WorkerId(UUID.randomUUID().toString, consumerGroup)
  private[this] val registerWorker = context.system.scheduler.schedule(500.millis, registerInterval, clusterClient, SendToAll(s"/user/$masterId/singleton", w2m.Register(workerId)))
  private[this] val taskExecutor = context.watch(context.actorOf(workExecutorProps, "exec"))
  private[this] var currentTaskId: Option[TaskId] = None

  def taskId: TaskId = currentTaskId match {
    case Some(taskId) => taskId
    case None         => throw new IllegalStateException("Not working")
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case ex: Exception =>
      log.error(ex, "Executor crashed !!!")
      currentTaskId foreach { taskId => sendToMaster(w2m.TaskFinished(workerId, taskId, Left(ex.getMessage))) }
      context.become(idle)
      Restart
  }

  override def postStop(): Unit = registerWorker.cancel()

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
    case m2w.TaskResultChecked(id) if id == taskId =>
      sendToMaster(w2m.TaskRequest(workerId))
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
    case ReceiveTimeout =>
      log.info("No ack from master, retrying")
      sendToMaster(w2m.TaskFinished(workerId, taskId, result))
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(`taskExecutor`) => context.stop(self)
    case m2w.TaskReady              => log.warning("TaskIsReady unhandled !")
    case _                          => super.unhandled(message)
  }

  def sendToMaster(msg: Any): Unit = {
    clusterClient ! SendToAll(s"/user/$masterId/singleton", msg)
  }

}

object Worker {
  import scala.language.implicitConversions

  implicit def tryToEither[A](obj: Try[A]): Either[String, A] = {
    obj match {
      case Success(something) => Right(something)
      case Failure(err) => Left(err.getMessage)
    }
  }

  def props(masterId: String, clusterClient: ActorRef, consumerGroup: String, executorProps: Props, registerInterval: FiniteDuration = 5.seconds): Props =
    Props(classOf[Worker], masterId, clusterClient, consumerGroup, executorProps, registerInterval)
}
