package gwi.mawex

import scala.collection.breakOut

object State {

  def empty: State = State(pendingTasks = Map.empty, tasksInProgress = Map.empty, doneTaskIds = Set.empty)

  trait TaskDomainEvent
  case class TaskAccepted(task: Task) extends TaskDomainEvent
  case class TaskStarted(taskId: TaskId) extends TaskDomainEvent
  case class TaskCompleted(taskId: TaskId, result: Any) extends TaskDomainEvent
  case class WorkerFailed(taskId: TaskId) extends TaskDomainEvent

}

// TODO do something with State#doneTaskIds, the set will grow indefinitely which is not good for a use case with millions of tasks

case class State private(private val pendingTasks: Map[Task, Long], private val tasksInProgress: Map[TaskId, Task], private val doneTaskIds: Set[TaskId]) {
  import State._

  def getPendingTasks: Set[Task] = pendingTasks.keySet
  def getPendingConsumerGroups: Set[String] = pendingTasks.map(_._1.id.consumerGroup)(breakOut)
  def getAcceptedTasks: Map[TaskId, Long] = pendingTasks.map { case (task, ts) => task.id -> ts }
  def isAccepted(taskId: TaskId): Boolean = pendingTasks.exists(_._1.id == taskId)
  def getTaskInProgress(taskId: TaskId): Option[Task] = tasksInProgress.get(taskId)
  def isDone(taskId: TaskId): Boolean = doneTaskIds.contains(taskId)

  def updated(event: TaskDomainEvent): State = event match {
    case TaskAccepted(task) =>
      copy(pendingTasks = pendingTasks.updated(task, System.currentTimeMillis))

    case TaskStarted(taskId) =>
      val task = pendingTasks.find(_._1.id == taskId).map(_._1).getOrElse(throw new IllegalStateException("Very unexpectedly, accepted task is missing !!!"))
      val rest = pendingTasks.filter(_._1.id != taskId)
      copy(pendingTasks = rest, tasksInProgress = tasksInProgress + (task.id -> task))

    case TaskCompleted(taskId, _) =>
      copy(tasksInProgress = tasksInProgress - taskId, doneTaskIds = doneTaskIds + taskId)

    case WorkerFailed(taskId) =>
      copy(tasksInProgress = tasksInProgress - taskId, doneTaskIds = doneTaskIds + taskId)

  }
}
