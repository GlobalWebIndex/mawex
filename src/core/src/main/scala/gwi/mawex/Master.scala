package gwi.mawex

import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.persistence.PersistentActor
import gwi.mawex.State._

import scala.concurrent.duration._

class Master(resultTopicName: String, taskTimeout: FiniteDuration, workerRegisterInterval: FiniteDuration) extends PersistentActor with ActorLogging {
  import Master._

  private[this] val mediator = DistributedPubSub(context.system).mediator
  ClusterClientReceptionist(context.system).registerService(self)

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) => role + self.path.name
    case None       => self.path.name
  }

  // workers state is not event sourced
  private[this]  var workersById = Map[WorkerId, WorkerStatus]()

  // workState is event sourced
  private[this]  var workState = State.empty

  import context.dispatcher
  private[this] val cleanupTask = context.system.scheduler.schedule(1.second, 1.second, self, CleanupTick)

  private[this] def notifyWorkers(): Unit =
    workState.getPendingTasks
      .foreach { task =>
        workersById
          .filterKeys(_.consumerGroup == task.id.consumerGroup)
          .foreach {
            case (_, WorkerStatus(ref, Idle, _)) =>
              ref ! m2w.TaskReady
            case _ => // busy
          }
      }

  private[this] def changeWorkerToIdle(workerId: WorkerId, taskId: TaskId): Unit =
    workersById.get(workerId) match {
      case Some(s @ WorkerStatus(_, Busy(busyTaskId), _)) if taskId == busyTaskId =>
        workersById += (workerId -> s.copy(status = Idle))
      case _ =>
        log.warning("Worker {} state probably not persisted after recovery, workId {} missing ...", workerId, taskId)
    }

  override def postStop(): Unit = cleanupTask.cancel()

  override def receiveRecover: Receive = {
    case event: TaskDomainEvent =>
      workState = workState.updated(event)
      log.info("Replaying {}", event.getClass.getName)
  }

  override def receiveCommand: Receive = {
    case w2m.Register(workerId) =>
      val s = sender()
      val workerStatusOpt = workersById.get(workerId)
      if (workerStatusOpt.exists(_.ref != s)) { // check for worker's ref change
        val oldWorkerStatus = workerStatusOpt.get
        context.watch(s)
        context.unwatch(oldWorkerStatus.ref)
        workersById += (workerId -> oldWorkerStatus.copy(ref = s, registrationTime = System.currentTimeMillis()))
        log.warning("Existing worker {} registered again with different ActorRef !", workerId)
      } else if (workerStatusOpt.isEmpty) {
        log.info("Worker registered: {}", workerId)
        context.watch(s)
        workersById += (workerId -> WorkerStatus(s, status = Idle, System.currentTimeMillis()))
        notifyWorkers()
      } else {
        workersById += (workerId -> workerStatusOpt.get.copy(registrationTime = System.currentTimeMillis()))
      }

    case w2m.TaskRequest(workerId) =>
      workState.getPendingTasks
        .find(_.id.consumerGroup == workerId.consumerGroup)
        .foreach { task =>
          workersById.get(workerId) match {
            case Some(s @ WorkerStatus(_, Idle, _)) =>
              persist(TaskStarted(task.id)) { event =>
                workState = workState.updated(event)
                log.info("Giving worker {} some task {}", workerId, task.id)
                workersById += (workerId -> s.copy(status = Busy(task.id)))
                sender() ! task
              }
            case _ =>
          }
        }

    case w2m.TaskFinished(workerId, taskId, r@Right(result)) =>
      sender() ! m2w.TaskResultChecked(taskId)
      if (workState.isDone(taskId)) { // idempotent
        log.warning("Previous Ack was lost, confirm again that work is done")
      } else {
        workState.getTaskInProgress(taskId) match {
          case None =>
            log.warning("Task {} not in progress, reported as done by worker {}", taskId, workerId)
          case Some(finishedTask) =>
            log.info("Task {} is done by worker {}", taskId, workerId)
            changeWorkerToIdle(workerId, taskId)
            persist(TaskCompleted(taskId, result)) { event =>
              workState = workState.updated(event)
              mediator ! DistributedPubSubMediator.Publish(resultTopicName, TaskResult(finishedTask, r))
            }
        }
      }

    case w2m.TaskFinished(workerId, taskId, r@Left(error)) =>
      sender() ! m2w.TaskResultChecked(taskId)
      workState.getTaskInProgress(taskId) match {
        case None =>
          log.warning("Task {} is supposed to be in progress by worker {}", taskId, workerId)
        case Some(finishedTask) =>
          log.warning("Task {} hasn't finished because of worker {} crash {}", taskId, workerId, error)
          changeWorkerToIdle(workerId, taskId)
          persist(WorkerFailed(taskId)) { event =>
            workState = workState.updated(event)
            mediator ! DistributedPubSubMediator.Publish(resultTopicName, TaskResult(finishedTask, r))
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
      workersById.find(_._2.ref == ref).map(_._1).foreach { workerId =>
        log.info(s"worker $workerId terminated ...")
        workersById -= workerId
      }

    case CleanupTick =>
      for ((workerId, WorkerStatus(_, _, registrationTime)) <- workersById if (System.currentTimeMillis() - registrationTime) > workerRegisterInterval.toMillis * 6) {
        log.warning(s"worker $workerId has not registered, context.watch doesn't work !!!")
      }
      for ((taskId, creationTime) <- workState.getAcceptedTasks if (System.currentTimeMillis() - creationTime) > taskTimeout.toMillis) {
        workersById
          .collectFirst { case (workerId, WorkerStatus(_, Busy(busyTaskId), _)) if taskId == busyTaskId => workerId }
          .fold(log.warning("Task {} timed out, no worker has asked for it !!!", taskId))(log.warning("Task {} timed out, worker {} has not finished on time ...", taskId, _))
      }
  }

}

object Master {
  private sealed trait Status
  private case object Idle extends Status
  private case class Busy(taskId: TaskId) extends Status
  private case class WorkerStatus(ref: ActorRef, status: Status, registrationTime: Long)
  private case object CleanupTick

  def props(resultTopicName: String, taskTimeout: FiniteDuration, workerRegisterInterval: FiniteDuration = 5.seconds): Props = Props(classOf[Master], resultTopicName, taskTimeout, workerRegisterInterval)

}
