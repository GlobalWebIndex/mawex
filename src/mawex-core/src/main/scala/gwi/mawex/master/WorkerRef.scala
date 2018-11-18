package gwi.mawex.master

import akka.Done
import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter
import gwi.mawex._

import collection.{breakOut, mutable}

protected[master] sealed trait WorkerStatus
protected[master] case object Idle extends WorkerStatus
protected[master] case class Busy(taskId: TaskId) extends WorkerStatus
protected[master] case class WorkerRef(ref: ActorRef, status: WorkerStatus, registrationTime: Long, lastTaskSubmissionTime: Option[Long])
protected[master] case object GetMawexState
protected[master] case class MawexState(workState: State, workersById: Map[WorkerId, WorkerRef])

protected[master] object WorkerRef {

  implicit class WorkerRefPimp(underlying: mutable.Map[WorkerId, WorkerRef]) {

    private[this] def adjust(k: WorkerId)(f: Option[WorkerRef] => WorkerRef): mutable.Map[WorkerId, WorkerRef] =
      underlying += (k -> f(underlying.get(k)))

    private[this] def changeWorkerRef(workerId: WorkerId, newRef: ActorRef): mutable.Map[WorkerId, WorkerRef] =
      adjust(workerId)(_.get.copy(ref = newRef, registrationTime = System.currentTimeMillis()))

    private[this] def checkInNew(workerId: WorkerId, ref: ActorRef): mutable.Map[WorkerId, WorkerRef] =
      underlying += (workerId -> WorkerRef(ref, status = Idle, System.currentTimeMillis(), None))

    private[this] def checkInOld(workerId: WorkerId): mutable.Map[WorkerId, WorkerRef] =
      adjust(workerId)(_.get.copy(registrationTime = System.currentTimeMillis()))

    def getBusyWorkers(pod: String): Set[WorkerId] = underlying.collect { case (id@WorkerId(_, wPod, _), WorkerRef(_, Busy(_), _, _)) if wPod == pod => id }.toSet

    def employ(workerId: WorkerId, taskId: TaskId): mutable.Map[WorkerId, WorkerRef] = {
      adjust(workerId)(_.get.copy(status = Busy(taskId), lastTaskSubmissionTime = Some(Master.distinctCurrentMillis)))
    }

    def idle(workerId: WorkerId, taskId: TaskId)(implicit log: LoggingAdapter): Unit =
      underlying.get(workerId) match {
        case Some(s @ WorkerRef(_, Busy(busyTaskId), _, _)) if taskId == busyTaskId =>
          underlying += (workerId -> s.copy(status = Idle))
        case _ =>
          log.warning("Worker {} state probably not persisted after recovery, workId {} missing ...", workerId, taskId)
      }

    def getWorkerIdByRef(ref: ActorRef): Option[WorkerId] =
      underlying
        .collectFirst { case (workerId, status) if status.ref == ref => workerId }

    def findProgressingOrphanTask(workState: State): Map[TaskId, Task] =
      workState.getProgressingTasks.keySet
        .collect { case task if !underlying.exists(_._2.status == Busy(task.id)) =>
          task.id -> task
        }(breakOut)

    def validate(workState: State, conf: Master.Config)(implicit log: LoggingAdapter): Unit = {
      for ((workerId, WorkerRef(_, _, registrationTime, _)) <- underlying if (System.currentTimeMillis() - registrationTime) > conf.workerCheckinInterval.toMillis * 6) {
        log.warning(s"worker $workerId has not checked in, context.watch doesn't work !!!")
      }
      for ((taskId, creationTime) <- workState.getProgressingTaskIds if (System.currentTimeMillis() - creationTime) > conf.progressingTaskTimeout.toMillis) {
        log.warning("Progressing task {} timed out, worker has not replied !!!", taskId)
      }
      for ((taskId, creationTime) <- workState.getPendingTaskIds if (System.currentTimeMillis() - creationTime) > conf.pendingTaskTimeout.toMillis) {
        log.warning("Pending task {} timed out, no worker asked for it !!!", taskId)
      }
    }

    private[this] def getIdleWorkerRefs(consumerGroup: String): Seq[(WorkerId, WorkerRef)] =
      underlying.toSeq
        .collect { case t@(WorkerId(wGroup, wPod, _), WorkerRef(_, Idle, _, _)) if wGroup == consumerGroup && getBusyWorkers(wPod).isEmpty => t }(breakOut)
        .sortBy( worker => worker._2.lastTaskSubmissionTime.getOrElse(worker._2.registrationTime) )

    def offerTask(consumerGroup: String): Unit =
      getIdleWorkerRefs(consumerGroup).foreach { case (_, workerRef) => workerRef.ref ! m2w.TaskReady }

    def checkOut(workerId: WorkerId, context: ActorContext)(implicit log: LoggingAdapter): Option[TaskId] = {
      val workerStatusOpt = underlying.get(workerId)
      val taskOpt =
        workerStatusOpt
          .collect {
            case WorkerRef(_, Busy(taskId), _, _) =>
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
