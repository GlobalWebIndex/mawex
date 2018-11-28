package gwi.mawex.master

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, RootActorPath, UnhandledMessage}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{CurrentTopics, GetTopics, Subscribe, SubscribeAck}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import gwi.mawex.RemoteService.HostAddress
import gwi.mawex._
import gwi.mawex.executor.{ExecutorCmd, ForkedJvmConf, SandBox}
import gwi.mawex.m2p.TaskScheduled
import gwi.mawex.worker.Worker
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class LocalMawexSpec(_system: ActorSystem) extends AbstractMawexSpec(_system: ActorSystem) {
  def this() = this(ClusterService.buildClusterSystem(HostAddress("localhost", 0), List.empty, 1))
  protected def executorProps(underlyingProps: Props): Props = SandBox.localJvmProps(underlyingProps)
  protected def singleMsgTimeout: FiniteDuration = 3.seconds
}

class ForkedMawexSpec(_system: ActorSystem) extends AbstractMawexSpec(_system: ActorSystem) {
  def this() = this(ClusterService.buildClusterSystem(HostAddress("localhost", 0), List.empty, 1))
  protected def executorProps(underlyingProps: Props): Props = SandBox.forkingProps(underlyingProps, ForkedJvmConf(System.getProperty("java.class.path"), 1.minute, 1), ExecutorCmd(Some(jvmOpts)))
  protected def singleMsgTimeout: FiniteDuration = 6.seconds
}

abstract class AbstractMawexSpec(_system: ActorSystem) extends TestKit(_system) with DockerSupport with Matchers with FreeSpecLike with BeforeAndAfterAll with ImplicitSender with Eventually {
  import AbstractMawexSpec._

  private[this] val workerSystem  = ActorSystem("ClusterSystem", config)
  private[this] val ConsumerGroup = "default"
  private[this] val Pod           = "default"
  private[this] val MasterId      = "master"
  protected[this] val jvmOpts     = "-Djava.awt.headless=true -Xms32m -Xmx64m -XX:TieredStopAtLevel=1 -Xverify:none"

  protected def executorProps(underlyingProps: Props): Props
  protected def singleMsgTimeout: FiniteDuration

  override def beforeAll(): Unit = try super.beforeAll() finally {
    system.eventStream.subscribe(system.actorOf(Props(new UnhandledMessageListener())), classOf[UnhandledMessage])
    system.eventStream.subscribe(workerSystem.actorOf(Props(new UnhandledMessageListener())), classOf[UnhandledMessage])
    ClusterService.clusterSingletonActorRef(Master.Config(MasterId, singleMsgTimeout * 14, 1.minute), system)
  }

