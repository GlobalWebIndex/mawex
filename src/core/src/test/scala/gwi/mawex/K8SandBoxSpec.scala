package gwi.mawex

import java.io.ByteArrayInputStream

import org.apache.commons.codec.binary.Base64
import io.fabric8.kubernetes.client.{BatchAPIGroupClient, ConfigBuilder}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import okhttp3.OkHttpClient
import org.scalatest.{FreeSpecLike, Ignore}

object K8 {
  val serverApiUrl: String  = ??? // cat ~/.kube/config | grep "server: "
  val token: String         = ??? // cat /run/secrets/kubernetes.io/serviceaccount/token
  val caCert: String        = ??? // cat /run/secrets/kubernetes.io/serviceaccount/ca.crt | base64 -w 0
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
    println(runJob(conf, "df", "-h"))

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
    println(runJob(conf, "df", "-h"))

    Thread.sleep(5000)

    println(deleteJob(conf))
  }

}
