package gwi.mawex

import scala.util.Try

sealed trait TaskLike

/**
  *  client => proxy =>
  *                  Master <=> Workers
  *  client <= proxy <=
  */
case class TaskId(id: String, consumerGroup: String) extends TaskLike
case class Task(id: TaskId, job: Any) extends TaskLike
case class TaskResult(task: Task, result: Either[String, Any]) extends TaskLike

/** Executor => Worker */
object e2w {
  case class TaskExecuted(result: Try[Any]) extends TaskLike
}

/** proxy <= master */
object m2p {
  case class TaskAck(taskId: TaskId) extends TaskLike
}

/** client <= proxy */
object p2c {
  case class Accepted(taskId: TaskId) extends TaskLike
  case class Rejected(taskId: TaskId) extends TaskLike
}


