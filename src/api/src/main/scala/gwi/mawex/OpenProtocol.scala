package gwi.mawex

import scala.util.Try

/**
  *  client => proxy =>
  *                  Master <=> Workers
  *  client <= proxy <=
  */
case class TaskId(id: String, consumerGroup: String)
case class Task(id: TaskId, job: Any)
case class TaskResult(task: Task, result: Either[String, Any])

/** Executor => Worker */
object e2w {
  case class TaskExecuted(result: Try[Any])
}

/** proxy <= master */
object m2p {
  case class TaskAck(taskId: TaskId)
}

/** client <= proxy */
object p2c {
  sealed trait TaskSubmission
  case class Accepted(taskId: TaskId) extends TaskSubmission
  case class Rejected(taskId: TaskId) extends TaskSubmission
}


