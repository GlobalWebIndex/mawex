package gwi.mawex

import scala.collection.immutable.Queue

object State {

  def empty: State = State(pendingTasks = Queue.empty, tasksInProgress = Map.empty, acceptedTaskIds = Map.empty, doneTaskIds = Set.empty)

  trait TaskDomainEvent
  case class TaskAccepted(task: Task) extends TaskDomainEvent
  case class TaskStarted(taskId: TaskId) extends TaskDomainEvent
  case class TaskCompleted(taskId: TaskId, result: Any) extends TaskDomainEvent
  case class WorkerFailed(taskId: TaskId) extends TaskDomainEvent
  // case class TaskTimedOut(taskId: TaskId) extends TaskDomainEvent

}

// TODO do something with State#doneTaskIds, the set will grow indefinitely which is not good for a use case with millions of tasks

case class State private(private val pendingTasks: Queue[Task], private val tasksInProgress: Map[TaskId, Task], private val acceptedTaskIds: Map[TaskId, Long], private val doneTaskIds: Set[TaskId]) {
  import State._

  def getPendingTasks: List[Task] = pendingTasks.toList
  def getAcceptedTasks: Map[TaskId, Long] = acceptedTaskIds
  def isAccepted(taskId: TaskId): Boolean = acceptedTaskIds.contains(taskId)
  def getTaskInProgress(taskId: TaskId): Option[Task] = tasksInProgress.get(taskId)
  def isDone(taskId: TaskId): Boolean = doneTaskIds.contains(taskId)

  def updated(event: TaskDomainEvent): State = event match {
    case TaskAccepted(task) =>
      copy(pendingTasks = pendingTasks enqueue task, acceptedTaskIds = acceptedTaskIds.updated(task.id, System.currentTimeMillis))

    case TaskStarted(taskId) =>
      val (task, rest) = pendingTasks.partition(_.id == taskId)
      require(task.size == 1)
      copy(pendingTasks = rest, tasksInProgress = tasksInProgress + (taskId -> task.head))

    case TaskCompleted(taskId, _) =>
      copy(tasksInProgress = tasksInProgress - taskId, acceptedTaskIds = acceptedTaskIds - taskId, doneTaskIds = doneTaskIds + taskId)

    case WorkerFailed(taskId) =>
      copy(tasksInProgress = tasksInProgress - taskId, acceptedTaskIds = acceptedTaskIds - taskId, doneTaskIds = doneTaskIds + taskId)

/*
    case TaskTimedOut(taskId) =>
      val rest = pendingTasks.filter(_.id != taskId)
      copy(pendingTasks = rest, tasksInProgress = tasksInProgress - taskId, acceptedTaskIds = acceptedTaskIds - taskId, doneTaskIds = doneTaskIds + taskId)
*/
  }
}
