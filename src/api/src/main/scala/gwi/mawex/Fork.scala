package gwi.mawex

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}

/**
  * Mawex task execution can be forked which is the recommended way for bigger, long running or memory consuming jobs
  * as their execution cannot affect the host JVM which increases the overall resiliency of the system
  */
object Fork {
  def run(className: String, classPath: String, jvmOpts: Option[String], args: List[String]): Process = {
    val command = Seq("java", "-cp", classPath, className) ++ args
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