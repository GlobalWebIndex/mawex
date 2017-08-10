package gwi.mawex

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, RootActorPath, UnhandledMessage}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{CurrentTopics, GetTopics, Subscribe, SubscribeAck}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import gwi.mawex.Service.Address
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Second, Span}
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class MawexSpec(_system: ActorSystem) extends TestKit(_system) with DockerSupport with Matchers with FreeSpecLike with BeforeAndAfterAll with ImplicitSender with Eventually {
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

  private[this] lazy val (clusterWorkerClient, masterProxy, probe) = initSystems
  private[this] val taskCounter = new AtomicInteger(1)

  private[this] def forWorkersDo(workerSpots: WorkerDef*)(fn: Seq[(WorkerId, ActorRef)] => Unit): Unit = {
    val workerRefs =
      workerSpots.map { case WorkerDef(workerId, props) =>
        val workerRef = workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, workerId, props, 1.second, 1.second), s"worker-${workerId.id}")
        workerId -> workerRef
      }
    try
      fn(workerRefs)
    finally {
      workerRefs.map(_._2).foreach(workerSystem.stop)
      Thread.sleep(50)
    }
  }

  private[this] def receiveResults(n: Int) = probe.receiveN(n, 5.seconds).map { case r: TaskResult => r }.partition(_.result.isSuccess)

  private[this] def assertStatus(pending: Set[String], progressing: Set[String], done: Vector[String] => Boolean, workerStatusById: Map[String, WorkerStatus]) = {
    masterProxy ! GetMawexState
    val MawexState(State(actualPending, actualProgressing, actualDone), workersById) = expectMsgType[MawexState]
    assertResult(pending)(actualPending.keySet.map(_.id.id))
    assertResult(progressing)(actualProgressing.keySet.map(_.id.id))
    assertResult(workerStatusById)(workersById.map { case (workerId, workerRef) => workerId.id -> workerRef.status })
    assert(done(actualDone.map(_.id)))
  }

  private[this] def submitTasksAndValidate(refSize: Int, genTaskGroup: Int => String): Unit = {
    val nextTaskIds = (1 to 30).map( _ => taskCounter.getAndIncrement)
    for (n <- nextTaskIds) {
      val taskId = TaskId(n.toString, genTaskGroup(n))
      masterProxy ! Task(taskId, n)
      expectMsg(p2c.Accepted(taskId))
    }
    val (successes, failures) = receiveResults(30)
    assert(failures.isEmpty)
    successes.toVector.map(_.task.id.id.toInt).toSet should be(nextTaskIds.toSet)
    assert(successes.map(_.result.get.asInstanceOf[String]).toSet.size == refSize)
  }

  "mawex should" - {
    "execute single task in single consumer group and pod" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, Pod, "1"), TestExecutor.identifyProps)) { _ =>
        val nextTaskId = taskCounter.getAndIncrement()
        val taskId = TaskId(nextTaskId.toString, ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
        probe.expectMsgType[TaskResult].task.id should be(TaskId(nextTaskId.toString, ConsumerGroup))
        assertStatus(Set.empty, Set.empty, _ == Vector(nextTaskId.toString), Map("1" -> Idle))
      }
    }

    "handle failures" in {
      forWorkersDo((1 to 3).map( n => WorkerDef(WorkerId(ConsumerGroup, Pod, n.toString), Props[FailingExecutor])): _*) {  _ =>
        val nextTaskIds = (1 to 20).map( _ => taskCounter.getAndIncrement)
        for (n <- nextTaskIds) {
          val taskId = TaskId(n.toString, ConsumerGroup)
          masterProxy ! Task(taskId, n)
          expectMsg(p2c.Accepted(taskId))
        }
        val (successes, failures) = receiveResults(20)
        assert(failures.size == 2)
        assert(successes.size == 18)
        assertStatus(Set.empty, Set.empty, _ == (1 until taskCounter.get).map(_.toString).toVector, (1 to 3).map(n => n.toString -> Idle).toMap)
      }
    }

    "allow for scaling out" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "1", "1"), TestExecutor.identifyProps)) {  _ =>
        val nextTaskId = taskCounter.getAndIncrement()
        val taskId = TaskId(nextTaskId.toString, ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
        probe.expectMsgType[TaskResult].task.id should be(TaskId(nextTaskId.toString, ConsumerGroup))
        assertStatus(Set.empty, Set.empty, _ == (1 until taskCounter.get).map(_.toString).toVector, Map("1" -> Idle))
        forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "2", "2"), TestExecutor.identifyProps)) {  _ =>
          submitTasksAndValidate(2, _ => ConsumerGroup)
          assertStatus(Set.empty, Set.empty, _ == (1 until taskCounter.get).map(_.toString).toVector, Map("1" -> Idle, "2" -> Idle))
        }
      }
    }

    "allow for scaling down" in {
      forWorkersDo((1 to 4).map(n => WorkerDef(WorkerId(ConsumerGroup, n.toString, n.toString), TestExecutor.identifyProps)): _*) { workerRefs =>
        workerRefs.take(3).foreach { case (workerId, workerRef) =>
          masterProxy ! w2m.CheckOut(workerId)
          workerSystem.stop(workerRef)
        }
        submitTasksAndValidate(1, _ => ConsumerGroup)
        assertStatus(Set.empty, Set.empty, _ == (1 until taskCounter.get).map(_.toString).toVector, Map("4" -> Idle))

      }
    }

    "allow worker to restart and show up with a different actor ref" in {
      val workerDef = WorkerDef(WorkerId(ConsumerGroup, "1", "1"), TestExecutor.identifyProps)
      forWorkersDo(workerDef) { workerRefs =>
        eventually (timeout(Span(1, Second)), interval(Span(100, Millis))) {
          assertStatus(Set.empty, Set.empty, _ => true, Map("1" -> Idle))
        }
        workerSystem.stop(workerRefs.head._2)
        eventually (timeout(Span(1, Second)), interval(Span(100, Millis))) {
          assertStatus(Set.empty, Set.empty, _ => true, Map.empty)
        }
        forWorkersDo(workerDef) { _ =>
          eventually (timeout(Span(1, Second)), interval(Span(100, Millis))) {
            assertStatus(Set.empty, Set.empty, _ => true, Map("1" -> Idle))
          }
        }
      }
    }

    "execute tasks sequentially by having multiple consumer groups share a pod" in {
      val running = new AtomicBoolean(false)
      forWorkersDo((1 to 3).map(n => WorkerDef(WorkerId(n.toString, Pod, n.toString), TestExecutor.evalProps(Some(TestExecutor.runningEvaluation(running, 30))))): _*) {  _ =>
        submitTasksAndValidate(3, n => ((n%3) + 1).toString)
        assertStatus(Set.empty, Set.empty, _.toSet == (1 until taskCounter.get).map(_.toString).toSet, Map("1" -> Idle, "2" -> Idle, "3" -> Idle))
      }
    }

    "load balance work to many workers sharing a consumer group" in {
      forWorkersDo((1 to 2).map(n => WorkerDef(WorkerId(ConsumerGroup, n.toString, n.toString), TestExecutor.evalProps(Some(TestExecutor.sleepEvaluation(300))))): _*) {  _ =>
        val nextTaskId_1 = taskCounter.getAndIncrement().toString
        val nextTaskId_2 = taskCounter.getAndIncrement().toString
        val taskId_1 = TaskId(nextTaskId_1, ConsumerGroup)
        val taskId_2 = TaskId(nextTaskId_2, ConsumerGroup)
        masterProxy ! Task(taskId_1, 1)
        expectMsg(p2c.Accepted(taskId_1))
        masterProxy ! Task(taskId_2, 1)
        expectMsg(p2c.Accepted(taskId_2))
        eventually (timeout(Span(1, Second)), interval(Span(50, Millis))) {
          assertStatus(Set.empty, Set(nextTaskId_1, nextTaskId_2), _ => true, Map("1" -> Busy(taskId_1), "2" -> Busy(taskId_2)))
        }
      }
    }

  }
}

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
