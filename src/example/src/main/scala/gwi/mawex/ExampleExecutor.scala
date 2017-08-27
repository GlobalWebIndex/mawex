package gwi.mawex

import akka.actor.{Actor, ActorLogging}

import scala.util.Success
import org.backuity.clist._

case class ExampleCommand(foo: String) extends MawexCommand {
  def multiply(n: Int): String = {
    val n2 = n * n
    val result = s"$n * $n = $n2"
    println(s"$foo = $result ")
    result
  }
}

class ExampleCommandBuilder extends MawexCommandBuilder[ExampleCommand] {
  var foo = opt[String](default = "bar")

  def build = ExampleCommand(foo)
}

class ExampleExecutor(cmd: ExampleCommand) extends Actor with ActorLogging {

  override def preStart(): Unit =
    log.info("ExampleExecutor started ...")

  def receive = {
    case n: Int => sender() ! e2w.TaskExecuted(Success(cmd.multiply(n)))
  }

}