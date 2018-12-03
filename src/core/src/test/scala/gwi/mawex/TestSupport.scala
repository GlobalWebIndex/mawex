package gwi.mawex

import akka.actor.ActorSystem
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.ExecutionContext.Implicits
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.Try

trait DockerSupport {

  case class PPorts(host: String, guest: String)
  object PPorts {
    def from(host: Int, guest: Int) = new PPorts(host.toString, guest.toString)
  }

  private def docker(cmd: String) = Array("/bin/sh", "-c", s"docker $cmd")

  private def portsToString(ports: Seq[PPorts]) =
    if (ports.isEmpty) "" else ports.map { case PPorts(host, guest) => s"$host:$guest" }.mkString("-p ", " -p ", "")

  protected def startContainer(image: String, name: String, ports: Seq[PPorts], cmd: Option[String])(prepare: => Unit): Unit = {
    println(s"Starting container $name ...")
    require(Process(docker(s"run --name $name ${portsToString(ports)} -d $image ${cmd.getOrElse("")}")).run(true).exitValue == 0)
    Thread.sleep(500)
    prepare
  }

  protected def stopContainer(name: String)(cleanup: => Unit): Unit = {
    Try(cleanup)
    Process(docker(s"stop $name")).run()
    Process(docker(s"rm -fv $name")).run()
  }
}

trait AkkaSupport extends Suite with ScalaFutures with BeforeAndAfterAll {
  protected[this] implicit val timeout = Timeout(10.seconds)
  implicit lazy val system             = ActorSystem(s"AkkaSupportSystem")
  protected[this] implicit lazy val executionContext = Implicits.global
  protected[this] implicit lazy val scheduler = system.scheduler

  override def afterAll(): Unit =
    try super.afterAll()
    finally Await.ready(Future(system.terminate())(ExecutionContext.global), Duration.Inf)
}
