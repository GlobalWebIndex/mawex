package gwi.mawex

import java.util.UUID

import akka.actor.SupervisorStrategy.{Resume, Stop}
import akka.actor._
import akka.cluster.client.ClusterClient.SendToAll

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Worker(masterId: String, clusterClient: ActorRef, workerId: WorkerId, workExecutorProps: Props, checkinInterval: FiniteDuration) extends Actor with ActorLogging {
  import Worker._
  import context.dispatcher

  private[this] val checkinWorker = context.system.scheduler.schedule(500.millis, checkinInterval, clusterClient, SendToAll(s"/user/$masterId/singleton", w2m.CheckIn(workerId)))
  private[this] val taskExecutor = context.watch(context.actorOf(workExecutorProps, "exec"))
  private[this] var currentTaskId: Option[TaskId] = None

  private[this] def taskId: TaskId = currentTaskId match {
    case Some(taskId) => taskId
    case None         => throw new IllegalStateException("Not working")
  }

  private[this] def sendToMaster(msg: Any): Unit = clusterClient ! SendToAll(s"/user/$masterId/singleton", msg)

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case ex: Exception =>
      log.error(ex, "Executor crashed !!!")
      currentTaskId.foreach { taskId => sendToMaster(w2m.TaskFinished(workerId, taskId, Left(ex.getMessage))) }
      context.become(waitingForAck(Failure(ex)))
      Resume
  }

  override def postStop(): Unit = {
    // note that Master is watching for Workers but they would have to be part of the same actor system for it to work
    sendToMaster(w2m.CheckOut(workerId))
    checkinWorker.cancel()
  }

  def receive: Receive = idle

  def idle: Receive = {
    case m2w.TaskReady =>
      sendToMaster(w2m.TaskRequest(workerId))

    case task@Task(id, job) =>
      log.info("Got task: {}", task)
      currentTaskId = Some(id)
      taskExecutor ! job
      context.become(working)
  }

  def working: Receive = {
    case e2w.TaskExecuted(result) =>
      log.info("Task is complete. Result {}", result)
      sendToMaster(w2m.TaskFinished(workerId, taskId, result))
      context.setReceiveTimeout(5.seconds)
      context.become(waitingForAck(result))
  }

  def waitingForAck(result: Try[Any]): Receive = {
    case m2w.TaskResultAck(id) if id == taskId =>
      sendToMaster(w2m.TaskRequest(workerId))
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
    case ReceiveTimeout =>
      log.info("No ack to Worker from Master, retrying")
      sendToMaster(w2m.TaskFinished(workerId, taskId, result))
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(`taskExecutor`) => context.stop(self)
    case _                          => super.unhandled(message)
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

  def props(masterId: String, clusterClient: ActorRef, consumerGroup: String, pod: String, executorProps: Props, checkinInterval: FiniteDuration = 5.seconds, workerId: String = UUID.randomUUID().toString): Props =
    Props(classOf[Worker], masterId, clusterClient, WorkerId(workerId.toString, consumerGroup, pod), executorProps, checkinInterval)
}
