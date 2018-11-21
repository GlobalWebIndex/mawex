package gwi.mawex.executor

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}

import akka.actor._
import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.{Fork, Launcher}
import io.kubernetes.client.util.Config
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

  override def onStop(): Unit = Try {
    (1 to 3).foldLeft(process.exists(_.isAlive())) {
      case (acc, counter) if acc =>
        logger.info("JVM process is still alive, waiting a second ...")
        Thread.sleep(500)
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
case class K8JobConf(jobName: String, image: String, namespace: String, serverApiUrl: String, token: String, caCert: String) extends ExecutorConf
case class K8JobController(executorProps: Props, executorConf: K8JobConf) extends RemoteController with K8BatchApiSupport {

  private[this] implicit val batchApi =
    new BatchV1Api(
      Config.fromToken(
        executorConf.serverApiUrl,
        executorConf.token,
      ).setSslCaCert(new ByteArrayInputStream(executorConf.caCert.getBytes()))
    )

  override def start(executorCmd: ExecutorCmd): Unit =
    runJob(executorConf, executorCmd)

  override def onStop(): Unit = Try(deleteJob(executorConf))
}

object K8JobConf {
  private val serviceAccountPath  = "/var/run/secrets/kubernetes.io/serviceaccount"
  private val tokenPath           = Paths.get(s"$serviceAccountPath/token")
  private val certPath            = Paths.get(s"$serviceAccountPath/ca.crt")

  private def fail(msg: String) = throw new IllegalArgumentException(msg)
  def apply(jobName: String, image: String, namespace: String): K8JobConf = {
    val k8sApiHost = sys.env.getOrElse("KUBERNETES_SERVICE_HOST", fail(s"Env var KUBERNETES_SERVICE_HOST is not available !!!"))
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

    K8JobConf(jobName, image, namespace, k8sApiHost, token, cert)
  }
}