  override def afterAll(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val allTerminated = Future.sequence(Seq(
      system.terminate(),
      workerSystem.terminate()
    ))
    Await.ready(allTerminated, Duration.Inf)
    Thread.sleep(2000)
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
        val workerRef = workerSystem.actorOf(Worker.props(MasterId, clusterWorkerClient, workerId, props, singleMsgTimeout * 14, 1.second), s"worker-${workerId.id}")
        Thread.sleep(40)
        workerId -> workerRef
      }
    try
      fn(workerRefs)
    finally {
      workerRefs.map(_._2).foreach(workerSystem.stop)
      Thread.sleep(singleMsgTimeout.toMillis)
    }
  }

  private[this] def receiveResults(n: Int) =
    probe.receiveN(n, singleMsgTimeout * n).collect { case r: TaskResult => r }.partition(_.result.isSuccess)

  private[this] def assertStatus(pending: Set[String], progressing: Set[String], done: Vector[String] => Unit, workerStatus: Map[String, WorkerStatus] => Unit) = {
    masterProxy ! GetMawexState
    val MawexState(State(actualPending, actualProgressing, actualDone), workersById) = expectMsgType[MawexState]
    assertResult(pending)(actualPending.keySet.map(_.id.id))
    assertResult(progressing)(actualProgressing.keySet.map(_.id.id))
    workerStatus(workersById.map { case (workerId, workerRef) => workerId.id -> workerRef.status })
    done(actualDone.map(_.id))
  }

  private[this] def submitTasksAndValidate(refSize: Int, genTaskGroup: Int => String): Unit = {
    val nextTaskIds = (1 to 14).map( _ => taskCounter.getAndIncrement)
    for (n <- nextTaskIds) {
      Thread.sleep(30)
      val taskId = TaskId(n.toString, genTaskGroup(n))
      masterProxy ! Task(taskId, n)
      expectMsg(p2c.Accepted(taskId))
    }
    val (successes, failures) = receiveResults(28)
    assert(failures.isEmpty)
    successes.toVector.map(_.taskId.id.toInt).toSet should be(nextTaskIds.toSet)
    assert(successes.map(_.result.get.asInstanceOf[String]).toSet.size == refSize)
  }

  "mawex should" - {
    "execute single task in single consumer group and pod" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, Pod, "1"), executorProps(TestExecutor.identifyProps))) { _ =>
        val nextTaskId = taskCounter.getAndIncrement()
        val taskId = TaskId(nextTaskId.toString, ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(singleMsgTimeout, p2c.Accepted(taskId))
        probe.expectMsgType[m2p.TaskScheduled](singleMsgTimeout).taskId should be(TaskId(nextTaskId.toString, ConsumerGroup))
        probe.expectMsgType[TaskResult](singleMsgTimeout).taskId should be(TaskId(nextTaskId.toString, ConsumerGroup))
        assertStatus(Set.empty, Set.empty, done => assertResult(Vector(nextTaskId.toString))(done), workerStatus => assertResult(Map("1" -> Idle))(workerStatus))
      }
    }

    "handle failures" in {
      forWorkersDo((1 to 3).map( n => WorkerDef(WorkerId(ConsumerGroup, Pod, n.toString), executorProps(Props[FailingExecutor]))): _*) {  _ =>
        val nextTaskIds = (1 to 6).map( _ => taskCounter.getAndIncrement)
        for (n <- nextTaskIds) {
          val taskId = TaskId(n.toString, ConsumerGroup)
          masterProxy ! Task(taskId, n)
          expectMsg(p2c.Accepted(taskId))
        }
        val (successes, failures) = receiveResults(12)
        assert(failures.size == 2)
        assert(successes.size == 4)
        assertStatus(Set.empty, Set.empty, done => assertResult((1 until taskCounter.get).map(_.toString).toVector)(done), workerStatus => assertResult((1 to 3).map(n => n.toString -> Idle).toMap)(workerStatus))
      }
    }

    "allow for scaling out" in {
      forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "1", "1"), executorProps(TestExecutor.identifyProps))) {  _ =>
        val nextTaskId = taskCounter.getAndIncrement()
        val taskId = TaskId(nextTaskId.toString, ConsumerGroup)
        masterProxy ! Task(taskId, 1)
        expectMsg(p2c.Accepted(taskId))
        probe.expectMsgType[TaskScheduled](singleMsgTimeout).taskId should be(TaskId(nextTaskId.toString, ConsumerGroup))
        probe.expectMsgType[TaskResult](singleMsgTimeout).taskId should be(TaskId(nextTaskId.toString, ConsumerGroup))
        assertStatus(Set.empty, Set.empty, _ == (1 until taskCounter.get).map(_.toString).toVector, workerStatus => assertResult(Map("1" -> Idle))(workerStatus))
        forWorkersDo(WorkerDef(WorkerId(ConsumerGroup, "2", "2"), executorProps(TestExecutor.identifyProps))) {  _ =>
          submitTasksAndValidate(2, _ => ConsumerGroup)
          assertStatus(Set.empty, Set.empty, done => assertResult((1 until taskCounter.get).map(_.toString).toSet)(done.toSet), workerStatus => assertResult(Map("1" -> Idle, "2" -> Idle))(workerStatus))
        }
      }
    }

    "allow for scaling down" in {
      forWorkersDo((1 to 4).map(n => WorkerDef(WorkerId(ConsumerGroup, n.toString, n.toString), executorProps(TestExecutor.identifyProps))): _*) { workerRefs =>
        workerRefs.take(3).foreach { case (workerId, workerRef) =>
          masterProxy ! w2m.CheckOut(workerId)
          workerSystem.stop(workerRef)
        }
        submitTasksAndValidate(1, _ => ConsumerGroup)
        assertStatus(Set.empty, Set.empty, done => assertResult((1 until taskCounter.get).map(_.toString).toSet)(done.toSet), workerStatus => assertResult(Map("4" -> Idle))(workerStatus))

      }
    }

    "allow worker to restart and show up with a different actor ref" in {
      val workerDef = WorkerDef(WorkerId(ConsumerGroup, "1", "1"), executorProps(TestExecutor.identifyProps))
      forWorkersDo(workerDef) { workerRefs =>
        eventually (timeout(Span(2, Seconds)), interval(Span(100, Millis))) {
          assertStatus(Set.empty, Set.empty, _ => (), workerStatus => assertResult(Map("1" -> Idle))(workerStatus))
        }
        workerSystem.stop(workerRefs.head._2)
        eventually (timeout(Span(2, Seconds)), interval(Span(100, Millis))) {
          assertStatus(Set.empty, Set.empty, _ => (), workerStatus => assertResult(Map.empty)(workerStatus))
        }
        forWorkersDo(workerDef) { _ =>
          eventually (timeout(Span(2, Seconds)), interval(Span(100, Millis))) {
            assertStatus(Set.empty, Set.empty, _ => (), workerStatus => assertResult(Map("1" -> Idle))(workerStatus))
          }
        }
      }
    }

    "execute tasks sequentially by having multiple consumer groups share a pod" in {
      val running = new AtomicBoolean(false)
      forWorkersDo((1 to 3).map(n => WorkerDef(WorkerId(n.toString, Pod, n.toString), executorProps(TestExecutor.evalProps(Some(RunningEvaluation(running, 30)))))): _*) {  _ =>
        submitTasksAndValidate(3, n => ((n%3) + 1).toString)
        assertStatus(Set.empty, Set.empty, done => assertResult((1 until taskCounter.get).map(_.toString).toSet)(done.toSet), workerStatus => assertResult(Map("1" -> Idle, "2" -> Idle, "3" -> Idle))(workerStatus))
      }
    }

    "load balance work to many workers sharing a consumer group" in {
      forWorkersDo((1 to 2).map(n => WorkerDef(WorkerId(ConsumerGroup, n.toString, n.toString), executorProps(TestExecutor.evalProps(Some(SleepEvaluation(400)))))): _*) {  _ =>
        Thread.sleep(300)
        val nextTaskId_1 = taskCounter.getAndIncrement().toString
        val nextTaskId_2 = taskCounter.getAndIncrement().toString
        val taskId_1 = TaskId(nextTaskId_1, ConsumerGroup)
        val taskId_2 = TaskId(nextTaskId_2, ConsumerGroup)
        masterProxy ! Task(taskId_1, 1)
        masterProxy ! Task(taskId_2, 1)
        expectMsg(p2c.Accepted(taskId_1))
        expectMsg(p2c.Accepted(taskId_2))
        Thread.sleep(100)
        assertStatus(Set.empty, Set(nextTaskId_1, nextTaskId_2), _ => (), workerStatus => assertResult(Set(Busy(taskId_1), Busy(taskId_2)))(workerStatus.values.toSet))
        Thread.sleep(singleMsgTimeout.toMillis)
      }
    }

    "handle TaskFinished message idempotently" in {
      val workerId = WorkerId(ConsumerGroup, "1", "1")
      val taskId = TaskId("unknown", ConsumerGroup)
      masterProxy ! w2m.TaskFinished(workerId, taskId, Right("ok"))
      expectMsg(m2w.TaskResultAck(taskId))
    }
  }
}

