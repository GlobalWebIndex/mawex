package gwi.mawex

import java.util.UUID

sealed trait Worker2MasterCommand
sealed trait Master2WorkerCommand

/** Master <=> Worker */
case class WorkerId(consumerGroup: String, pod: String, id: String = UUID.randomUUID().toString)

/** Workers => Master */
object w2m {
  case class CheckIn(workerId: WorkerId) extends Worker2MasterCommand
  case class CheckOut(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskRequest(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskFinished(workerId: WorkerId, taskId: TaskId, result: Either[String, Any]) extends Worker2MasterCommand
}
/** Master => Workers */
object m2w {
  case object TaskReady extends Master2WorkerCommand
  case class TaskResultAck(taskId: TaskId) extends Master2WorkerCommand
}
