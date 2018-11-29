package gwi.mawex.executor

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.{Fork, Launcher, TaskId}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext.Implicits
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

trait ExecutorSupervisor extends Actor with ActorLogging {
  def executorConf: ExecutorConf
}

object ExecutorSupervisor {
  case class Start(taskId: TaskId, executorCmd: ExecutorCmd)
  case object Crashed
  case object Stop
  case class Check(sandboxRef: ActorRef)
  case object TimedOut
}

trait ExecutorConf {
  def checkInterval: FiniteDuration
  def checkLimit: Int
}

case class ForkedJvmConf(classPath: String, checkInterval: FiniteDuration, checkLimit: Int) extends ExecutorConf
case class K8Resources(limitsCpu: String, limitsMemory: String, requestsCpu: String, requestsMemory: String)
case class K8JobConf(image: String, namespace: String, k8Resources: K8Resources, debug: Boolean, checkInterval: FiniteDuration, checkLimit: Int, serverApiUrl: String, token: String, caCert: String) extends ExecutorConf

/** starts executor in a forked JVM process **/
class ForkingExecutorSupervisor(val executorConf: ForkedJvmConf) extends ExecutorSupervisor {

  override def receive: Receive = idle(0)

  def idle(attempts: Int): Receive = {
    case ExecutorSupervisor.Start(_, executorCmd) =>
      val sandboxRef = sender()
      Future(
        Fork.run(
          Launcher.getClass.getName.replaceAll("\\$", ""),
          executorConf.classPath,
          executorCmd.jvmOpts,
          executorCmd.commands
        )
      ) onComplete {
        case Success(process) =>
          context.become(running(attempts, process))
          context.system.scheduler.scheduleOnce(10.seconds, self, ExecutorSupervisor.Check(sandboxRef))(Implicits.global)
          log.info(s"Process successfully started ...")
        case Failure(ex) =>
          sandboxRef ! ExecutorSupervisor.Crashed
          log.error(ex,s"Starting process failed !!!")

      }
  }

  def running(attempts: Int, process: Process) : Receive = {
    case ExecutorSupervisor.Check(sandboxRef) =>
      if (!process.isAlive())
        sandboxRef ! ExecutorSupervisor.Crashed
      else if (attempts >= executorConf.checkLimit)
        sandboxRef ! ExecutorSupervisor.TimedOut
      else {
        context.become(running(attempts+1, process))
        context.system.scheduler.scheduleOnce(executorConf.checkInterval, self, ExecutorSupervisor.Check(sandboxRef))(Implicits.global)
      }
    case ExecutorSupervisor.Stop =>
      Future(
        (1 to 3).foldLeft(process.isAlive()) {
          case (acc, counter) if acc =>
            log.info("JVM process is still alive, waiting a second ...")
            Thread.sleep(500)
            if (counter == 3 && process.isAlive()) {
              log.info("JVM process did not die, destroying ...")
              Try(process.destroy())
            }
            process.isAlive()
          case _ =>
            false
        }
      ) andThen { case _ => context.stop(self) }
  }
}

class K8JobExecutorSupervisor(val executorConf: K8JobConf) extends ExecutorSupervisor with K8BatchApiSupport {

  private[this] implicit val batchApi =
    new BatchV1Api(
      Config.fromToken(
        executorConf.serverApiUrl,
        executorConf.token,
      ).setSslCaCert(new ByteArrayInputStream(executorConf.caCert.getBytes()))
        .setDebugging(executorConf.debug)
    )

  override def receive: Receive = idle(0)

  def idle(attempts: Int): Receive = {
    case ExecutorSupervisor.Start(taskId, executorCmd) =>
      val jobName = JobName(taskId)
      val sandboxRef = sender()
      logger.info(s"Starting k8s job $jobName")
      context.become(running(attempts, jobName))
      Future(runJob(jobName, executorConf, executorCmd)) onComplete {
        case Success(job) =>
          logger.info(s"Job $jobName successfully started ${getJobStatusConditions(job)}")
          context.system.scheduler.scheduleOnce(10.seconds, self, ExecutorSupervisor.Check(sandboxRef))(Implicits.global)
        case Failure(ex) =>
          logger.error(s"Starting job $jobName failed !!!", ex)
          sandboxRef ! ExecutorSupervisor.Crashed
      }
  }

  def running(attempts: Int, jobName: JobName): Receive = {
    case ExecutorSupervisor.Check(sandboxRef) =>
      val isRunning = jobExists(jobName, executorConf)
      if (!isRunning)
        sandboxRef ! ExecutorSupervisor.Crashed
      else if (attempts >= executorConf.checkLimit)
        sandboxRef ! ExecutorSupervisor.TimedOut
      else {
        context.become(running(attempts+1, jobName))
        context.system.scheduler.scheduleOnce(executorConf.checkInterval, self, ExecutorSupervisor.Check(sandboxRef))(Implicits.global)
      }
    case ExecutorSupervisor.Stop =>
      logger.info(s"Deleting k8s job $jobName")
      Future(deleteJob(jobName, executorConf)) onComplete {
        case Success(status) =>
          logger.info(s"Job $jobName successfully deleted, status: \n$status")
          context.stop(self)
        case Failure(ex) =>
          logger.warn(s"Deleting job $jobName failed !!!", ex)
          context.stop(self)
      }
  }

}

object K8JobConf extends LazyLogging {
  private val serviceAccountPath  = "/var/run/secrets/kubernetes.io/serviceaccount"
  private val tokenPath           = Paths.get(s"$serviceAccountPath/token")
  private val certPath            = Paths.get(s"$serviceAccountPath/ca.crt")

  private def fail(msg: String) = {
    logger.error(msg)
    throw new IllegalArgumentException(msg)
  }

  def apply(image: String, namespace: String, k8Resources: K8Resources, debug: Boolean, checkInterval: FiniteDuration, checkLimit: Int): K8JobConf = {
    val k8sApiHost = sys.env.getOrElse("KUBERNETES_SERVICE_HOST", fail(s"Env var KUBERNETES_SERVICE_HOST is not available !!!"))
    val k8sApiUrl = s"https://$k8sApiHost"
    val token =
      if (tokenPath.toFile.exists())
        new String(Files.readAllBytes(tokenPath), "UTF-8")
      else
        fail(s"Token file $tokenPath does not exist !!!")

    val cert =
      if (certPath.toFile.exists())
        new String(Files.readAllBytes(certPath), "UTF-8")
      else
        fail(s"Cert file $certPath does not exist !!!")

    K8JobConf(image, namespace, k8Resources, debug, checkInterval, checkLimit, k8sApiUrl, token, cert)
  }
}