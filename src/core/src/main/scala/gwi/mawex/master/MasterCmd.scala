package gwi.mawex.master

import java.io.File

import com.typesafe.scalalogging.LazyLogging
import gwi.mawex.ClusterService
import org.backuity.clist.{Command, opt}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MasterCmd extends Command(name = "master", description = "launches master") with ClusterService with LazyLogging {
  import ClusterService._

  var appConfPath             = opt[String](name="app-conf-path", default = "etc/application.conf", description = "path of externally provided application.conf")
  var progressingTaskTimeout  = opt[Int](useEnv = true, default = 60*60, description = "timeout for a task progression in seconds")
  var pendingTaskTimeout      = opt[Int](useEnv = true, default = 3*24, description = "timeout for a pending task in hours")
  var masterId                = opt[String](useEnv = true, default = "master", name="master-id", description = "Unique identifier of this master node")

  def run(): Unit = {
    val appConfPathOpt = Option(new File(appConfPath)).filter(_.exists)
    logger.info(s"Starting master $masterId hostAddress $hostAddress and seed nodes : ${seedNodes.mkString("\n","\n","\n")}")
    appConfPathOpt.foreach( f => logger.info(s"App configuration loaded from ${f.getAbsolutePath}") )
    val system = buildClusterSystem(hostAddress, seedNodes, seedNodes.size, appConfPathOpt)
    clusterSingletonActorRef(Master.Config(masterId, progressingTaskTimeout.seconds, pendingTaskTimeout.hours), system)
    system.whenTerminated.onComplete(_ => System.exit(0))(ExecutionContext.Implicits.global)
    sys.addShutdownHook(Await.result(system.terminate(), 10.seconds))
  }
}