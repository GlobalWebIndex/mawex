package gwi.mawex

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import akka.actor.{Actor, ActorLogging, ActorRef}

import scala.concurrent.duration._

object Producer {
  case object Tick
}

class Producer(masterProxy: ActorRef) extends Actor with ActorLogging {
  import Producer._
  import context.dispatcher
  private[this] val ConsumerGroup = "default"
  private[this] def scheduler = context.system.scheduler
  private[this] def rnd = ThreadLocalRandom.current
  private[this] def nextTaskId(): TaskId = TaskId(UUID.randomUUID().toString, ConsumerGroup)

  private[this] var n = 0

  override def preStart(): Unit = {
    log.info("Producer started ...")
    scheduler.scheduleOnce(5.seconds, self, Tick)
  }

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
    case p2c.Accepted(_) =>
      context.unbecome()
      scheduler.scheduleOnce(rnd.nextInt(3, 10).seconds, self, Tick)
    case p2c.Rejected(_) =>
      log.info("Work not accepted, retry after a while")
      scheduler.scheduleOnce(3.seconds, masterProxy, work)
  }

}