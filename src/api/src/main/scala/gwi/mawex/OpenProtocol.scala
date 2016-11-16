package gwi.mawex

import scala.util.Try

object OpenProtocol {
  /**
    *  client => proxy =>
    *                  Master <=> Workers
    *  client <= proxy <=
    */
  case class TaskId(id: String, consumerGroup: String)
  case class Task(id: TaskId, job: Any)
  case class TaskResult(task: Task, result: Try[Any])

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
    case class Accepted(taskId: TaskId)
    case class Rejected(taskId: TaskId)
  }

}

