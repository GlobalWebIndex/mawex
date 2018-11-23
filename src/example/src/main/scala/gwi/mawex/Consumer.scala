package gwi.mawex

import akka.actor.{Actor, ActorLogging}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}

class Consumer extends Actor with ActorLogging {

  private[this] val mediator = DistributedPubSub(context.system).mediator

  mediator ! DistributedPubSubMediator.Subscribe(ClientCmd.MasterId, self)

  override def preStart(): Unit = {
    log.info("Consumer started ...")
  }

  def receive = {
    case _: DistributedPubSubMediator.SubscribeAck =>
      log.info("Subscribed to mediator ...")
    case TaskResult(taskId, result) =>
      log.info("Consumed task () result: {}", taskId, result)
  }

}