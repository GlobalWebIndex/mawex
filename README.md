## mawex

[![Build Status](https://travis-ci.org/GlobalWebIndex/mawex.svg?branch=master)](https://travis-ci.org/GlobalWebIndex/mawex)

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
  +-------------------------------+
  |SandBox (host JVM / Forked JVM)|
  | +---------------------------+ |
  | |Executor                   | |
  | | +-----------------------+ | |
  | | |Command                | | |
  | | |     Business logic    | | |
  | | +-----------------------+ | |
  | +---------------------------+ |
  +-------------------------------+
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
 2. resiliency :
    - tasks are executed by Executor in forked JVM process, so called sandbox, which minimizes possibility of system failures

Tasks that are going to Consumer Groups A,B or C,D,E,F are similarly resource demanding so they can live on
the same machines. Master executes tasks sequentially within a Pod, never concurrently, so that you can
use very small instances cost-effectively as this allows you to utilize machine's resources on almost 100%
if you run a lot of microservices.

### Use case

This system was designed for an ETL pipeline orchestrated by [saturator](https://github.com/GlobalWebIndex/saturator)
which is a FSM that sees pipeline as layered DAG and saturates/satisfies dependencies by executing ETL tasks on Mawex.

### mawex in action :

Mawex akka persistence is tested [akka-persistence-dynamodb](https://github.com/akka/akka-persistence-dynamodb) and redis plugin
but running it on different storages like cassandra is just a matter of configuration changes, choose a storage based on
amount and throughput of tasks that are being submitted to it in which case something like cassandra would be a better fit.
It uses Kryo serialization because event log is persisted only temporarily and it would be deleted on new deploy.
By default it uses the Oldest node auto-downing strategy for split-brain cases because the cluster is solely about Singleton with actor residing on the oldest node.

### Production cluster behavior

When you deploy a cluster of Master and Worker nodes, you are free to stop/start Workers freely, they just register and unregister from Master
and tasks would be pending until corresponding Worker shows up. This is good for deployment purposes because Workers are being developed continually
and you can ship them without restarting whole cluster, even if you don't have redundant workers, task would wait in Master until corresponding Worker asks for it.

```
$ cd docker
$ docker-compose -f ${plugin}.yml -f mawex.yml up
```

### how-to ( W.I.P. )

```
"net.globalwebindex" %% "mawex-api" % "x.y.z" // In case you want to use mawex remotely via `RemoteMasterProxy`
"net.globalwebindex" %% "mawex-core" % "x.y.z" // In case you want to use mawex within your actor system programatically via `LocalMasterProxy`, see `./example`
```
or
```
dependsOn(ProjectRef(uri("https://github.com/GlobalWebIndex/mawex.git#vx.y.x"), "mawex-api"))
dependsOn(ProjectRef(uri("https://github.com/GlobalWebIndex/mawex.git#vx.y.x"), "mawex-core"))
```

Then all you need to do is supplying your fat Jar to a Worker which is currently done by extending docker image and copying the fat jar on classpath.
This is going to change in future by downloading jars from a repository.
