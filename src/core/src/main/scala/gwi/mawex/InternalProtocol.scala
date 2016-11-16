package gwi.mawex

import gwi.mawex.OpenProtocol.TaskId

import scala.util.Try

object InternalProtocol {

  /** Master <=> Worker */
  case class WorkerId(id: String, consumerGroup: String)

  /** Workers => Master */
  object w2m {
    case class Register(workerId: WorkerId)
    case class TaskRequest(workerId: WorkerId)
    case class TaskFinished(workerId: WorkerId, taskId: TaskId, result: Try[Any])
  }

  /** Master => Workers */
  object m2w {
    case object TaskReady
    case class TaskChecked(taskId: TaskId)
  }

}