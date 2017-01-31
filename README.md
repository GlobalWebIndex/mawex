## mawex

[![Build Status](https://travis-ci.org/GlobalWebIndex/mawex.svg?branch=master)](https://travis-ci.org/GlobalWebIndex/mawex)

```
+--------------------+                             +--------------------+
|                    |                             |                    |
| Consumer Group B   |                             | Consumer Group A   |
|                    |         +--------+          |                    |
|  +--------------+  | Task B  |        | Task A   |  +--------------+  |
|  |              |  <---------+ Master +---------->  |              |  |
|  | Worker       |  |         |        |          |  | Worker       |  |
|  |  +--------+  |  |    +----+------^-+          |  |  +--------+  |  |
|  |  |Executor|  |  |    |           |            |  |  |Executor|  |  |
|  |  +--------+  |  | +--v---------+ |            |  |  +--------+  |  |
|  +--------------+  | |            | |            |  +--------------+  |
|  +--------------+  | |Result Topic| |  Task A    |  +--------------+  |
|  |              |  | |            | |  Task B    |  |              |  |
|  | Worker       |  | +--+---------+ |            |  | Worker       |  |
|  |  +--------+  |  |    |           |            |  |  +--------+  |  |
|  |  |Executor|  |  |    |    +------+-+          |  |  |Executor|  |  |
|  |  +--------+  |  |    |    |        |          |  |  +--------+  |  |
|  +--------------+  |    +----> Client |          |  +--------------+  |
+--------------------+         |        |          +--------------------+
                               +--------+
```

Lightweight library for distributed task scheduling based on Master Worker Executor model.

It deals with following problems :
 1. scalability
    - add more workers on the fly if tasks are getting more heavy or they start coming more frequently
 2. resiliency
    - tasks are executed by Executor in forked JVM process which minimizes possibility of system failures
    - Master is a Akka cluster Singleton - when one instance crashes another one takes over
    - Master is a persistent Akka actor - work state would replay in case of a crash
 3. load balancing
    - consumer groups paradigm taken from Kafka

It is based on [activator-akka-distributed-workers](https://github.com/typesafehub/activator-akka-distributed-workers) project, most of the credit goes there !!!

### mawex in action :

Mawex akka persistence is tested with redis only because it is the best fit for mawex unless
high amount of micro tasks are being submitted to it in which case something like cassandra would be a better fit.
It uses Kryo serialization because event log is persisted only temporarily and it would be deleted on new deploy.
By default it uses the Oldest node auto-downing strategy for split-brain cases because the cluster is solely about Singleton with actor residing on the oldest node.

```
$ cd docker
docker-compose up
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