object AbstractMawexSpec {

  case class WorkerDef(id: WorkerId, executorProps: Props)

  val config = ConfigFactory.parseString(
    """
    akka {
      actor.provider = "remote"
      remote.netty.tcp.port = 0
    }
    """.stripMargin
  ).withFallback(ConfigFactory.parseResources("serialization.conf"))
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
      case Task(taskId, n: Int) =>
        if (n == 3) throw new RuntimeException("Failing executor crashed !!!")
        val result = if (n == 4) Left("Failing executor's task crashed !!!") else Right(context.parent.path.name)
        sender() ! TaskResult(taskId, result)
    }
  }

  sealed trait Evaluation {
    def eval: Boolean
  }

  case class RunningEvaluation(running: AtomicBoolean, sleep: Int) extends Evaluation {
    def eval: Boolean = {
      val wasRunning = running.getAndSet(true)
      Thread.sleep(sleep)
      running.set(false)
      !wasRunning
    }
  }

  case class SleepEvaluation(sleep: Int) extends Evaluation {
    def eval: Boolean = {
      Thread.sleep(sleep)
      true
    }
  }

  class TestExecutor(evalOpt: Option[Evaluation] = Option.empty) extends Actor {
    def receive = {
      case Task(id, _) =>
        val result =
          evalOpt match {
            case None =>
              Right(context.parent.path.toString)
            case Some(evaluation) if evaluation.eval =>
              Right(context.parent.path.toString)
            case _ =>
              Left("Predicate failed !!!")
          }
        sender() ! TaskResult(id, result)
    }
  }

  object TestExecutor {
    def evalProps(evalOpt: Option[Evaluation]) = Props(classOf[TestExecutor], evalOpt)
    def identifyProps = Props(classOf[TestExecutor], Option.empty)
  }

  class UnhandledMessageListener extends Actor with ActorLogging {

    override def receive = {
      case message: UnhandledMessage =>
        log.error(s"CRITICAL!!! No actors found for message ${message.getMessage}")
        context.system.terminate()
    }
  }
}
