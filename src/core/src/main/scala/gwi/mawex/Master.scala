package gwi.mawex

import akka.actor.{ActorIdentity, ActorLogging, ActorPath, ActorRef, ActorSystem, Identify, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import gwi.mawex.InternalProtocol._
import gwi.mawex.OpenProtocol._
import gwi.mawex.State._

import scala.concurrent.duration.{Deadline, FiniteDuration, _}
import scala.util.{Failure, Success}

class Master(workTimeout: FiniteDuration) extends PersistentActor with ActorLogging {
  import Master._

  val mediator = DistributedPubSub(context.system).mediator
  ClusterClientReceptionist(context.system).registerService(self)

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) => role + "-master"
    case None       => "master"
  }

  // workers state is not event sourced
  private var workersById = Map[WorkerId, WorkerStatus]()

  // workState is event sourced
  private var workState = State.empty

  import context.dispatcher
  val cleanupTask = context.system.scheduler.schedule(workTimeout / 2, workTimeout / 2, self, CleanupTick)

  override def postStop(): Unit = cleanupTask.cancel()

  override def receiveRecover: Receive = {
    case event: TaskDomainEvent =>
      workState = workState.updated(event)
      log.info("Replaying {}", event.getClass.getName)
  }

  override def receiveCommand: Receive = {
    case w2m.Register(workerId) =>
      val s = sender()
      if (workersById.get(workerId).exists(_.ref != s)) { // check for worker's ref change
        val oldWorker = workersById(workerId)
        context.watch(s)
        context.unwatch(oldWorker.ref)
        workersById += (workerId -> oldWorker.copy(ref = s))
        log.warning("Existing worker {} registered again with different ActorRef !", workerId)
      } else if (!workersById.contains(workerId)) {
        log.info("Worker registered: {}", workerId)
        context.watch(s)
        workersById += (workerId -> WorkerStatus(s, status = Idle))
        workState.getPendingTasks
          .find(_.id.consumerGroup == workerId.consumerGroup)
          .foreach(_ => s ! m2w.TaskReady)
      }

    case w2m.TaskRequest(workerId) =>
      workState.getPendingTasks
        .find(_.id.consumerGroup == workerId.consumerGroup)
        .foreach { task =>
          workersById.get(workerId) match {
            case Some(s @ WorkerStatus(_, Idle)) =>
              persist(TaskStarted(task.id)) { event =>
                workState = workState.updated(event)
                log.info("Giving worker {} some task {}", workerId, task.id)
                workersById += (workerId -> s.copy(status = Busy(task.id, Deadline.now + workTimeout)))
                sender() ! task
              }
            case _ =>
          }
        }

    case w2m.TaskFinished(workerId, taskId, r@Success(result)) =>
      if (workState.isDone(taskId)) { // idempotent
        log.warning("Previous Ack was lost, confirm again that work is done")
        sender() ! m2w.TaskChecked(taskId)
      } else {
        workState.getTaskInProgress(taskId) match {
          case None =>
            log.warning("Task {} not in progress, reported as done by worker {}", taskId, workerId)
          case Some(finishedTask) =>
            log.info("Task {} is done by worker {}", taskId, workerId)
            changeWorkerToIdle(workerId, taskId)
            persist(TaskCompleted(taskId, result)) { event =>
              workState = workState.updated(event)
              mediator ! DistributedPubSubMediator.Publish(ResultsTopic, TaskResult(finishedTask, r))
              sender ! m2w.TaskChecked(taskId) // Ack back to original sender
            }
        }
      }

    case w2m.TaskFinished(workerId, taskId, r@Failure(ex)) =>
      workState.getTaskInProgress(taskId) match {
        case None =>
          log.warning("Task {} is supposed to be in progress by worker {}", taskId, workerId)
        case Some(finishedTask) =>
          log.warning("Task {} hasn't finished because of worker {} crash {}", taskId, workerId, ex.getMessage)
          changeWorkerToIdle(workerId, taskId)
          persist(WorkerFailed(taskId)) { event =>
            workState = workState.updated(event)
            mediator ! DistributedPubSubMediator.Publish(ResultsTopic, TaskResult(finishedTask, r))
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

    case CleanupTick =>
      for ((workerId, s@ WorkerStatus(_, Busy(taskId, timeout))) <- workersById if timeout.isOverdue) {
        log.warning("Task timed out: {}", taskId)
      }
  }

  def notifyWorkers(): Unit =
    workState.getPendingTasks
      .foreach { task =>
        workersById
          .filterKeys(_.consumerGroup == task.id.consumerGroup)
          .foreach {
            case (_, WorkerStatus(ref, Idle)) =>
              ref ! m2w.TaskReady
            case _ => // busy
          }
      }

  def changeWorkerToIdle(workerId: WorkerId, taskId: TaskId): Unit =
    workersById.get(workerId) match {
      case Some(s @ WorkerStatus(_, Busy(`taskId`, _))) =>
        workersById += (workerId -> s.copy(status = Idle))
      case _ =>
        log.warning("Worker {} state probably not persisted after recovery, workId {} missing ...", workerId, taskId)
    }

  // TODO do something with State#doneTaskIds, the set will grow indefinitely which is not good for a use case with millions of tasks

  override def unhandled(message: Any): Unit = message match {
    case Terminated(ref) =>
      workersById.find(_._2.ref == ref).map(_._1).foreach { workerId =>
        workersById -= workerId
      }
    case _ =>
      super.unhandled(message)
  }

}

object Master {
  private sealed trait Status
  private case object Idle extends Status
  private case class Busy(taskId: TaskId, deadline: Deadline) extends Status
  private case class WorkerStatus(ref: ActorRef, status: Status)
  private case object CleanupTick

  val ResultsTopic = "results"

  def apply(workTimeout: FiniteDuration): Props = Props(classOf[Master], workTimeout)

  def startJournal(system: ActorSystem, path: ActorPath): Unit = {
    import system.dispatcher
    implicit val timeout = Timeout(15.seconds)
    system.actorOf(Props[SharedLeveldbStore], "store")
    (system.actorSelection(path) ? Identify(None)) onComplete {
      case Success(ActorIdentity(_, Some(ref))) =>
        SharedLeveldbJournal.setStore(ref, system)
      case Success(_) =>
        system.log.error("Shared journal not started at {}", path)
        system.terminate()
      case Failure(ex) =>
        system.log.error("Lookup of shared journal at {} timed out", path)
        system.terminate()
    }
  }

}
