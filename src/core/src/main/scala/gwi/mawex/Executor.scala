package gwi.mawex

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import akka.actor._
import io.kubernetes.client.Configuration
import io.kubernetes.client.apis.BatchV1Api
import org.backuity.clist.{CliMain, arg}

import scala.concurrent.{Await, ExecutionContext}
import scala.sys.ShutdownHookThread
import scala.sys.process.Process
import scala.util.Try

trait Executor {
  def props: Props
  def run(serializedExecutorPath: String): Unit
  def onStop(): Unit
}

/** Executor that runs in a forked JVM process **/
case class ForkedJvmConf(classPath: String, opts: String)
case class ForkingExecutor(props: Props, forkedJvm: ForkedJvmConf) extends Executor {

  private[this] var process: Option[Process] = Option.empty

  override def run(serializedActorPath: String): Unit = {
    process =
      Option(
        Fork.run(
          Executor.getClass.getName.replaceAll("\\$", ""),
          forkedJvm.classPath,
          Some(forkedJvm.opts),
          Some(s"--sandbox-actor-path=$serializedActorPath")
        )
      )
  }

  override def onStop(): Unit = {
    (1 to 10).foldLeft(process.exists(_.isAlive())) {
      case (acc, counter) if acc =>
        Thread.sleep(500)
        if (counter == 10) process.foreach(p => Try(p.destroy()))
        process.exists(_.isAlive())
      case _ =>
        false
    }
    process = Option.empty
  }

}

/** Executor that runs in a k8s job **/
case class K8JobConf(jobName: String, image: String, namespace: String, opts: Option[String] = None)
case class K8JobExecutor(props: Props, k8JobConf: K8JobConf) extends Executor with K8BatchApiSupport {

  private[this] implicit val batchApi = new BatchV1Api(Configuration.getDefaultApiClient)

  override def run(serializedActorPath: String): Unit = runJob(k8JobConf, s"--sandbox-actor-path=$serializedActorPath")

  override def onStop(): Unit = deleteJob(k8JobConf)
}

object Executor extends CliMain[Unit]() {
  val ActorName   = "Executor"
  val SystemName  = "ExecutorSystem"

  class SandboxFrontDesk(sandbox: ActorSelection) extends Actor {
    override def preStart(): Unit = sandbox ! e2s.RegisterExecutor
    override def receive: Receive = {
      case s2e.TerminateExecutor => context.system.terminate()
    }
  }

  var sandboxActorPath = arg[String](name="sandbox-actor-path", description = "Serialization.serializedActorPath")

  private val systemTerminated = new AtomicBoolean(false)

  private def startAndRegisterExecutorToSandBox: ShutdownHookThread = {
    val executorSystem = RemoteService.buildRemoteSystem(Address("akka.tcp", Executor.SystemName, AddressFromURIString(sandboxActorPath).host.get, 0))
    executorSystem.actorOf(Props(classOf[SandboxFrontDesk], executorSystem.actorSelection(sandboxActorPath)))
    executorSystem.whenTerminated.onComplete { _ =>
      systemTerminated.set(true)
      System.exit(0)
    }(ExecutionContext.Implicits.global)
    sys.addShutdownHook(if (!systemTerminated.get) Await.result(executorSystem.terminate(), 10.seconds))
  }

  override def run: Unit = startAndRegisterExecutorToSandBox
}
