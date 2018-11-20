package gwi.mawex.executor

import java.io.ByteArrayInputStream

import io.fabric8.kubernetes.client.{BatchAPIGroupClient, ConfigBuilder}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import okhttp3.OkHttpClient
import org.apache.commons.codec.binary.Base64
import org.scalatest.{FreeSpecLike, Ignore}

object K8 {
  lazy val serverApiUrl: String  = "xxx" // cat ~/.kube/config | grep "server: "
  lazy val token: String         = "xxx" // cat /run/secrets/kubernetes.io/serviceaccount/token
  lazy val caCert: String        = "xxx" // new String(Base64.decodeBase64(cat /run/secrets/kubernetes.io/serviceaccount/ca.crt | base64 -w 0)
}

@Ignore
class K8SandBoxSpec extends FreeSpecLike with K8BatchApiSupport {
  import K8._

  private val apiClient =
    Config.fromToken(
      serverApiUrl,
      token,
    ).setSslCaCert(new ByteArrayInputStream(caCert.getBytes()))

  private[this] implicit val batchApi = new BatchV1Api(apiClient)

  "k8 client should succeed" in {
    val conf = K8JobConf("hello-world", "alpine", "default")
    println(runJob(conf, ExecutorCmd(List("df", "-h"), None)))

    Thread.sleep(5000)

    println(deleteJob(conf))
  }

}

@Ignore
class Fabric8SandBoxSpec extends FreeSpecLike with Fabric8BatchApiSupport {
  import K8._

  private val config =
    new ConfigBuilder()
      .withMasterUrl(serverApiUrl)
      .withOauthToken(token)
      .withCaCertData(caCert)
      .build

  val httpClient = new OkHttpClient()
  implicit val apiClient = new BatchAPIGroupClient(httpClient, config)


  "fabricate client should succeed" in {
    val conf = K8JobConf("hello-world", "alpine", "default")
    println(runJob(conf, ExecutorCmd(List("df", "-h"), None)))

    Thread.sleep(5000)

    println(deleteJob(conf))
  }

}
