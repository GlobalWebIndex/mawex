package gwi.mawex.worker

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import akka.cluster.client.ClusterClient.SendToAll
import gwi.mawex._
import gwi.mawex.executor.SandBox

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import Common.ThrowablePimp

class Worker(masterId: String, clusterClient: ActorRef, workerId: WorkerId, sandBoxProps: Props, taskTimeout: FiniteDuration, checkinInterval: FiniteDuration) extends Actor with ActorLogging {
  import context.dispatcher

  private[this] val MasterAddress   = s"/user/$masterId/singleton"
  private[this] val checkinWorker   = master_checkMeInPeriodically
  private[this] val executorSandBox = context.actorOf(sandBoxProps, SandBox.ActorName)
  private[this] var currentTaskId   = Option.empty[TaskId]

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case ex: Exception =>
      log.error(ex, "Executor crashed !!!")
      currentTaskId.foreach(master_finishTask(_, Left(s"Executor crashed, ${ex.messageWithStackTraceToString}")))
      Restart
  }

  override def preStart(): Unit = {
    log.info(s"Worker $workerId starting...")
  }

  override def postStop(): Unit = {
    // note that Master is watching for Workers but they would have to be part of the same actor system for it to work
    log.info(s"Worker $workerId stopping...")
    master_checkMeOut()
    checkinWorker.cancel()
  }

  private[this] def master_checkMeOut() =
    clusterClient ! SendToAll(MasterAddress, w2m.CheckOut(workerId))

  private[this] def master_giveMeTask() =
    clusterClient ! SendToAll(MasterAddress,w2m.TaskRequest(workerId))

  private[this] def master_checkMeInPeriodically =
    context.system.scheduler.schedule(5.millis, checkinInterval, clusterClient, SendToAll(MasterAddress, w2m.CheckIn(workerId)))

  private[this] def master_finishTask(taskId: TaskId, result: Either[String, Any]) = {
    clusterClient ! SendToAll(MasterAddress, w2m.TaskFinished(workerId, taskId, result))
    context.setReceiveTimeout(5.seconds)
    context.become(waitingForAck(result))
  }

  def receive: Receive = idle

  private[this] def idle: Receive = {
    case m2w.TaskReady =>
      master_giveMeTask()

    case task@Task(id, _) =>
      log.debug("Got task: {}", task)
      currentTaskId = Some(id)
      executorSandBox ! task
      context.setReceiveTimeout(taskTimeout)
      context.become(working)
  }

  private[this] def working: Receive = {
    case TaskResult(taskId, result) =>
      log.debug("Task is complete. Result {}", result)
      master_finishTask(taskId, result)
    case ReceiveTimeout =>
      log.warning("No response from Executor to Worker ...")
      master_finishTask(currentTaskId.get, Left(s"Task $currentTaskId timed out in worker $workerId ..."))
  }

  private[this] def waitingForAck(result: Either[String, Any]): Receive = {
    case m2w.TaskResultAck(id) if currentTaskId.contains(id) =>
      currentTaskId = Option.empty
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
      master_giveMeTask()
    case ReceiveTimeout =>
      log.warning("No ack to Worker from Master, retrying ...")
      master_finishTask(currentTaskId.get, result)
  }

}

object Worker {
  import scala.language.implicitConversions
  val SystemName = "WorkerSystem"

  implicit def tryToEither[A](obj: Try[A]): Either[String, A] = {
    obj match {
      case Success(something) => Right(something)
      case Failure(err) => Left(err.messageWithStackTraceToString)
    }
  }

  def props(masterId: String, clusterClient: ActorRef, workerId: WorkerId, sandBoxProps: Props, taskTimeout: FiniteDuration, checkinInterval: FiniteDuration = 5.seconds): Props =
    Props(classOf[Worker], masterId, clusterClient, workerId, sandBoxProps, taskTimeout, checkinInterval)
}
