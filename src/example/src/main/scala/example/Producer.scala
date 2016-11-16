package example

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef}
import gwi.mawex.OpenProtocol._

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

object Producer {
  case object Tick
}

class Producer(masterProxy: ActorRef) extends Actor with ActorLogging {
  import Producer._
  import context.dispatcher
  val ConsumerGroup = "default"
  def scheduler = context.system.scheduler
  def rnd = ThreadLocalRandom.current
  def nextTaskId(): TaskId = TaskId(UUID.randomUUID().toString, ConsumerGroup)

  var n = 0

  override def preStart(): Unit =
    scheduler.scheduleOnce(5.seconds, self, Tick)

  // override postRestart so we don't call preStart and schedule a new Tick
  override def postRestart(reason: Throwable): Unit = ()

  def receive = {
    case Tick =>
      n += 1
      log.info("Produced work: {}", n)
      val work = Task(nextTaskId(), n)
      masterProxy ! work
      context.become(waitAccepted(work), discardOld = false)
  }

  def waitAccepted(work: Task): Actor.Receive = {
    case p2c.Accepted(id) =>
      context.unbecome()
      scheduler.scheduleOnce(rnd.nextInt(3, 10).seconds, self, Tick)
    case p2c.Rejected(id) =>
      log.info("Work not accepted, retry after a while")
      scheduler.scheduleOnce(3.seconds, masterProxy, work)
  }

}