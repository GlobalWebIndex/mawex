package gwi.mawex

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Props}
import akka.cluster.client.ClusterClient.SendToAll
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.duration._

class LocalMasterProxy extends Actor {
  import context.dispatcher
  private val masterProxy = context.actorOf(
    ClusterSingletonProxy.props(
      settings = ClusterSingletonProxySettings(context.system).withRole("backend"),
      singletonManagerPath = "/user/master"
    ),
    name = "localMasterProxy")

  def receive = {
    case task: Task =>
      implicit val timeout = Timeout(5.seconds)
      (masterProxy ? task) map {
        case m2p.TaskAck(t) => p2c.Accepted(t)
      } recover { case _ => p2c.Rejected(task.id) } pipeTo sender()
  }

}

class RemoteMasterProxy(initialContacts: Set[ActorPath]) extends Actor with ActorLogging {
  import RemoteMasterProxy._
  import context.dispatcher

  var senderByTaskId = Map.empty[TaskId, ActorRef]

  private val clusterClient = context.system.actorOf(ClusterClient.props(ClusterClientSettings(context.system).withInitialContacts(initialContacts)), "cluster-client")

  def receive = {
    case CheckForZombieTask(taskId) =>
      senderByTaskId.get(taskId).foreach { s =>
        senderByTaskId -= taskId
        s ! p2c.Rejected(taskId)
        log.warning("TaskId {} has missing sender !!!", taskId)
      }
    case m2p.TaskAck(taskId) =>
      senderByTaskId.get(taskId) match {
        case Some(s) =>
          senderByTaskId -= taskId
          s ! p2c.Accepted(taskId)
        case None =>
          log.warning("TaskId {} has missing sender !!!", taskId)
      }
    case task: Task =>
      senderByTaskId += (task.id -> sender())
      clusterClient ! SendToAll("/user/master/singleton", task)
      context.system.scheduler.scheduleOnce(5.seconds, self, CheckForZombieTask(task.id))
  }

}

object RemoteMasterProxy {
  case class CheckForZombieTask(id: TaskId)
  def props(initialContacts: Set[ActorPath]) = Props(classOf[RemoteMasterProxy], initialContacts)
}