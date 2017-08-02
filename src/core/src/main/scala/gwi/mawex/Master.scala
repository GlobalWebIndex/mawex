package gwi.mawex

import akka.actor.{ActorContext, ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.event.LoggingAdapter
import akka.persistence.PersistentActor
import gwi.mawex.State._

import scala.collection.mutable
import scala.concurrent.duration._

class Master(masterId: String, taskTimeout: FiniteDuration, workerRegisterInterval: FiniteDuration) extends PersistentActor with ActorLogging {
  import Master._

  private[this] implicit val logger: LoggingAdapter = log

  private[this] val mediator = DistributedPubSub(context.system).mediator
  ClusterClientReceptionist(context.system).registerService(self)

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) => role + self.path.name
    case None       => self.path.name
  }

  // workers state is not event sourced
  private[this] val workersById = mutable.Map[WorkerId, WorkerStatus]()

  // workState is event sourced
  private[this] var workState = State.empty

  import context.dispatcher
  private[this] val cleanupTask = context.system.scheduler.schedule(1.second, 1.second, self, CleanupTick)

  override def postStop(): Unit = cleanupTask.cancel()

  override def receiveRecover: Receive = {
    case event: TaskDomainEvent =>
      workState = workState.updated(event)
      log.info("Replaying {}", event.getClass.getName)
  }

  private[this] def notifyWorkers(): Unit =
    workState.getPendingConsumerGroups
      .flatMap(workersById.getIdleWorkerRef)
      .foreach(_ ! m2w.TaskReady)

  override def receiveCommand: Receive = {
    case w2m.Register(workerId) =>
      workersById.registerWorker(workerId, sender(), context).foreach( _ => notifyWorkers() )

    case w2m.TaskRequest(workerId) =>
      workState.getPendingTasks
        .find(_.id.consumerGroup == workerId.consumerGroup)
        .foreach { task =>
          workersById.get(workerId).filter(_.status == Idle).foreach { _ =>
            persist(TaskStarted(task.id)) { event =>
              workState = workState.updated(event)
              log.info("Giving worker {} some task {}", workerId, task.id)
              workersById.busy(workerId, task.id)
              sender() ! task
            }
          }
        }

    case w2m.TaskFinished(workerId, taskId, resultEither) =>
      sender() ! m2w.TaskResultChecked(taskId)
      workState.getTaskInProgress(taskId).foreach { task => // idempotency - Previous Ack sent to Worker might get lost ...
        val event =
          resultEither match {
            case Right(result) =>
              log.info("Task {} is done by worker {}", taskId, workerId)
              TaskCompleted(taskId, result)
            case Left(error) =>
              log.warning("Task {} hasn't finished because of worker {} crash {}", taskId, workerId, error)
              WorkerFailed(taskId)
          }
        persist(event) { e =>
          workState = workState.updated(e)
          workersById.idle(workerId, taskId)
          mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, resultEither))
        }
      }

    case task: Task =>
      if (workState.isAccepted(task.id)) { // idempotent
        sender() ! m2p.TaskAck(task.id)
      } else {
        log.info("Accepted task: {}", task.id)
        persist(TaskAccepted(task)) { event =>
          sender() ! m2p.TaskAck(task.id) // Ack back to original sender
          workState = workState.updated(event)
          notifyWorkers()
        }
      }

    case Terminated(ref) =>
      log.info(s"Worker ${ref.path.name} is terminating ...")
      workersById.terminateWorker(ref)

    case CleanupTick =>
      workersById.validate(workState, workerRegisterInterval, taskTimeout)
  }

}

object Master {
  protected[mawex] sealed trait Status
  protected[mawex] case object Idle extends Status
  protected[mawex] case class Busy(taskId: TaskId) extends Status
  protected[mawex] case class WorkerStatus(ref: ActorRef, status: Status, registrationTime: Long)
  protected[mawex] case object CleanupTick

  def props(resultTopicName: String, taskTimeout: FiniteDuration, workerRegisterInterval: FiniteDuration = 5.seconds): Props = Props(classOf[Master], resultTopicName, taskTimeout, workerRegisterInterval)

