package gwi.mawex.executor

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.{Fork, Launcher}
import io.kubernetes.client.Configuration
import io.kubernetes.client.apis.BatchV1Api

import scala.sys.process.Process
import scala.util.Try

trait RemoteController {
  def executorProps: Props
  def executorConf: ExecutorConf
  def start(executorCmd: ExecutorCmd): Unit
  def onStop(): Unit
}

trait ExecutorConf

/** Controller starts executor in a forked JVM process **/
case class ForkedJvmConf(classPath: String) extends ExecutorConf
case class ForkingController(executorProps: Props, executorConf: ForkedJvmConf) extends RemoteController with LazyLogging {

  private[this] var process: Option[Process] = Option.empty

  override def start(executorCmd: ExecutorCmd): Unit = {
    process =
      Option(
        Fork.run(
          Launcher.getClass.getName.replaceAll("\\$", ""),
          executorConf.classPath,
          executorCmd.jvmOpts,
          executorCmd.commands
        )
      )
  }

  override def onStop(): Unit = {
    (1 to 3).foldLeft(process.exists(_.isAlive())) {
      case (acc, counter) if acc =>
        logger.info("JVM process is still alive, waiting a second ...")
        Thread.sleep(1000)
        val stillAlive = process.exists(_.isAlive())
        if (counter == 3 && stillAlive) {
          logger.info("JVM process did not die, destroying ...")
          process.foreach(p => Try(p.destroy()))
        }
        stillAlive
      case _ =>
        false
    }
    process = Option.empty
  }

}

/** Controller starts executor in a k8s job **/
case class K8JobConf(jobName: String, image: String, namespace: String) extends ExecutorConf
case class K8JobController(executorProps: Props, executorConf: K8JobConf) extends RemoteController with K8BatchApiSupport {

  private[this] implicit val batchApi = new BatchV1Api(Configuration.getDefaultApiClient)

  override def start(executorCmd: ExecutorCmd): Unit =
    runJob(executorConf, executorCmd)

  override def onStop(): Unit = deleteJob(executorConf)
}
