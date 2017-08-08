package gwi.mawex

import akka.actor.{ActorLogging, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.event.LoggingAdapter
import akka.persistence.PersistentActor
import gwi.mawex.State._

import scala.collection.mutable
import scala.concurrent.duration._

class Master(masterId: String, taskTimeout: FiniteDuration, workerCheckinInterval: FiniteDuration) extends PersistentActor with ActorLogging {
  import Master._
  import WorkerRef._
  import context.dispatcher

  private[this] implicit val logger: LoggingAdapter = log

  private[this] val mediator = DistributedPubSub(context.system).mediator
  ClusterClientReceptionist(context.system).registerService(self)

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) => role + self.path.name
    case None       => self.path.name
  }

  private[this] val workersById = mutable.Map[WorkerId, WorkerRef]() // not event sourced
  private[this] var workState = State.empty // event sourced

  private[this] val notifyTask = context.system.scheduler.schedule(500.millis, 500.millis, self, Notify)
  private[this] val validateTask = context.system.scheduler.schedule(5.second, 3.seconds, self, Validate)
  private[this] val cleanupTask = context.system.scheduler.schedule(5.second, 5.seconds, self, Cleanup)

  override def postStop(): Unit = {
    notifyTask.cancel()
    validateTask.cancel()
    cleanupTask.cancel()
  }

  override def receiveRecover: Receive = {
    case event: TaskDomainEvent =>
      workState = workState.updated(event)
      log.info("Replaying {}", event.getClass.getName)
  }

  private[this] def notifyWorkers(): Unit =
    workState.getPendingTasks.keySet.map(_.id.consumerGroup)
      .flatMap(workersById.getIdleWorkerRef)
      .foreach(_ ! m2w.TaskReady)

  private[this] def handleHeartBeat(heartBeat: HeartBeat) = heartBeat match {
    case Notify =>
      notifyWorkers()

    case Validate =>
      workersById.validate(workState, workerCheckinInterval, taskTimeout)

    case Cleanup =>
      workersById.findProgressingOrphanTask(workState)
        .foreach{ task =>
          log.warning("Orphan processing task, his worker probably died in battle : {} !!!", task)
          persist(TaskFailed(task.id)) { e =>
            workState = workState.updated(e)
            mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, Left(s"Orphan progressing task ${task.id} !!!")))
          }
        }
  }

  private[this] def handleWorkerCommand(cmd: Worker2MasterCommand) = cmd match {
    case w2m.CheckIn(workerId) =>
      workersById.checkIn(workerId, sender(), context)

    case w2m.CheckOut(workerId) =>
      log.info("Checking out worker {} ...", workerId)
      workersById.checkOut(workerId, context)
        .flatMap(workState.getTaskInProgress)
        .foreach { task =>
          persist(TaskFailed(task.id)) { e =>
            workState = workState.updated(e)
            mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, Left(s"Worker $workerId checkde out while processing task ${task.id} ...")))
          }
        }

    case w2m.TaskRequest(workerId) =>
      if (workersById.getBusyWorkers(workerId.pod).isEmpty) {
        val s = sender()
        workState.getPendingTasks.keySet
          .find(_.id.consumerGroup == workerId.consumerGroup)
          .foreach { task =>
            workersById.get(workerId).filter(_.status == Idle).foreach { _ =>
              persist(TaskStarted(task.id)) { event =>
                workState = workState.updated(event)
                log.info("Giving worker {} some task {}", workerId, task.id)
                workersById.employ(workerId, task.id)
                s ! task
              }
            }
          }
      }

    case w2m.TaskFinished(workerId, taskId, resultEither) =>
      sender() ! m2w.TaskResultAck(taskId)
      workState.getTaskInProgress(taskId).foreach { task => // idempotency - Previous Ack sent to Worker might get lost ...
        val event =
          resultEither match {
            case Right(result) =>
              log.info("Task {} is done by worker {}", taskId, workerId)
              TaskCompleted(taskId, result)
            case Left(error) =>
              log.error("Task {} crashed in worker {} due to : {}", taskId, workerId, error)
              TaskFailed(taskId)
          }
        persist(event) { e =>
          workState = workState.updated(e)
          workersById.idle(workerId, taskId)
          mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, resultEither))
        }
      }
    }

  override def receiveCommand: Receive = {
    case heartBeat: HeartBeat =>
      handleHeartBeat(heartBeat)

    case cmd: Worker2MasterCommand =>
      handleWorkerCommand(cmd)

    case task: Task =>
      val s = sender()
      if (workState.isAccepted(task.id)) { // idempotent
        s ! m2p.TaskAck(task.id)
      } else {
        log.info("Accepted task: {}", task.id)
        persist(TaskAccepted(task)) { event =>
          s ! m2p.TaskAck(task.id) // Ack back to original sender
          workState = workState.updated(event)
          notifyWorkers()
        }
      }

    case Terminated(ref) =>
      log.info(s"Worker ${ref.path.name} is terminating ...")
      workersById.getWorkerIdByRef(ref).foreach ( workerId => self ! w2m.CheckOut(workerId) )

    case x =>
      log.error(s"Received unknown message $x")
  }

}

object Master {

  protected[mawex] sealed trait HeartBeat
  protected[mawex] case object Notify extends HeartBeat
  protected[mawex] case object Validate extends HeartBeat
  protected[mawex] case object Cleanup extends HeartBeat

  def props(resultTopicName: String, taskTimeout: FiniteDuration, workerCheckinInterval: FiniteDuration = 5.seconds): Props = Props(classOf[Master], resultTopicName, taskTimeout, workerCheckinInterval)

}
