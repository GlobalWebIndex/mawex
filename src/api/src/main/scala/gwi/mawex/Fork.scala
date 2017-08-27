package gwi.mawex

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}

object Fork {
  def run(className: String, classPath: String, jvmOpts: Option[String], argsOpt: Option[String]): Process = {
    val command = Seq("java", "-cp", classPath, className) ++ argsOpt
    val builder = new ProcessBuilder(command:_*)
    jvmOpts.foreach(opts => builder.environment().put("JAVA_TOOL_OPTIONS", opts + " -XX:+ExitOnOutOfMemoryError"))
    Process(builder).run(false)
  }
  def await(process: Process, timeout: FiniteDuration): Int = {
    Try(Await.result(Future(process.exitValue())(ExecutionContext.global), timeout)) match {
      case Success(status) =>
        println(s"Forked JVM finished on time with status $status")
        status
      case Failure(ex) =>
        Try(process.destroy())
        println(s"Forked JVM didn't finish on time : ${ex.getMessage}")
        1
    }
  }
}