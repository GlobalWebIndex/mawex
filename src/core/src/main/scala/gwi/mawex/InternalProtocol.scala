package gwi.mawex

sealed trait MasterWorkerLike

/** Master <=> Worker */
case class WorkerId(id: String, consumerGroup: String) extends MasterWorkerLike
/** Workers => Master */
object w2m {
  case class Register(workerId: WorkerId) extends MasterWorkerLike
  case class TaskRequest(workerId: WorkerId) extends MasterWorkerLike
  case class TaskFinished(workerId: WorkerId, taskId: TaskId, result: Either[String, Any]) extends MasterWorkerLike
}
/** Master => Workers */
object m2w {
  case object TaskReady extends MasterWorkerLike
  case class TaskChecked(taskId: TaskId) extends MasterWorkerLike
}
