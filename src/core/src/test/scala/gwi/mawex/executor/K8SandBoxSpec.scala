package gwi.mawex.executor

import java.io.ByteArrayInputStream

import gwi.mawex.AkkaSupport

import scala.concurrent.duration._
import io.fabric8.kubernetes.client.{BatchAPIGroupClient, ConfigBuilder}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import okhttp3.OkHttpClient
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FreeSpecLike, Ignore}

object K8 {
  val conf =
    K8JobConf(
      "alpine",
      "default",
      K8Resources("150m", "100Mi", "150m", "100Mi"),
      false,
      1.minute,
      1,
      "xxx", // cat ~/.kube/config | grep "server: "
      "xxx", // cat /run/secrets/kubernetes.io/serviceaccount/token
      "xxx" // new String(Base64.decodeBase64(cat /run/secrets/kubernetes.io/serviceaccount/ca.crt | base64 -w 0)
    )
}

@Ignore
class K8SandBoxSpec extends FreeSpecLike with K8BatchApiSupport with AkkaSupport {
  implicit lazy val futurePatience = PatienceConfig(timeout = Span(10, Seconds), interval = Span(300, Millis))
  private lazy val apiClient =
    Config.fromToken(
      K8.conf.serverApiUrl,
      K8.conf.token,
    ).setSslCaCert(new ByteArrayInputStream(K8.conf.caCert.getBytes())).setDebugging(K8.conf.debug)

  private[this] implicit lazy val batchApi = new BatchV1Api(apiClient)

  "k8 client should succeed" in {
    whenReady(runJob(JobName("job-test"), K8.conf, K8sExecutorCmd(List("df", "-h"), None, None, None))) { createResult =>
      Thread.sleep(3000)
      whenReady(deleteJob(JobName("job-test"), K8.conf)) { deleteResult =>

      }
    }
  }

}

@Ignore
class Fabric8SandBoxSpec extends FreeSpecLike with Fabric8BatchApiSupport {
  import K8._

  private lazy val config =
    new ConfigBuilder()
      .withMasterUrl(K8.conf.serverApiUrl)
      .withOauthToken(K8.conf.token)
      .withCaCertData(K8.conf.caCert)
      .build

  val httpClient = new OkHttpClient()
  implicit lazy val apiClient = new BatchAPIGroupClient(httpClient, config)

  "fabricate client should succeed" in {
    println(runJob(JobName("job-test"), conf, K8sExecutorCmd(List("df", "-h"), None, None, None)))

    Thread.sleep(5000)

    println(deleteJob(JobName("job-test"), conf))
  }

}
