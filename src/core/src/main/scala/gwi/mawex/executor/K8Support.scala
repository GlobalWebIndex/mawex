package gwi.mawex.executor

import java.lang

import gwi.mawex.TaskId
import io.fabric8.kubernetes.api.model.batch.{Job, JobBuilder}
import io.fabric8.kubernetes.api.model.{ContainerBuilder, EnvVar, EnvVarBuilder}
import io.fabric8.kubernetes.client.BatchAPIGroupClient
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.models._

import scala.collection.JavaConverters._

case class JobName(name: String)
object JobName {
  def apply(taskId: TaskId): JobName = JobName(taskId.id)
}

trait K8BatchApiSupport {

  protected[mawex] def runJob(jobName: JobName, conf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchV1Api): V1Job = {
    val envVarsMap = sys.env ++ executorCmd.jvmOpts.map(opt => Map("JAVA_TOOL_OPTIONS" -> opt) ).getOrElse(Map.empty)
    val envVars =
      envVarsMap.foldLeft(List.empty[V1EnvVar]) { case (acc, (k,v)) =>
        new V1EnvVarBuilder().withName(k).withValue(v).build() :: acc
      }.asJava

    val container =
      new V1ContainerBuilder(true)
        .withName("mawex-job")
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
        .withNewSpec().withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").and.and.and
        .build

    batchApi.createNamespacedJob(conf.namespace, job, "true")
  }

  protected[mawex] def deleteJob(jobName: JobName, k8JobConf: K8JobConf)(implicit batchApi: BatchV1Api): V1Status = {
    val opts = new V1DeleteOptionsBuilder().withPropagationPolicy("Background").build()
    batchApi.deleteNamespacedJob(jobName.name, k8JobConf.namespace, opts, "false", 5, false, "Background")
  }

}

trait Fabric8BatchApiSupport {

  protected[mawex] def runJob(jobName: JobName, conf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchAPIGroupClient): Job = {
    import io.fabric8.kubernetes.api.model.Quantity
    val envVarsMap = sys.env ++ executorCmd.jvmOpts.map(opt => Map("JAVA_TOOL_OPTIONS" -> opt) ).getOrElse(Map.empty)
    val envVars =
      envVarsMap.foldLeft(List.empty[EnvVar]) { case (acc, (k,v)) =>
        new EnvVarBuilder().withName(k).withValue(v).build() :: acc
      }.asJava

    val container =
      new ContainerBuilder(true)
        .withName("mawex-job")
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
        .withNewSpec().withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").endSpec().endTemplate().endSpec()
        .build
    )

  }

  protected[mawex] def deleteJob(jobName: JobName, k8JobConf: K8JobConf)(implicit batchApi: BatchAPIGroupClient): lang.Boolean = {
    batchApi.jobs().withName(jobName.name).delete()
  }

}
