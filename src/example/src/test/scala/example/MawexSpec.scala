package example

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, RootActorPath, UnhandledMessage}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{CurrentTopics, GetTopics, Subscribe, SubscribeAck}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import gwi.mawex.Service.Address
import gwi.mawex._
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

object MawexSpec {

  case class WorkerDef(id: WorkerId, executorProps: Props)

  val workerConfig = ConfigFactory.parseString(
    """
    akka {
      actor{
         provider = remote
         kryo.idstrategy = automatic
      }
      remote.netty.tcp.port=0
    }
    """.stripMargin
  ).withFallback(ConfigFactory.load("serialization"))
    .withFallback(ConfigFactory.load())

  import scala.language.implicitConversions
  implicit def eitherToTry[B](either: Either[String, B]): Try[B] = {
    either match {
      case Right(obj) => Success(obj)
      case Left(err) => Failure(new RuntimeException(err))
    }
  }

  class FailingExecutor extends Actor {
    def receive = {
      case n: Int =>
        if (n == 3) throw new RuntimeException("Failing executor crashed !!!")
        val result = if (n == 4) Failure(new RuntimeException("Failing executor's task crashed !!!")) else Success(context.parent.path.name)
        sender() ! e2w.TaskExecuted(result)
    }
  }
  class TestExecutor(fnOpt: Option[() => Boolean] = Option.empty) extends Actor {
    def receive = {
      case _ =>
        val result =
          fnOpt match {
            case None             => Success(context.parent.path.name)
            case Some(fn) if fn() => Success(context.parent.path.name)
            case _                => Failure(new RuntimeException("Predicate failed !!!"))
          }
        sender() ! e2w.TaskExecuted(result)
    }
  }

  object TestExecutor {
    def evalProps(fnOpt: Option[() => Boolean]) = Props(classOf[TestExecutor], fnOpt)
    def identifyProps = Props(classOf[TestExecutor], Option.empty)

    def runningEvaluation(running: AtomicBoolean, sleep: Int): () => Boolean =
      () => {
        val wasRunning = running.getAndSet(true)
        Thread.sleep(sleep)
        running.set(false)
        !wasRunning
      }

    def sleepEvaluation(sleep: Int): () => Boolean =
      () => {
        Thread.sleep(sleep)
        true
      }
  }

  class UnhandledMessageListener extends Actor with ActorLogging {

    override def receive = {
      case message: UnhandledMessage =>
        log.error(s"CRITICAL!!! No actors found for message ${message.getMessage}")
        context.system.terminate()
    }
  }
}

class MawexSpec(_system: ActorSystem) extends TestKit(_system) with DockerSupport with Matchers with FreeSpecLike with BeforeAndAfterAll with ImplicitSender {
  import MawexSpec._

  def this() = this(Service.buildClusterSystem(Address("localhost", 6379), "foo", Address("localhost", 0), List.empty, 1))

  private[this] val workerSystem = ActorSystem("ClusterSystem", workerConfig)
  private[this] val ConsumerGroup = "default"
  private[this] val Pod = "default"
  private[this] val MasterId = "master"

  override def beforeAll(): Unit = try super.beforeAll() finally {
    system.eventStream.subscribe(system.actorOf(Props(new UnhandledMessageListener())), classOf[UnhandledMessage])
    system.eventStream.subscribe(workerSystem.actorOf(Props(new UnhandledMessageListener())), classOf[UnhandledMessage])
    startContainer("redis", "redis-test", 6379)(())
    Service.backendSingletonActorRef(1.second, system, MasterId)()
  }

  override def afterAll(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    stopContainer("redis-test")(())
    val allTerminated = Future.sequence(Seq(
      system.terminate(),
      workerSystem.terminate()
    ))

    Await.ready(allTerminated, Duration.Inf)
  }

