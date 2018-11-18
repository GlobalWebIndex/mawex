package gwi.mawex.master

import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import akka.cluster.Cluster
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.event.LoggingAdapter
import akka.persistence.PersistentActor
import gwi.mawex._
import gwi.mawex.master.State._

import scala.collection.{breakOut, mutable}
import scala.concurrent.duration._

class Master(conf: Master.Config) extends PersistentActor with ActorLogging {
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
      workersById.validate(workState, conf)

    case Cleanup =>
      val tasksById = workersById.findProgressingOrphanTask(workState)
      persistAll(tasksById.keys.map(TaskFailed)(breakOut)) { failedEvent =>
        log.warning("Orphan processing task, his worker probably died in battle : {} !!!", failedEvent)
        workState = workState.updated(failedEvent)
        mediator ! DistributedPubSubMediator.Publish(conf.masterId, TaskResult(tasksById(failedEvent.taskId), Left(s"Orphan progressing task ${failedEvent.taskId} !!!")))
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
            mediator ! DistributedPubSubMediator.Publish(conf.masterId, TaskResult(task, Left(s"Worker $workerId checked out while processing task ${task.id} ...")))
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
                mediator ! DistributedPubSubMediator.Publish(conf.masterId, m2p.TaskScheduled(task.id))
                s ! task
              }
            }
          }
      }

    case w2m.TaskFinished(workerId, taskId, resultEither) =>
      workState.getTaskInProgress(taskId) match {
        case Some(task) => handleTaskInProgress(workerId, task, resultEither, sender())
        case None =>
          log.info("Task {} finished by worker {} isn't in progress", taskId.id, workerId)
          sender() ! m2w.TaskResultAck(taskId)
      }
    }

  private def handleTaskInProgress(workerId: WorkerId, task: Task, resultEither: Either[String, Any], s: ActorRef) {
    val event =
      resultEither match {
        case Right(result) =>
          log.info("Task {} is done by worker {}", task.id, workerId)
          TaskCompleted(task.id, result)
        case Left(error) =>
          log.error("Task {} crashed in worker {} due to : {}", task.id, workerId, error)
          TaskFailed(task.id)
      }
    persist(event) { e =>
      workState = workState.updated(e)
      workersById.idle(workerId, task.id)
      notifyWorkers()
      mediator ! DistributedPubSubMediator.Publish(conf.masterId, TaskResult(task, resultEither))
      s ! m2w.TaskResultAck(task.id)
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

  case class Config(masterId: String, progressingTaskTimeout: FiniteDuration, pendingTaskTimeout: FiniteDuration, workerCheckinInterval: FiniteDuration = 5.seconds)

  protected[master] sealed trait HeartBeat
  protected[master] case object Validate extends HeartBeat
  protected[master] case object Cleanup extends HeartBeat

  def props(config: Config): Props = Props(classOf[Master], config)

}
