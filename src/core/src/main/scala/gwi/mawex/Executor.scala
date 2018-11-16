package gwi.mawex

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import gwi.mawex.k8.K8SandBox

/** Executor itself is delivered by users, Mawex provides only SandBox where Executor runs, see [[gwi.mawex.WorkerCmd]] */
object Executor {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"
}

/** Mawex SandBoxes are like a runtime environment for executors
  * They are resilient, let's escalate errors from underlying executors to Worker that is responsible for Executor failures */
trait SandBox extends Actor with ActorLogging {
  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException ⇒ Stop
    case _: ActorKilledException         ⇒ Stop
    case _: DeathPactException           ⇒ Stop
    case _: Exception                    ⇒ Escalate
  }
}

object SandBox {
  val ActorName = "SandBox"
  def localJvmProps(executorProps: Props): Props = Props(classOf[LocalJvmExecutor], executorProps)
  def forkingProps(executorProps: Props, forkedJvm: ForkedJvm): Props = Props(classOf[ForkingSandBox], executorProps, forkedJvm)
  def k8JobProps(executorProps: Props, forkedJvm: ForkedJvm): Props = Props(classOf[K8SandBox], executorProps, forkedJvm)
}

/** SandBox for local JVM execution */
class LocalJvmExecutor(props: Props) extends SandBox {
  override def receive: Receive = {
    case Task(_, job) =>
     val executor = context.child(Executor.ActorName) getOrElse context.actorOf(props, Executor.ActorName)
      executor.forward(job)
    }
}