  private[this] def initSystems = {
    val backendClusterAddress = Cluster(system).selfAddress
    val clusterProbe = TestProbe()
    Cluster(system).subscribe(clusterProbe.ref, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
    Cluster(system).join(backendClusterAddress)
    clusterProbe.expectMsgType[MemberUp]

    val initialContacts = Set(RootActorPath(backendClusterAddress) / "system" / "receptionist")
    val clusterWorkerClient = workerSystem.actorOf(ClusterClient.props(ClusterClientSettings(workerSystem).withInitialContacts(initialContacts)), "clusterWorkerClient")

    val masterProxy = system.actorOf(LocalMasterProxy.props(MasterId), "remoteMasterProxy")

    val probe = TestProbe()
    DistributedPubSub(system).mediator ! Subscribe(MasterId, probe.ref)
    expectMsgType[SubscribeAck]

    // make sure pub sub topics are replicated over to the backend system before triggering any work
    within(10.seconds) {
      awaitAssert {
        DistributedPubSub(system).mediator ! GetTopics
        expectMsgType[CurrentTopics].getTopics() should contain(MasterId)
      }
    }

    (clusterWorkerClient, masterProxy, probe)
  }

  lazy val (clusterWorkerClient, masterProxy, probe) = initSystems

  private[this] def forWorkersDo(workerSpots: WorkerDef*)(fn: Seq[(WorkerId, ActorRef)] => Unit): Unit = {
    val workerRefs =
      workerSpots.map { case WorkerDef(workerId, props) =>
        workerId -> workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, workerId, props, 1.second, 1.second), s"worker-${workerId.id}")
      }
    Thread.sleep(100)
    try
      fn(workerRefs)
    finally {
      workerRefs.map(_._2).foreach(workerSystem.stop)
      Thread.sleep(100)
    }
  }

  private[this] def receiveResults(n: Int) = probe.receiveN(n, 5.seconds).map { case r: TaskResult => r }.partition(_.result.isSuccess)

  private[this] def validateWork(refSize: Int, genTaskGroup: Int => String) = {
    for (n <- 201 to 230) {
      val taskId = TaskId(n.toString, genTaskGroup(n))
      masterProxy ! Task(taskId, n)
      expectMsg(p2c.Accepted(taskId))
    }
    val (successes, failures) = receiveResults(30)
    assert(failures.isEmpty)
    successes.toVector.map(_.task.id.id.toInt).toSet should be((201 to 230).toSet)
    assert(successes.map(_.result.get.asInstanceOf[String]).toSet.size == refSize)
  }

  "mawex should" - {
    "execute single task in single consumer group and pod" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, Pod, "1"), TestExecutor.identifyProps)) { _ =>
        val taskId = TaskId("1", ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
        probe.expectMsgType[TaskResult].task.id should be(TaskId("1", ConsumerGroup))
      }
    }

    "handle failures" in {
      forWorkersDo((1 to 3).map( n => WorkerDef(WorkerId(ConsumerGroup, Pod, n.toString), Props[FailingExecutor])): _*) {  _ =>
        for (n <- 2 to 20) {
          val taskId = TaskId(n.toString, ConsumerGroup)
          masterProxy ! Task(taskId, n)
          expectMsg(p2c.Accepted(taskId))
        }
        val (successes, failures) = receiveResults(19)
        assert(failures.size == 2)
        assert(successes.size == 17)
      }
    }

    "allow for scaling out" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "1", "1"), TestExecutor.evalProps(Some(TestExecutor.sleepEvaluation(50))))) {  _ =>
        val taskId = TaskId("1", ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
        probe.expectMsgType[TaskResult].task.id should be(TaskId("1", ConsumerGroup))

        forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "2", "2"), TestExecutor.evalProps(Some(TestExecutor.sleepEvaluation(50))))) {  _ =>
          validateWork(2, _ => ConsumerGroup)
        }
      }
    }

    "allow for scaling down" in {
      forWorkersDo((1 to 4).map(n => WorkerDef(WorkerId(ConsumerGroup, n.toString, n.toString), TestExecutor.evalProps(Some(TestExecutor.sleepEvaluation(50))))): _*) { workerRefs =>
        workerRefs.take(3).foreach { case (workerId, workerRef) =>
          masterProxy ! w2m.CheckOut(workerId)
          workerSystem.stop(workerRef)
        }
        validateWork(1, _ => ConsumerGroup)
      }
    }

    "execute tasks sequentially by having multiple consumer groups share a pod" in {
      val running = new AtomicBoolean(false)
      forWorkersDo((1 to 3).map(n => WorkerDef(WorkerId(n.toString, Pod, n.toString), TestExecutor.evalProps(Some(TestExecutor.runningEvaluation(running, 30))))): _*) {  _ =>
        validateWork(3, n => ((n%3) + 1).toString)
      }
    }

    "load balance work to many workers sharing a consumer group" in {
      forWorkersDo((1 to 4).map(n => WorkerDef(WorkerId((n%2).toString, n.toString, n.toString), TestExecutor.evalProps(Some(TestExecutor.sleepEvaluation(70))))): _*) {  _ =>
        validateWork(4, n => (n%2).toString)
      }
    }

  }

}