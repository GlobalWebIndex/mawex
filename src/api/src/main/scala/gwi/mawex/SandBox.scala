package gwi.mawex

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}

object SandBox {

  def fork(classPath: String, mainClass: String, timeout: Duration, args: Seq[String]): Int = {
    val allArgs = Seq("java", "-cp", classPath, mainClass) ++ args
    println(s"${allArgs.mkString(" ")}")
    val builder = new ProcessBuilder(allArgs:_*)
    sys.env.get("FORK_JAVA_TOOL_OPTIONS").foreach { javaToolOpts =>
      builder.environment().put("JAVA_TOOL_OPTIONS", javaToolOpts)
    }
    Try(Await.result(Future(Process(builder).run(false).exitValue())(ExecutionContext.global), timeout)) match {
      case Success(status) =>
        println("Forked JVM successfully finished")
        Thread.sleep(3000)
        status
      case Failure(ex) =>
        println(s"Forked JVM didn't finish on time because ${ex.getMessage}")
        ex.printStackTrace()
        1
    }
  }

}
