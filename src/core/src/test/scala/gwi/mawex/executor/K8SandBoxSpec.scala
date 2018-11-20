package gwi.mawex.executor

import java.io.ByteArrayInputStream

import io.fabric8.kubernetes.client.{BatchAPIGroupClient, ConfigBuilder}
import io.kubernetes.client.apis.BatchV1Api
import io.kubernetes.client.util.Config
import okhttp3.OkHttpClient
import org.apache.commons.codec.binary.Base64
import org.scalatest.{FreeSpecLike, Ignore}

object K8 {
  val serverApiUrl = "https://104.155.127.136"
  val token = "eyJhbGciOiJSUzI1NiIsImtpZCI6IiJ9.eyJpc3MiOiJrdWJlcm5ldGVzL3NlcnZpY2VhY2NvdW50Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9uYW1lc3BhY2UiOiJkZWZhdWx0Iiwia3ViZXJuZXRlcy5pby9zZXJ2aWNlYWNjb3VudC9zZWNyZXQubmFtZSI6ImRlZmF1bHQtdG9rZW4tYjd6NWciLCJrdWJlcm5ldGVzLmlvL3NlcnZpY2VhY2NvdW50L3NlcnZpY2UtYWNjb3VudC5uYW1lIjoiZGVmYXVsdCIsImt1YmVybmV0ZXMuaW8vc2VydmljZWFjY291bnQvc2VydmljZS1hY2NvdW50LnVpZCI6ImNhMzNlOGY3LWE5ZjgtMTFlOC1hOGZhLTQyMDEwYTg0MDI3YiIsInN1YiI6InN5c3RlbTpzZXJ2aWNlYWNjb3VudDpkZWZhdWx0OmRlZmF1bHQifQ.0tS808uWSZaHgaUXVBTTI5NAc819k_K9foQxF9dnYr76dvfOnSlHzOoqxeFA29AmZe56Pj5ZC6V3QFy-oqo74LiJk8qAEhj3lAxHwwYrnfP9YJnblzb0w88lLBKdVC_S_VLGYw2U3Jj4kMSWiyU3qZaQpkl0XxluD2g1SBLIMpoCjtE5-fQnPLg8-m4hbulRuXJKFjI80-agttvMPGuL-bs7FBYE6iA5MMGhUCqGMMaNxue9bhcA2PKgJc0Z2s8KolrYEI1rxWsu0bSYzIB4OMKmXMDMYzTYI-vhe-icgc0vPwSYOX_o-Mj2ItdvPRmeeKNnwsRv5pFmYo-psCI6BQ"
  val caCert = new String(Base64.decodeBase64("LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSURERENDQWZTZ0F3SUJBZ0lSQU96VW5WVjJVZVVnR3VaSWdhVWx6Qll3RFFZSktvWklodmNOQVFFTEJRQXcKTHpFdE1Dc0dBMVVFQXhNa05HSmlNekpqTmpjdE1qWmpaQzAwWXpBMkxXRmpaR0V0WmpVMFptSmlPRFExWlRObQpNQjRYRFRFNE1EZ3lOekV4TlRZeU1Wb1hEVEl6TURneU5qRXlOVFl5TVZvd0x6RXRNQ3NHQTFVRUF4TWtOR0ppCk16SmpOamN0TWpaalpDMDBZekEyTFdGalpHRXRaalUwWm1KaU9EUTFaVE5tTUlJQklqQU5CZ2txaGtpRzl3MEIKQVFFRkFBT0NBUThBTUlJQkNnS0NBUUVBdjlabWw5N2ludGJJcmpRcGVBaUo4UDBTWTFCOEpQcDhLZmJIMHhpaAo2Lzh6eE9JbnU0eDV5UlVoQ3h1ZXZYQXl2SnpvSXRLVS85VzZqcVlMY0UwT3ZVTGgveG9Ma3AxRWgrVjFMRmdZCmVnQ3hhbEJrVmhIZWFGZk5ObWdKbjZGRTdBTDVGOTBJc0xPMWpTWWpEMTYwQUlDUVdoRnJ0aFdsZHhyTjNwNngKODJaeVdhZkVrOHgxbVErK0FhSzMxbVhsMis0enM1ay9WQ3dXall2aGZjdU1UaUQ2eEE2dVBSSTI1UWRkYWtyMApGMU9zVU8xbnNqTFJQMzJYRE03WGJlSXU0MkFUVlRwZnJ0dytWYnZzK2tKSU96VG9PTFpjZWFUOGxCL1BJU0FQCkw0Q2JQc1ROT2xQcWF0VzNsT25xK0twRUhhT3V3MFQvS1MxR0xpd1dqRFEvdHdJREFRQUJveU13SVRBT0JnTlYKSFE4QkFmOEVCQU1DQWdRd0R3WURWUjBUQVFIL0JBVXdBd0VCL3pBTkJna3Foa2lHOXcwQkFRc0ZBQU9DQVFFQQpVQ3ZUd2dzalhvdXpsMUZEdlltR20wMWVYTTgzSmlnWHRJSGVDVElHcnFWdG84Z3BGUCtiVTRscUEyVmEzaGp2Cm1ubjF6Ymt5c0lKWnlVU1M0b2pwbXp1dE1rd2dxTWZFSFk5WTBGUkdwQ3ZpaHoyVk5zT3RaRS9QQ0o4MW5sL1EKdzVSRzB4RGxreEFaSDlHUkNVcGJsTk0rSFZ1WlpaMERGUEEwQlh4bzA4aU9HWHN1VWx2TE5aQ1V0YWJzOHBBNQoxOWIzSklTYjNJZWMwcko2Z0VoYkwzandJL0lDcWkxZ1h1UDR3bStUNTl1OUNlQ0tzWlpaOFRteW53Uk5uK1BXClFaKzRzNGl5ZStJaTNJb1d4LzlKb09aNGthbk1JVUx6R1JLMm1oYnRVTHRlWTRsUVNmLzhDYld6elNBYmN1YS8KTlVLRTJNaVc3K1RoU1B5UVo1ZmlDZz09Ci0tLS0tRU5EIENFUlRJRklDQVRFLS0tLS0K".getBytes()))
}

@Ignore
class K8SandBoxSpec extends FreeSpecLike with K8BatchApiSupport {
  import K8._

  private lazy val apiClient =
    Config.fromToken(
      serverApiUrl,
      token,
    ).setSslCaCert(new ByteArrayInputStream(caCert.getBytes()))

  private[this] implicit lazy val batchApi = new BatchV1Api(apiClient)

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

  private lazy val config =
    new ConfigBuilder()
      .withMasterUrl(serverApiUrl)
      .withOauthToken(token)
      .withCaCertData(caCert)
      .build

  val httpClient = new OkHttpClient()
  implicit lazy val apiClient = new BatchAPIGroupClient(httpClient, config)


  "fabricate client should succeed" in {
    val conf = K8JobConf("hello-world", "alpine", "default")
    println(runJob(conf, ExecutorCmd(List("df", "-h"), None)))

    Thread.sleep(5000)

    println(deleteJob(conf))
  }

}
