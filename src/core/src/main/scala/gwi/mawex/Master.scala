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
import scala.collection.breakOut

class Master(masterId: String, taskTimeout: FiniteDuration, workerCheckinInterval: FiniteDuration) extends PersistentActor with ActorLogging {
  import Master._
  import WorkerRef._
  import context.dispatcher
  import w2m._

  private[this] implicit val logger: LoggingAdapter = log

  private[this] val mediator = DistributedPubSub(context.system).mediator
  ClusterClientReceptionist(context.system).registerService(self)

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = Cluster(context.system).selfRoles.find(_.startsWith("backend-")) match {
    case Some(role) => role + self.path.toStringWithoutAddress
    case None       => self.path.toStringWithoutAddress
  }

  private[this] val workersById = mutable.Map[WorkerId, WorkerRef]() // not event sourced
  private[this] var workState = State.empty // event sourced

  private[this] val validateTask = context.system.scheduler.schedule(5.second, 3.seconds, self, Validate)
  private[this] val cleanupTask = context.system.scheduler.schedule(5.second, 5.seconds, self, Cleanup)

  override def postStop(): Unit = {
    validateTask.cancel()
    cleanupTask.cancel()
  }

  override def receiveRecover: Receive = {
    case event: TaskDomainEvent =>
      workState = workState.updated(event)
      log.info("Replaying {}", event.getClass.getName)
  }

  private[this] def notifyWorkers(): Unit =
    workState.getPendingTasksConsumerGroups
      .foreach(workersById.offerTask)

  private[this] def handleHeartBeat(heartBeat: HeartBeat) = heartBeat match {
    case Validate =>
      workersById.validate(workState, workerCheckinInterval, taskTimeout)

    case Cleanup =>
      val tasksById = workersById.findProgressingOrphanTask(workState)
      persistAll(tasksById.keys.map(TaskFailed)(breakOut)) { failedEvent =>
        log.warning("Orphan processing task, his worker probably died in battle : {} !!!", failedEvent)
        workState = workState.updated(failedEvent)
        mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(tasksById(failedEvent.taskId), Left(s"Orphan progressing task ${failedEvent.taskId} !!!")))
      }
  }

  private[this] def handleWorkerCommand(cmd: Worker2MasterCommand) = cmd match {
    case w2m.CheckIn(workerId) =>
      workersById.checkIn(workerId, sender(), context).foreach( _ => notifyWorkers() )

    case w2m.CheckOut(workerId) =>
      log.info("Checking out worker {} ...", workerId)
      workersById.checkOut(workerId, context)
        .flatMap(workState.getTaskInProgress)
        .foreach { task =>
          persist(TaskFailed(task.id)) { e =>
            workState = workState.updated(e)
            notifyWorkers()
            mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, Left(s"Worker $workerId checked out while processing task ${task.id} ...")))
          }
        }

    case w2m.TaskRequest(workerId) =>
      if (workersById.getBusyWorkers(workerId.pod).isEmpty) {
        val s = sender()
        workState.getPendingTasks.toSeq.sortBy(_._2).map(_._1)
          .find(_.id.consumerGroup == workerId.consumerGroup)
          .foreach { task =>
            persist(TaskStarted(task.id)) { event =>
              workState = workState.updated(event)
              workersById.get(workerId).filter(_.status == Idle).foreach { _ =>
                log.info("Giving worker {} some task {}", workerId, task.id)
                workersById.employ(workerId, task.id)
                s ! task
              }
            }
          }
      }

    case w2m.TaskFinished(workerId, taskId, resultEither) =>
      val s = sender()
      workState.getTaskInProgress(taskId).foreach { task =>
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
          notifyWorkers()
          mediator ! DistributedPubSubMediator.Publish(masterId, TaskResult(task, resultEither))
          s ! m2w.TaskResultAck(taskId)
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

    case GetMawexState =>
      sender() ! MawexState(workState, workersById.toMap)

    case Terminated(ref) =>
      log.info(s"Worker ${ref.path.name} is terminating ...")
      workersById.getWorkerIdByRef(ref).foreach ( workerId => self ! w2m.CheckOut(workerId) )

    case x =>
      log.error(s"Received unknown message $x")
  }

}

object Master {

  /** System.nanoTime() is a relative time, probably not thread-safe, working with timers of lower precision than 1 millisecond is very hard, using Thread.sleep(1) is a reasonable trade-off */
  def distinctCurrentMillis: Long = {
    Thread.sleep(1)
    System.currentTimeMillis()
  }

  protected[mawex] sealed trait HeartBeat
  protected[mawex] case object Validate extends HeartBeat
  protected[mawex] case object Cleanup extends HeartBeat

  def props(resultTopicName: String, taskTimeout: FiniteDuration, workerCheckinInterval: FiniteDuration = 5.seconds): Props = Props(classOf[Master], resultTopicName, taskTimeout, workerCheckinInterval)

}
