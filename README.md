## mawex

[![DroneCI](https://drone.globalwebindex.net/api/badges/GlobalWebIndex/mawex/status.svg)](https://drone.globalwebindex.net/GlobalWebIndex/mawex)
[![mawex-api](https://api.bintray.com/packages/l15k4/GlobalWebIndex/mawex-api/images/download.svg) ](https://bintray.com/l15k4/GlobalWebIndex/mawex-api/_latestVersion)
[![mawex-core](https://api.bintray.com/packages/l15k4/GlobalWebIndex/mawex-core/images/download.svg) ](https://bintray.com/l15k4/GlobalWebIndex/mawex-core/_latestVersion)

```
+-------------------------------------------------------------------------------------+
|                                   Pod x1 t2.small                                   |
| +------------------+ +------------------+ +------------------+ +------------------+ |
| | Consumer Group C | | Consumer Group D | | Consumer Group E | | Consumer Group F | |
| | +--------------+ | | +--------------+ | | +--------------+ | | +--------------+ | |
| | | Worker       | | | | Worker       | | | | Worker       | | | | Worker       | | |
| | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | |
| | |  |Executor|  | | | |  |Executor|  | | | |  |Executor|  | | | |  |Executor|  | | |
| | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | |
| | +--------------+ | | +--------------+ | | +--------------+ | | +--------------+ | |
| +------------------+ +------------------+ +------------------+ +------------------+ |
+-------------------------------------------------------------------------------------+

+----------------------+                  +----------+         +----------------------+
|   Pod y1 m4.xlarge   |                  |Master    |         |   Pod y2 m4.xlarge   |
| +------------------+ |               +-----------+ |         | +------------------+ |
| | Consumer Group A | |               |Master     | |         | | Consumer Group A | |
| | +--------------+ | |             +-----------+ | |         | | +--------------+ | |
| | | Worker       | | |    Task B   |Master     | | | Task A  | | | Worker       | | |
| | |  +--------+  | | |     +-------+           | | +-----------> |  +--------+  | | |
| | |  |Executor|  | | |     |       | Singleton | +-+         | | |  |Executor|  | | |
| | |  +--------+  | | |     |       |           +-+           | | |  +--------+  | | |
| | +--------------+ | |     |   +---+---------^-+             | | +--------------+ | |
| +------------------+ |     |   |             |               | +------------------+ |
| +------------------+ |     |  +v-----------+ |               | +------------------+ |
| | Consumer Group B | |     |  |            | |               | | Consumer Group B | |
| | +--------------+ | |     |  |Result Topic| |               | | +--------------+ | |
| | | Worker       | | |     |  |            | |               | | | Worker       | | |
| | |  +--------+  | <-------+  +----+-------+ |               | | |  +--------+  | | |
| | |  |Executor|  | | |             |         |               | | |  |Executor|  | | |
| | |  +--------+  | | |             |    +----+---+           | | |  +--------+  | | |
| | +--------------+ | |             |    |        |           | | +--------------+ | |
| +------------------+ |             +----> Client |           | +------------------+ |
+----------------------+                  |        |           +----------------------+
                                          +--------+

+-------------------------------------------------------------------------------------+
|                                    Pod x2 t2.small                                  |
| +------------------+ +------------------+ +------------------+ +------------------+ |
| | Consumer Group C | | Consumer Group D | | Consumer Group E | | Consumer Group F | |
| | +--------------+ | | +--------------+ | | +--------------+ | | +--------------+ | |
| | | Worker       | | | | Worker       | | | | Worker       | | | | Worker       | | |
| | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | |
| | |  |Executor|  | | | |  |Executor|  | | | |  |Executor|  | | | |  |Executor|  | | |
| | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | | |  +--------+  | | |
| | +--------------+ | | +--------------+ | | +--------------+ | | +--------------+ | |
| +------------------+ +------------------+ +------------------+ +------------------+ |
+-------------------------------------------------------------------------------------+


^^ Executor
  +-----------------------------------+
  |SandBox (current-jvm/forked-jvm/k8s|
  | +-------------------------------+ |
  | |Executor                       | |
  | | +---------------------------+ | |
  | | |Command                    | | |
  | | |     Business logic        | | |
  | | +---------------------------+ | |
  | +-------------------------------+ |
  +-----------------------------------+
```

Lightweight library for distributed task scheduling based on Master Worker Executor model.

System is designed for :
 1. redundancy
    - Consumer Groups
        - workers that belong to the same group are pulling tasks that belong to that group
        - one group of a kind should be always present at least on 2 machines
 2. high availability
    - Master is a Akka cluster Singleton and persistent actor :
        - when one instance crashes another one takes over, work state would replay in case of a crash
        - use at least 3 nodes
 2. both horizontal and vertical scalability so that one can :
    - add more workers on the fly if tasks start coming more frequently
    - switch to bigger pods if workers need more resources, memory especially
    - pod is an isolated execution environment for Workers, if you have one mission critical worker and 6 expendable workers,
      you would create a dedicated pod for the mission critical worker and a second pod for those 6 workers,
      this way the mission critical worker is not affected by runtime of the others
 2. resiliency :
    - tasks are executed by Executor in so called sandbox which is either current JVM process, forked JVM process or as a k8s job
    - the 2 latter sandboxes minimize possibility of system failures, however they are slower and not suitable for many small jobs
        - run small jobs in current JVM process and long running resource intensive jobs either in forked JVM process or as a k8s job

Tasks that are going to Consumer Groups A,B or C,D,E,F are similarly resource demanding so they can live on
the same machines. Master executes tasks sequentially within a Pod, never concurrently, so that you can
use very small instances cost-effectively as this allows you to utilize machine's resources on almost 100%
if you run a lot of microservices.

### Use case

This system was designed for an ETL pipeline orchestrated by [saturator](https://github.com/GlobalWebIndex/saturator)
which is a FSM that sees pipeline as layered DAG and saturates/satisfies dependencies by executing ETL tasks on Mawex.

### Production cluster behavior

When you deploy a cluster of Master and Worker nodes, you are free to stop/start Workers freely, they just register and unregister from Master
and tasks would be pending until corresponding Worker shows up. This is good for deployment purposes because Workers are being developed continually
and you can ship them without restarting whole cluster, even if you don't have redundant workers, task would wait in Master until corresponding Worker asks for it.

### Sandboxing

Actual jobs should be executed in isolated environment or cloud and never affect runtime of the actual Worker, there are three implementations :
 1. local-jvm
     - task is executed within the current jvm process
     - recommended for lightweight tasks with simple computation, nothing with high memory requirements or cpu heavy
 2. forked-jvm
     - task is executed within forked jvm process
     - recommended for heavy tasks as the worker jvm process is not directly affected
 3. k8s job
     - task is executed as Kubernetes Job
     - recommended for heavy tasks

Local SandBox is dummy, it just executes tasks in current jvm process.
Remote SandBox has either `ForkingExecutorSupervisor` or `K8JobExecutorSupervisor` which is supervising remote actor system in a forked jvm process or a k8s job.

### mawex persistence and clustering details :

Mawex akka persistence is tested with [akka-persistence-dynamodb](https://github.com/akka/akka-persistence-dynamodb) and redis plugin
but running it on different storages like cassandra is just a matter of configuration changes, choose a storage based on
amount and throughput of tasks that are being submitted to it.
By default it uses the Oldest node auto-downing strategy for split-brain cases because the cluster is solely about Singleton with actor residing on the oldest node.

### Example setup

```
$ cd docker
$ docker-compose up
```

### how-to ( W.I.P. )

```
Resolver.bintrayRepo("l15k4", "GlobalWebIndex")

"net.globalwebindex" %% "mawex-api" % "x.y.z" // In case you want to use mawex remotely via `RemoteMasterProxy`
"net.globalwebindex" %% "mawex-core" % "x.y.z" // In case you want to use mawex within your actor system programatically via `LocalMasterProxy`, see `./example`
```