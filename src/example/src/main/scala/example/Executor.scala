package example

import akka.actor.{Actor, ActorLogging}
import gwi.mawex.OpenProtocol.e2w
import gwi.mawex.Service.Address

import scala.util.Success

class Executor(args: Seq[String]) extends Actor with ActorLogging {

  // In real world, remote client would be a standalone service/container, here we start it from a Worker service to avoid building it
  Client.startRemoteClient(Address("workers", 0), List(Address("master-a", 2552), Address("master-b", 2551)))

  def receive = {
    case n: Int =>
      val n2 = n * n
      val result = s"$n * $n = $n2"
      log.info("Executing task ...")
      sender() ! e2w.TaskExecuted(Success(result))
  }

}