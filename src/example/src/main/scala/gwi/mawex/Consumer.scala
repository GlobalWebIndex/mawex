package gwi.mawex

import akka.actor.{Actor, ActorLogging}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}

class Consumer extends Actor with ActorLogging {

  private[this] val mediator = DistributedPubSub(context.system).mediator

  mediator ! DistributedPubSubMediator.Subscribe(Client.MasterId, self)

  def receive = {
    case _: DistributedPubSubMediator.SubscribeAck =>
      log.info("Subscribed to mediator ...")
    case TaskResult(task, result) =>
      log.info("Consumed result: {}", result)
  }

}