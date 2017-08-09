package gwi.mawex

import akka.Done
import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

protected[mawex] sealed trait WorkerStatus
protected[mawex] case object Idle extends WorkerStatus
protected[mawex] case class Busy(taskId: TaskId) extends WorkerStatus
protected[mawex] case class WorkerRef(ref: ActorRef, status: WorkerStatus, registrationTime: Long)

protected[mawex] object WorkerRef {

  implicit class WorkerRefPimp(underlying: mutable.Map[WorkerId, WorkerRef]) {

    private[this] def adjust(k: WorkerId)(f: Option[WorkerRef] => WorkerRef): mutable.Map[WorkerId, WorkerRef] =
      underlying += (k -> f(underlying.get(k)))

    private[this] def changeWorkerRef(workerId: WorkerId, newRef: ActorRef): mutable.Map[WorkerId, WorkerRef] =
      adjust(workerId)(_.get.copy(ref = newRef, registrationTime = System.currentTimeMillis()))

    private[this] def checkInNew(workerId: WorkerId, ref: ActorRef): mutable.Map[WorkerId, WorkerRef] =
      underlying += (workerId -> WorkerRef(ref, status = Idle, System.currentTimeMillis()))

    private[this] def checkInOld(workerId: WorkerId): mutable.Map[WorkerId, WorkerRef] =
      adjust(workerId)(_.get.copy(registrationTime = System.currentTimeMillis()))

    def getBusyWorkers(pod: String): Set[WorkerId] = underlying.collect { case (id@WorkerId(_, wPod, _), WorkerRef(_, Busy(_), _)) if wPod == pod => id }.toSet

    def employ(workerId: WorkerId, taskId: TaskId): mutable.Map[WorkerId, WorkerRef] =
      adjust(workerId)(_.get.copy(status = Busy(taskId)))

    def idle(workerId: WorkerId, taskId: TaskId)(implicit log: LoggingAdapter): Unit =
      underlying.get(workerId) match {
        case Some(s @ WorkerRef(_, Busy(busyTaskId), _)) if taskId == busyTaskId =>
          underlying += (workerId -> s.copy(status = Idle))
        case _ =>
          log.warning("Worker {} state probably not persisted after recovery, workId {} missing ...", workerId, taskId)
      }

    def getWorkerIdByRef(ref: ActorRef): Option[WorkerId] =
      underlying
        .collectFirst { case (workerId, status) if status.ref == ref => workerId }

    def findProgressingOrphanTask(workState: State): Set[Task] =
      workState.getProgressingTasks.keySet
        .filter ( task => !underlying.exists(_._2.status == Busy(task.id)) )

    def validate(workState: State, workerCheckinInterval: FiniteDuration, taskTimeout: FiniteDuration)(implicit log: LoggingAdapter): Unit = {
      for ((workerId, WorkerRef(_, _, registrationTime)) <- underlying if (System.currentTimeMillis() - registrationTime) > workerCheckinInterval.toMillis * 6) {
        log.warning(s"worker $workerId has not checked in, context.watch doesn't work !!!")
      }
      for ((taskId, creationTime) <- workState.getProgressingTaskIds if (System.currentTimeMillis() - creationTime) > taskTimeout.toMillis) {
        log.warning("Progressing task {} timed out, worker has not replied !!!", taskId)
      }
      for ((taskId, creationTime) <- workState.getPendingTaskIds if (System.currentTimeMillis() - creationTime) > taskTimeout.toMillis) {
        log.warning("Pending task {} timed out, no worker asked for it !!!", taskId)
      }
    }

    def getIdleWorkerRef(consumerGroup: String): Option[ActorRef] =
      underlying.collectFirst { case (WorkerId(wGroup, wPod, _), WorkerRef(ref, Idle, _)) if wGroup == consumerGroup && getBusyWorkers(wPod).isEmpty => ref }

    def checkOut(workerId: WorkerId, context: ActorContext)(implicit log: LoggingAdapter): Option[TaskId] = {
      val workerStatusOpt = underlying.get(workerId)
      val taskOpt =
        workerStatusOpt
          .collect {
            case WorkerRef(_, Busy(taskId), _) =>
              log.warning(s"Worker $workerId terminated while executing task $taskId, it will be considered failed ...")
              taskId
          }
      workerStatusOpt.foreach( status => context.unwatch(status.ref) )
      underlying -= workerId
      log.info(s"Worker $workerId terminated ...")
      taskOpt
    }

    def checkIn(workerId: WorkerId, sender: ActorRef, context: ActorContext)(implicit log: LoggingAdapter): Option[Done] = {
      val workerStatusOpt = underlying.get(workerId)
      if (workerStatusOpt.exists(_.ref != sender)) { // check for worker's ref change
        val oldWorkerStatus = workerStatusOpt.get
        context.watch(sender)
        context.unwatch(oldWorkerStatus.ref)
        changeWorkerRef(workerId, sender)
        log.warning("Existing worker {} checked in again with different ActorRef !", workerId)
        Some(Done.getInstance())
      } else if (workerStatusOpt.isEmpty) {
        log.info("Worker checked in: {}", workerId)
        context.watch(sender)
        checkInNew(workerId, sender)
        Some(Done.getInstance())
      } else {
        checkInOld(workerId)
        None
      }
    }
  }

}
