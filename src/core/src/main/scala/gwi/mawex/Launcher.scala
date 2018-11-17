package gwi.mawex

import gwi.mawex.executor.ExecutorCmd
import gwi.mawex.master.MasterCmd
import gwi.mawex.worker.WorkerCmd
import org.backuity.clist._

object Launcher {
  def main(args: Array[String]): Unit =
    Cli.parse(args)
      .withProgramName("mawex")
      .withCommands(MasterCmd, WorkerCmd, ExecutorCmd)
      .foreach(_.run())
}
