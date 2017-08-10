package gwi.mawex

import akka.actor.SupervisorStrategy.{Resume, Stop}
import akka.actor._
import akka.cluster.client.ClusterClient.SendToAll

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Worker(masterId: String, clusterClient: ActorRef, workerId: WorkerId, workExecutorProps: Props, taskTimeout: FiniteDuration, checkinInterval: FiniteDuration) extends Actor with ActorLogging {
  import Worker._
  import context.dispatcher

  private[this] val MasterAddress = s"/user/$masterId/singleton"
  private[this] val checkinWorker = master_checkMeInPeriodically
  private[this] val taskExecutor  = context.watch(context.actorOf(workExecutorProps, "exec"))
  private[this] var currentTaskId = Option.empty[TaskId]

  private[this] def taskId: TaskId = currentTaskId match {
    case Some(taskId) => taskId
    case None         => throw new IllegalStateException("Not working")
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case ex: Exception =>
      log.error(ex, "Executor crashed !!!")
      currentTaskId.foreach ( _ => master_finishTask(Failure(ex)) )
      Resume
  }

  override def postStop(): Unit = {
    // note that Master is watching for Workers but they would have to be part of the same actor system for it to work
    master_checkMeOut()
    checkinWorker.cancel()
  }

  private[this] def master_checkMeOut() =
    clusterClient ! SendToAll(MasterAddress, w2m.CheckOut(workerId))

  private[this] def master_giveMeTask() =
    clusterClient ! SendToAll(MasterAddress,w2m.TaskRequest(workerId))

  private[this] def master_checkMeInPeriodically =
    context.system.scheduler.schedule(5.millis, checkinInterval, clusterClient, SendToAll(MasterAddress, w2m.CheckIn(workerId)))

  private[this] def master_finishTask(result: Try[Any]) = {
    clusterClient ! SendToAll(MasterAddress, w2m.TaskFinished(workerId, taskId, result))
    context.setReceiveTimeout(5.seconds)
    context.become(waitingForAck(result))
  }

  def receive: Receive = idle

  def idle: Receive = {
    case m2w.TaskReady =>
      master_giveMeTask()

    case task@Task(id, job) =>
      log.info("Got task: {}", task)
      currentTaskId = Some(id)
      taskExecutor ! job
      context.setReceiveTimeout(taskTimeout)
      context.become(working)
  }

  def working: Receive = {
    case e2w.TaskExecuted(result) =>
      log.info("Task is complete. Result {}", result)
      master_finishTask(result)
    case ReceiveTimeout =>
      log.info("No response from Executor to Worker ...")
      master_finishTask(Failure(new RuntimeException(s"Task $taskId timed out in worker $workerId ...")))
  }

  def waitingForAck(result: Try[Any]): Receive = {
    case m2w.TaskResultAck(id) if id == taskId =>
      master_giveMeTask()
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
    case ReceiveTimeout =>
      log.info("No ack to Worker from Master, retrying ...")
      master_finishTask(result)
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

  def props(masterId: String, clusterClient: ActorRef, workerId: WorkerId, executorProps: Props, taskTimeout: FiniteDuration, checkinInterval: FiniteDuration = 5.seconds): Props =
    Props(classOf[Worker], masterId, clusterClient, workerId, executorProps, taskTimeout, checkinInterval)
}
