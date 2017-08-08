package gwi.mawex

sealed trait Worker2MasterCommand
sealed trait Master2WorkerCommand

/** Master <=> Worker */
case class WorkerId(id: String, consumerGroup: String, pod: String)

/** Workers => Master */
object w2m {
  case class Register(workerId: WorkerId) extends Worker2MasterCommand
  case class UnRegister(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskRequest(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskFinished(workerId: WorkerId, taskId: TaskId, result: Either[String, Any]) extends Worker2MasterCommand
}
/** Master => Workers */
object m2w {
  case object TaskReady extends Master2WorkerCommand
  case class TaskResultAck(taskId: TaskId) extends Master2WorkerCommand
}
