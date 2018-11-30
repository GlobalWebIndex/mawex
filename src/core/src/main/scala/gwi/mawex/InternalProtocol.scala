package gwi.mawex

import java.util.UUID

import akka.actor.ActorRef
import gwi.mawex.executor.ExecutorCmd

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

/** SandBox => ExecutorSupervisor */
object s2es {
  case class Start(taskId: TaskId, executorCmd: ExecutorCmd)
  case object Stop
}

/** ExecutorSupervisor => SandBox */
object es2s {
  case object Crashed
  case object TimedOut
}

/** Executor => SandBox */
object e2s {
  case object RegisterExecutor
}
