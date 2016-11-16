package example

import akka.actor.{Actor, ActorLogging}
import gwi.mawex.OpenProtocol.e2w

import scala.util.Success

class Executor(args: Seq[String]) extends Actor with ActorLogging {

  def receive = {
    case n: Int =>
      val n2 = n * n
      val result = s"$n * $n = $n2"
      log.info("Executing task ...")
      sender() ! e2w.TaskExecuted(Success(result))
  }

}