package example

import akka.actor.{Actor, ActorLogging}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import gwi.mawex._

class Consumer extends Actor with ActorLogging {

  private[this] val mediator = DistributedPubSub(context.system).mediator
  private[this] val MasterId = "master"

  mediator ! DistributedPubSubMediator.Subscribe(MasterId, self)

  def receive = {
    case _: DistributedPubSubMediator.SubscribeAck =>
      log.info("Subscribed to mediator ...")
    case TaskResult(task, result) =>
      log.info("Consumed result: {}", result)
  }

}