package gwi.mawex.executor

import akka.actor._
import gwi.mawex.{Fork, Launcher}
import io.kubernetes.client.Configuration
import io.kubernetes.client.apis.BatchV1Api

import scala.sys.process.Process
import scala.util.Try

trait Executor {
  def props: Props
  def conf: ExecutorConf
  def run(serializedExecutorPath: String): Unit
  def onStop(): Unit
}

trait ExecutorConf

/** Executor that runs in a forked JVM process **/
case class ForkedJvmConf(classPath: String, commands: List[String], opts: Option[String] = None) extends ExecutorConf
case class ForkingExecutor(props: Props, conf: ForkedJvmConf) extends Executor {

  private[this] var process: Option[Process] = Option.empty

  override def run(serializedActorPath: String): Unit = {
    process =
      Option(
        Fork.run(
          Launcher.getClass.getName.replaceAll("\\$", ""),
          conf.classPath,
          conf.opts,
          conf.commands :+ s"--sandbox-actor-path=$serializedActorPath"
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
case class K8JobConf(jobName: String, image: String, namespace: String, commands: List[String], opts: Option[String] = None) extends ExecutorConf
case class K8JobExecutor(props: Props, conf: K8JobConf) extends Executor with K8BatchApiSupport {

  private[this] implicit val batchApi = new BatchV1Api(Configuration.getDefaultApiClient)

  override def run(serializedActorPath: String): Unit = runJob(conf.copy(commands = conf.commands :+ s"--sandbox-actor-path=$serializedActorPath"))

  override def onStop(): Unit = deleteJob(conf)
}
