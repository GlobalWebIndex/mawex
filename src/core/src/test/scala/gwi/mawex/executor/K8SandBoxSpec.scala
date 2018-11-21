package gwi.mawex.executor

import java.io.ByteArrayInputStream

import io.fabric8.kubernetes.client.{BatchAPIGroupClient, ConfigBuilder}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import okhttp3.OkHttpClient
import org.scalatest.{FreeSpecLike, Ignore}

object K8 {
  val conf =
    K8JobConf(
      "hello-world",
      "alpine",
      "default",
      "xxx", // cat ~/.kube/config | grep "server: "
      "xxx", // cat /run/secrets/kubernetes.io/serviceaccount/token
      "xxx" // new String(Base64.decodeBase64(cat /run/secrets/kubernetes.io/serviceaccount/ca.crt | base64 -w 0)
    )
}

@Ignore
class K8SandBoxSpec extends FreeSpecLike with K8BatchApiSupport {
  private lazy val apiClient =
    Config.fromToken(
      K8.conf.serverApiUrl,
      K8.conf.token,
    ).setSslCaCert(new ByteArrayInputStream(K8.conf.caCert.getBytes())).setDebugging(true)

  private[this] implicit lazy val batchApi = new BatchV1Api(apiClient)

  "k8 client should succeed" in {
    runJob(K8.conf, ExecutorCmd(List("df", "-h"), None))

    Thread.sleep(5000)

    deleteJob(K8.conf)
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
    println(runJob(conf, ExecutorCmd(List("df", "-h"), None)))

    Thread.sleep(5000)

    println(deleteJob(conf))
  }

}
