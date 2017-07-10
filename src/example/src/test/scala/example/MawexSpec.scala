package example

import akka.actor.{Actor, ActorSystem, Props, RootActorPath}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{CurrentTopics, GetTopics, Subscribe, SubscribeAck}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import gwi.mawex.Service.Address
import gwi.mawex._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}

object MawexSpec {

  val workerConfig = ConfigFactory.parseString("""
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

  class FlakyWorkExecutor extends Actor {
    var i = 0

    override def postRestart(reason: Throwable): Unit = {
      i = 3
      super.postRestart(reason)
    }

    def receive = {
      case n: Int =>
        i += 1
        if (i == 3) throw new RuntimeException("Flaky worker")
        if (i == 5) context.stop(self)

        val n2 = n * n
        val result = s"$n * $n = $n2"
        sender() ! e2w.TaskExecuted(Success(result))
    }
  }
}

class MawexSpec(_system: ActorSystem) extends TestKit(_system) with DockerSupport with Matchers with FlatSpecLike with BeforeAndAfterAll with ImplicitSender {
  import MawexSpec._

  def this() = this(Service.buildClusterSystem(Address("localhost", 6379), "foo", Address("localhost", 0), List.empty, 1))
  private[this] val workerSystem = ActorSystem("ClusterSystem", workerConfig)
  private[this] val ConsumerGroup = "default"
  private[this] val MasterId = "master"

  override def beforeAll(): Unit = try super.beforeAll() finally {
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
    for (n <- 1 to 3)
      workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, ConsumerGroup, Props(classOf[IdentityExecutor], Seq.empty), 1.second), "worker-" + n)
    workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, ConsumerGroup, Props[FlakyWorkExecutor], 1.second), "flaky-worker")

    val masterProxy = system.actorOf(RemoteMasterProxy.props(MasterId, initialContacts), "remoteMasterProxy")

    clusterWorkerClient -> masterProxy
  }

  "Distributed workers" should "perform work and publish results" in {
    val (clusterWorkerClient, masterProxy) = initSystems

    val results = TestProbe()
    DistributedPubSub(system).mediator ! Subscribe(MasterId, results.ref)
    expectMsgType[SubscribeAck]

    // make sure pub sub topics are replicated over to the backend system before triggering any work
    within(10.seconds) {
      awaitAssert {
        DistributedPubSub(system).mediator ! GetTopics
        expectMsgType[CurrentTopics].getTopics() should contain(MasterId)
      }
    }

    // make sure we can get one piece of work through to fail fast if it doesn't
    within(10.seconds) {
      awaitAssert {
        val taskId = TaskId("1", ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
      }
    }
    results.expectMsgType[TaskResult].task.id should be(TaskId("1", ConsumerGroup))

    // and then send in some actual work
    for (n <- 2 to 100) {
      val taskId = TaskId(n.toString, ConsumerGroup)
      masterProxy ! Task(taskId, n)
      expectMsg(p2c.Accepted(taskId))
    }

    results.within(10.seconds) {
      val (successes, failures) = results.receiveN(99).map { case TaskResult(task, result) => result.map(_ => task) }.partition(_.isSuccess)
      // nothing lost, and no duplicates
      assert(failures.size == 1)
      assert(successes.size == 98)
    }

    // consumer groups
    for (n <- 4 to 10)
      workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, n.toString, Props(classOf[IdentityExecutor], Seq.empty), 1.second), "worker-" + n)
    for (n <- 201 to 300) {
      val taskId = TaskId(n.toString, Random.shuffle(4 to 10).head.toString)
      masterProxy ! Task(taskId, n)
      expectMsg(p2c.Accepted(taskId))
    }
    results.within(10.seconds) {
      val (successIds, failures) = results.receiveN(100).map { case TaskResult(task, result) => result.map(_ => task) }.partition(_.isSuccess)
      successIds.toVector.map(_.get.id.id.toInt).sorted should be((201 to 300).toVector)
      assert(failures.isEmpty)
    }

  }

}