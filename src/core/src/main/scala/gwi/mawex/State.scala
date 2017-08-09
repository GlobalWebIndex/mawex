package gwi.mawex

object State {

  def empty: State = State(pendingTasks = Map.empty, progressingTasks = Map.empty, doneTaskIds = Vector.empty)

  trait TaskDomainEvent
  case class TaskAccepted(task: Task) extends TaskDomainEvent
  case class TaskStarted(taskId: TaskId) extends TaskDomainEvent
  case class TaskCompleted(taskId: TaskId, result: Any) extends TaskDomainEvent
  case class TaskFailed(taskId: TaskId) extends TaskDomainEvent

  implicit class VectorPimp(underlying: Vector[TaskId]) {
    def fifo(e: TaskId, limit: Int): Vector[TaskId] = {
      if (underlying.length < limit)
        underlying :+ e
      else
        underlying.drop(1) :+ e
    }
  }

}

case class State private(private val pendingTasks: Map[Task, Long], private val progressingTasks: Map[Task, Long], private val doneTaskIds: Vector[TaskId]) {
  import State._

  private[this] val DoneTasksLimit = 1000

  def getPendingTasks: Map[Task, Long] = pendingTasks
  def getProgressingTasks: Map[Task, Long] = progressingTasks
  def getPendingTaskIds: Map[TaskId, Long] = pendingTasks.map { case (task, ts) => task.id -> ts }
  def getProgressingTaskIds: Map[TaskId, Long] = progressingTasks.map { case (task, ts) => task.id -> ts }
  def isAccepted(taskId: TaskId): Boolean = pendingTasks.exists(_._1.id == taskId)
  def getTaskInProgress(taskId: TaskId): Option[Task] = progressingTasks.keySet.find(_.id == taskId)
  def getDoneTaskIds: Vector[TaskId] = doneTaskIds

  def getPendingTasksConsumerGroups: Seq[String] =
    pendingTasks.toSeq
      .map { case (task, ts) => task.id.consumerGroup -> ts }
      .groupBy(_._1)
      .mapValues(_.map(_._2).min).toSeq
      .sortBy(_._2)
      .map(_._1)

  def updated(event: TaskDomainEvent): State = event match {
    case TaskAccepted(task) =>
      copy(pendingTasks = pendingTasks.updated(task, System.currentTimeMillis))

    case TaskStarted(taskId) =>
      val task = pendingTasks.find(_._1.id == taskId).map(_._1).getOrElse(throw new IllegalStateException("Very unexpectedly, accepted task is missing !!!"))
      val rest = pendingTasks.filter(_._1.id != taskId)
      copy(pendingTasks = rest, progressingTasks = progressingTasks + (task -> System.currentTimeMillis()))

    case TaskCompleted(taskId, _) =>
      val task = getTaskInProgress(taskId).getOrElse(throw new IllegalStateException("Very unexpectedly, progressing task is missing to complete !!!"))
      copy(progressingTasks = progressingTasks - task, doneTaskIds = doneTaskIds.fifo(taskId, DoneTasksLimit))

    case TaskFailed(taskId) =>
      val task = getTaskInProgress(taskId).getOrElse(throw new IllegalStateException("Very unexpectedly, progressing task is missing to fail !!!"))
      copy(progressingTasks = progressingTasks - task, doneTaskIds = doneTaskIds.fifo(taskId, DoneTasksLimit))

  }
}
