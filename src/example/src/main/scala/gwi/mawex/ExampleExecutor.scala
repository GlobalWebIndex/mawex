package gwi.mawex

import akka.actor.{Actor, ActorLogging}
import com.typesafe.config.Config
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

  def build(config: Config) = ExampleCommand(foo)
}

class ExampleExecutor(cmd: ExampleCommand) extends Actor with ActorLogging {

  override def preStart(): Unit =
    log.info("ExampleExecutor started ...")

  def receive = {
    case Task(id, n: Int) => sender() ! TaskResult(id, Right(cmd.multiply(n)))
  }

}