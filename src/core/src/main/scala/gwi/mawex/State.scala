package gwi.mawex

import gwi.mawex.OpenProtocol.{Task, TaskId}

import scala.collection.immutable.Queue

object State {

  def empty: State = State(pendingTasks = Queue.empty, tasksInProgress = Map.empty, acceptedTaskIds = Set.empty, doneTaskIds = Set.empty)

  trait TaskDomainEvent
  case class TaskAccepted(task: Task) extends TaskDomainEvent
  case class TaskStarted(taskId: TaskId) extends TaskDomainEvent
  case class TaskCompleted(taskId: TaskId, result: Any) extends TaskDomainEvent
  case class WorkerFailed(taskId: TaskId) extends TaskDomainEvent

}

case class State private(private val pendingTasks: Queue[Task], private val tasksInProgress: Map[TaskId, Task], private val acceptedTaskIds: Set[TaskId], private val doneTaskIds: Set[TaskId]) {
  import State._

  def getPendingTasks: List[Task] = pendingTasks.toList
  def isAccepted(taskId: TaskId): Boolean = acceptedTaskIds.contains(taskId)
  def getTaskInProgress(taskId: TaskId): Option[Task] = tasksInProgress.get(taskId)
  def isDone(taskId: TaskId): Boolean = doneTaskIds.contains(taskId)

  def updated(event: TaskDomainEvent): State = event match {
    case TaskAccepted(task) =>
      copy(pendingTasks = pendingTasks enqueue task, acceptedTaskIds = acceptedTaskIds + task.id)

    case TaskStarted(taskId) =>
      val (work, rest) = pendingTasks.partition(_.id == taskId)
      require(work.size == 1)
      copy(pendingTasks = rest, tasksInProgress = tasksInProgress + (taskId -> work.head))

    case TaskCompleted(taskId, result) =>
      copy(tasksInProgress = tasksInProgress - taskId, acceptedTaskIds = acceptedTaskIds - taskId, doneTaskIds = doneTaskIds + taskId)

    case WorkerFailed(taskId) =>
      copy(tasksInProgress = tasksInProgress - taskId, acceptedTaskIds = acceptedTaskIds - taskId, doneTaskIds = doneTaskIds + taskId)

  }
}
