package gwi.mawex.master

import gwi.mawex.{Task, TaskId}

protected[master] object State {

  protected[master] def empty: State = State(pendingTasks = Map.empty, progressingTasks = Map.empty, doneTaskIds = Vector.empty)

  protected[master] trait TaskDomainEvent
  protected[master] case class TaskAccepted(task: Task) extends TaskDomainEvent
  protected[master] case class TaskStarted(taskId: TaskId) extends TaskDomainEvent
  protected[master] case class TaskCompleted(taskId: TaskId, result: Any) extends TaskDomainEvent
  protected[master] case class TaskFailed(taskId: TaskId) extends TaskDomainEvent

  implicit class VectorPimp(underlying: Vector[TaskId]) {
    def fifo(e: TaskId, limit: Int): Vector[TaskId] = {
      if (underlying.length < limit)
        underlying :+ e
      else
        underlying.drop(1) :+ e
    }
  }

}

/** Persistent Actor Event Sourced State tracking progress of mawex */
protected[master] case class State private(private val pendingTasks: Map[Task, Long], private val progressingTasks: Map[Task, Long], private val doneTaskIds: Vector[TaskId]) {
  import State._

  private[this] val DoneTasksLimit = 1000

  protected[master] def getPendingTasks: Map[Task, Long] = pendingTasks
  protected[master] def getProgressingTasks: Map[Task, Long] = progressingTasks
  protected[master] def getPendingTaskIds: Map[TaskId, Long] = pendingTasks.map { case (task, ts) => task.id -> ts }
  protected[master] def getProgressingTaskIds: Map[TaskId, Long] = progressingTasks.map { case (task, ts) => task.id -> ts }
  protected[master] def isAccepted(taskId: TaskId): Boolean = pendingTasks.exists(_._1.id == taskId)
  protected[master] def getTaskInProgress(taskId: TaskId): Option[Task] = progressingTasks.keySet.find(_.id == taskId)
  protected[master] def getDoneTaskIds: Vector[TaskId] = doneTaskIds

  protected[master] def getPendingTasksConsumerGroups: Seq[String] =
    pendingTasks.toSeq
      .map { case (task, ts) => task.id.consumerGroup -> ts }
      .groupBy(_._1)
      .mapValues(_.map(_._2).min).toSeq
      .sortBy(_._2)
      .map(_._1)

  protected[master] def updated(event: TaskDomainEvent): State = event match {
    case TaskAccepted(task) =>
      copy(pendingTasks = pendingTasks.updated(task, Master.distinctCurrentMillis))

    case TaskStarted(taskId) =>
      val task = pendingTasks.find(_._1.id == taskId).map(_._1).getOrElse(throw new IllegalStateException("Very unexpectedly, accepted task is missing !!!"))
      val rest = pendingTasks.filter(_._1.id != taskId)
      copy(pendingTasks = rest, progressingTasks = progressingTasks + (task -> Master.distinctCurrentMillis))

    case TaskCompleted(taskId, _) =>
      val task = getTaskInProgress(taskId).getOrElse(throw new IllegalStateException("Very unexpectedly, progressing task is missing to complete !!!"))
      copy(progressingTasks = progressingTasks - task, doneTaskIds = doneTaskIds.fifo(taskId, DoneTasksLimit))

    case TaskFailed(taskId) =>
      val task = getTaskInProgress(taskId).getOrElse(throw new IllegalStateException("Very unexpectedly, progressing task is missing to fail !!!"))
      copy(progressingTasks = progressingTasks - task, doneTaskIds = doneTaskIds.fifo(taskId, DoneTasksLimit))

  }
}
