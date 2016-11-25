package example

import akka.actor.{Actor, ActorLogging}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import gwi.mawex._

class Consumer extends Actor with ActorLogging {

  val mediator = DistributedPubSub(context.system).mediator
  mediator ! DistributedPubSubMediator.Subscribe(Master.ResultsTopic, self)

  def receive = {
    case _: DistributedPubSubMediator.SubscribeAck =>
      log.info("Subscribed to mediator ...")
    case TaskResult(task, result) =>
      log.info("Consumed result: {}", result)
  }

}