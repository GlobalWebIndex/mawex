package gwi.mawex.executor

import java.lang

import io.fabric8.kubernetes.api.model.batch.{Job, JobBuilder}
import io.fabric8.kubernetes.api.model.{ContainerBuilder, EnvVarBuilder}
import io.fabric8.kubernetes.client.BatchAPIGroupClient
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.models._

import scala.collection.JavaConverters._

trait K8BatchApiSupport {

  protected[mawex] def runJob(k8JobConf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchV1Api): V1Job = {
    val envVars = executorCmd.jvmOpts.map( opt => new V1EnvVarBuilder().withName("JAVA_TOOL_OPTIONS").withValue(opt).build() ).toList.asJava
    val container =
      new V1ContainerBuilder(true)
        .withName(k8JobConf.jobName)
        .withImage(k8JobConf.image)
        .withEnv(envVars)
        .withCommand(executorCmd.commands.asJava)
        .build

    val job =
      new V1JobBuilder(true)
        .withNewMetadata.withName(k8JobConf.jobName)
        .withNamespace(k8JobConf.namespace).and
        .withNewSpec().withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").and.and.and
        .build

    batchApi.createNamespacedJob(k8JobConf.namespace, job, "true")
  }

  protected[mawex] def deleteJob(k8JobConf: K8JobConf)(implicit batchApi: BatchV1Api): V1Status = {
    val opts = new V1DeleteOptionsBuilder().withPropagationPolicy("Background").build()
    batchApi.deleteNamespacedJob(k8JobConf.jobName, k8JobConf.namespace, opts, "false", 5, false, "Background")
  }

}

trait Fabric8BatchApiSupport {

  protected[mawex] def runJob(k8JobConf: K8JobConf, executorCmd: ExecutorCmd)(implicit batchApi: BatchAPIGroupClient): Job = {
    val envVars = executorCmd.jvmOpts.map( opt => new EnvVarBuilder().withName("JAVA_TOOL_OPTIONS").withValue(opt).build() ).toList.asJava
    val container =
      new ContainerBuilder(true)
        .withName(k8JobConf.jobName)
        .withImage(k8JobConf.image)
        .withEnv(envVars)
        .withCommand(executorCmd.commands.asJava)
        .build

    batchApi.jobs.create(
      new JobBuilder(true)
        .withNewMetadata.withName(k8JobConf.jobName).withNamespace(k8JobConf.namespace).endMetadata()
        .withNewSpec().withNewTemplate().withNewSpec().withContainers(container).withRestartPolicy("Never").endSpec().endTemplate().endSpec()
        .build
    )

  }

  protected[mawex] def deleteJob(k8JobConf: K8JobConf)(implicit batchApi: BatchAPIGroupClient): lang.Boolean = {
    batchApi.jobs().withName(k8JobConf.jobName).delete()
  }

}
