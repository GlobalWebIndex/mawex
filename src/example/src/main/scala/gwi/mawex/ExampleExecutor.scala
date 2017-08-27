package gwi.mawex

import akka.actor.{Actor, ActorLogging}

import scala.util.Success

class ExampleExecutor(args: Seq[String]) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("ExampleExecutor started ...")
  }

  def receive = {
    case n: Int =>
      val n2 = n * n
      val result = s"$n * $n = $n2"
      log.info("Executing task ...")
      sender() ! e2w.TaskExecuted(Success(result))
  }

}