  implicit class WorkersPimp(underlying: mutable.Map[WorkerId, WorkerStatus]) {

    private[this] def adjust(k: WorkerId)(f: Option[WorkerStatus] => WorkerStatus): mutable.Map[WorkerId, WorkerStatus] =
      underlying += (k -> f(underlying.get(k)))

    private[this] def changeWorkerRef(workerId: WorkerId, newRef: ActorRef): mutable.Map[WorkerId, WorkerStatus] =
      adjust(workerId)(_.get.copy(ref = newRef, registrationTime = System.currentTimeMillis()))

    private[this] def registerNewWorker(workerId: WorkerId, ref: ActorRef): mutable.Map[WorkerId, WorkerStatus] =
      underlying += (workerId -> WorkerStatus(ref, status = Idle, System.currentTimeMillis()))

    private[this] def registerOldWorker(workerId: WorkerId): mutable.Map[WorkerId, WorkerStatus] =
      adjust(workerId)(_.get.copy(registrationTime = System.currentTimeMillis()))

    def busy(workerId: WorkerId, taskId: TaskId): mutable.Map[WorkerId, WorkerStatus] =
      adjust(workerId)(_.get.copy(status = Busy(taskId)))

    def idle(workerId: WorkerId, taskId: TaskId)(implicit log: LoggingAdapter): Unit =
      underlying.get(workerId) match {
        case Some(s @ WorkerStatus(_, Busy(busyTaskId), _)) if taskId == busyTaskId =>
          underlying += (workerId -> s.copy(status = Idle))
        case _ =>
          log.warning("Worker {} state probably not persisted after recovery, workId {} missing ...", workerId, taskId)
      }

    def terminateWorker(ref: ActorRef)(implicit log: LoggingAdapter): Unit =
      underlying
        .collectFirst { case (workerId, status) if status.ref == ref => workerId }
        .foreach { workerId =>
          log.info(s"worker $workerId terminated ...")
          underlying -= workerId
        }

    def validate(workState: State, workerRegisterInterval: FiniteDuration, taskTimeout: FiniteDuration)(implicit log: LoggingAdapter): Unit = {
      for ((workerId, WorkerStatus(_, _, registrationTime)) <- underlying if (System.currentTimeMillis() - registrationTime) > workerRegisterInterval.toMillis * 6) {
        log.warning(s"worker $workerId has not registered, context.watch doesn't work !!!")
      }
      for ((taskId, creationTime) <- workState.getAcceptedTasks if (System.currentTimeMillis() - creationTime) > taskTimeout.toMillis) {
        underlying
          .collectFirst { case (workerId, WorkerStatus(_, Busy(busyTaskId), _)) if taskId == busyTaskId => workerId }
          .fold(log.warning("Task {} timed out, no worker has asked for it !!!", taskId))(log.warning("Task {} timed out, worker {} has not finished on time ...", taskId, _))
      }
    }

    def getIdleWorkerRef(consumerGroup: String): Option[ActorRef] =
      underlying.collectFirst { case (workerId, WorkerStatus(ref, Idle, _)) if workerId.consumerGroup == consumerGroup => ref }

    def registerWorker(workerId: WorkerId, sender: ActorRef, context: ActorContext)(implicit log: LoggingAdapter): Option[WorkerId] = {
      val workerStatusOpt = underlying.get(workerId)
      if (workerStatusOpt.exists(_.ref != sender)) { // check for worker's ref change
        val oldWorkerStatus = workerStatusOpt.get
        context.watch(sender)
        context.unwatch(oldWorkerStatus.ref)
        changeWorkerRef(workerId, sender)
        log.warning("Existing worker {} registered again with different ActorRef !", workerId)
        None
      } else if (workerStatusOpt.isEmpty) {
        log.info("Worker registered: {}", workerId)
        context.watch(sender)
        registerNewWorker(workerId, sender)
        Some(workerId)
      } else {
        registerOldWorker(workerId)
        None
      }
    }
  }

}
