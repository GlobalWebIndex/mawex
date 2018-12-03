package gwi.mawex.executor

import java.lang

import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.TaskId
import io.fabric8.kubernetes.api.model.batch.{Job, JobBuilder}
import io.fabric8.kubernetes.api.model.{ContainerBuilder, EnvVar, EnvVarBuilder}
import io.fabric8.kubernetes.client.BatchAPIGroupClient
import io.kubernetes.client.ApiException
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.models._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}
import akka.pattern.after
import akka.actor.Scheduler

import scala.concurrent.duration.FiniteDuration

case class JobName(name: String)
object JobName {
  def apply(taskId: TaskId): JobName = JobName(taskId.id)
}

trait K8BatchApiSupport extends LazyLogging {

  private[this] def recover[T](in: FiniteDuration, attempts: Int)(f: => Future[T])(implicit ec: ExecutionContext, s: Scheduler): Future[T] =
    f recoverWith {
      case ex: ApiException if attempts > 1 =>
        logger.warn(s"Unable to recover from k8s API error with code ${ex.getCode} ... trying $attempts times\n${ex.getResponseBody}")
        after(in, s)(recover(in, attempts - 1)(f))
    }

  protected[mawex] def getJobStatusConditions(job: V1Job): String =
    Option(job.getStatus.getConditions).map(_.asScala.map( c => s"${c.getType} ${c.getStatus}").mkString("\n","\n","\n")).getOrElse("")

  protected[mawex] def runJob(jobName: JobName, conf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchV1Api, ioEC: ExecutionContext): Future[V1Job] = {
    val envVarsMap = sys.env ++ executorCmd.jvmOpts.map(opt => Map("JAVA_TOOL_OPTIONS" -> opt) ).getOrElse(Map.empty)
    val envVars =
      envVarsMap.foldLeft(List.empty[V1EnvVar]) { case (acc, (k,v)) =>
        new V1EnvVarBuilder().withName(k).withValue(v).build() :: acc
      }.asJava

    val containerName = conf.image.trim.split('/').last.split(':').head

    val container =
      new V1ContainerBuilder(true)
        .withName(containerName)
        .withImage(conf.image)
        .withEnv(envVars)
        .withArgs(executorCmd.commands.asJava)
        .withNewResources()
          .addToLimits("memory", new Quantity(conf.k8Resources.limitsMemory))
          .addToLimits("cpu", new Quantity(conf.k8Resources.limitsCpu))
          .addToRequests("memory", new Quantity(conf.k8Resources.requestsMemory))
          .addToRequests("cpu", new Quantity(conf.k8Resources.requestsCpu))
        .endResources()
        .build

    val job =
      new V1JobBuilder(true)
        .withNewMetadata.withName(jobName.name)
        .withNamespace(conf.namespace).and
        .withNewSpec()
          .withActiveDeadlineSeconds(3600L)
          .withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").and.and.and
        .build

    logger.debug(s"Creating job ${jobName.name} with container name $containerName")
    Future(batchApi.createNamespacedJob(conf.namespace, job, "true"))
      .andThen {
        case Failure(ex: ApiException) =>
          logger.error(s"Creating job failed with status code : ${ex.getCode} and response :\n ${ex.getResponseBody}", ex)
        case Failure(NonFatal(ex)) =>
          logger.error(s"Creating job failed !!!", ex)
      }
  }

  protected[mawex] def jobExists(jobName: JobName, conf: K8JobConf)(implicit batchApi: BatchV1Api, ioEC: ExecutionContext, s: Scheduler): Future[Boolean] =
    recover(5.seconds, 3)(Future(batchApi.readNamespacedJobStatusWithHttpInfo(jobName.name, conf.namespace, "true")))
      .transformWith {
        case Failure(ex: ApiException) =>
          logger.error(s"Checking job exists failed with status code : ${ex.getCode} and response :\n ${ex.getResponseBody}", ex)
          Future.successful(false)
        case Failure(ex) =>
          logger.error(s"Checking job exists failed !!!", ex)
          Future.successful(false)
        case Success(response) =>
          val jobExists = response.getStatusCode != 404
          logger.debug(s"Job exists : $jobExists ${getJobStatusConditions(response.getData)}")
          Future.successful(jobExists)
      }

  protected[mawex] def deleteJob(jobName: JobName, k8JobConf: K8JobConf)(implicit batchApi: BatchV1Api, ioEC: ExecutionContext, s: Scheduler): Future[V1Status] = {
    val opts = new V1DeleteOptionsBuilder().withPropagationPolicy("Background").build()
    recover(5.seconds, 3)(Future(
      batchApi.deleteNamespacedJob(jobName.name, k8JobConf.namespace, opts, "false", 5, false, "Background")
    )).andThen {
      case Failure(ex: ApiException) =>
        logger.error(s"Checking job exists failed with status code : ${ex.getCode} and response :\n ${ex.getResponseBody}", ex)
      case Failure(ex) =>
        logger.error(s"Checking job exists failed !!!", ex)
    }
  }

}

@Deprecated
trait Fabric8BatchApiSupport {

  protected[mawex] def runJob(jobName: JobName, conf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchAPIGroupClient): Job = {
    import io.fabric8.kubernetes.api.model.Quantity
    val envVarsMap = sys.env ++ executorCmd.jvmOpts.map(opt => Map("JAVA_TOOL_OPTIONS" -> opt) ).getOrElse(Map.empty)
    val envVars =
      envVarsMap.foldLeft(List.empty[EnvVar]) { case (acc, (k,v)) =>
        new EnvVarBuilder().withName(k).withValue(v).build() :: acc
      }.asJava

    val containerName = conf.image.trim.split('/').last.split(':').head

    val container =
      new ContainerBuilder(true)
        .withName(containerName)
        .withImage(conf.image)
        .withEnv(envVars)
        .withCommand(executorCmd.commands.asJava)
        .withNewResources()
          .addToLimits("memory", new Quantity(conf.k8Resources.limitsMemory))
          .addToLimits("cpu", new Quantity(conf.k8Resources.limitsCpu))
          .addToRequests("memory", new Quantity(conf.k8Resources.requestsMemory))
          .addToRequests("cpu", new Quantity(conf.k8Resources.requestsCpu))
        .endResources()
        .build

    batchApi.jobs.create(
      new JobBuilder(true)
        .withNewMetadata.withName(jobName.name).withNamespace(conf.namespace).endMetadata()
        .withNewSpec()
          .withActiveDeadlineSeconds(3600L)
          .withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").endSpec().endTemplate().endSpec()
        .build
    )

  }

  protected[mawex] def deleteJob(jobName: JobName, k8JobConf: K8JobConf)(implicit batchApi: BatchAPIGroupClient): lang.Boolean = {
    batchApi.jobs().withName(jobName.name).delete()
  }

}
