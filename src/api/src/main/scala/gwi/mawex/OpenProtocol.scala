package gwi.mawex

import com.typesafe.config.Config
import org.backuity.clist.Command

/**
  *  client => proxy =>
  *                  Master <=> Workers <=> Executor
  *  client <= proxy <=
  */
case class TaskId(id: String, consumerGroup: String)
case class Task(id: TaskId, job: Any)
case class TaskResult(taskId: TaskId, result: Either[String, Any])

/** proxy <= master */
object m2p {
  case class TaskAck(taskId: TaskId)
  case class TaskScheduled(taskId: TaskId)
}

/** client <= proxy */
object p2c {
  sealed trait TaskSubmission
  case class Accepted(taskId: TaskId) extends TaskSubmission
  case class Rejected(taskId: TaskId) extends TaskSubmission
}

/**
  * Its purpose is to collect user arguments and build a serializable MawexCommand
  * It exists merely because of the fact that MawexCommand must be serializable case class
  */
abstract class MawexCommandBuilder[C <: MawexCommand] extends Command(name = "command") {
  def build(config: Config): C
}

/**
  * MawexCommand is a marker trait for commands passed to executor for execution.
  * Executor class is provided by user, see [[gwi.mawex.worker.WorkerCmd]]
  * @note MawexCommand implementations must be case classes that do not hold any state as Command is serialized
  *       because it is passed to a remote actor (instantiated in a forked jvm process) as an argument
  */
trait MawexCommand extends Serializable