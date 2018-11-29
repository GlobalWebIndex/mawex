package gwi.mawex

import java.util.UUID

import akka.actor.ActorRef

/** Master <=> Worker */
case class WorkerId(consumerGroup: String, pod: String, id: String = UUID.randomUUID().toString)

/** Workers => Master */
protected[mawex] object w2m {
  sealed trait Worker2MasterCommand
  case class CheckIn(workerId: WorkerId) extends Worker2MasterCommand
  case class CheckOut(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskRequest(workerId: WorkerId) extends Worker2MasterCommand
  case class TaskFinished(workerId: WorkerId, taskId: TaskId, result: Either[String, Any]) extends Worker2MasterCommand
}
/** Master => Workers */
protected[mawex] object m2w {
  sealed trait Master2WorkerCommand
  case object TaskReady extends Master2WorkerCommand
  case class TaskResultAck(taskId: TaskId) extends Master2WorkerCommand
}

/** SandBox => Executor */
object s2e {
  case object TerminateExecutor
  case class RegisterExecutorAck(executorRef: ActorRef)
}

/** Executor => SandBox */
object e2s {
  case object RegisterExecutor
}
