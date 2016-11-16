## mawex
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
```
docker-compose -f docker/example-remote-client.yml up master workers client
```

### how-to ( W.I.P. )

```
"net.globalwebindex" %% "mawex-api" % "0.01-SNAPSHOT"
```

Provides you with API and `LocalMasterProxy` or `RemoteMasterProxy` actors, see `./example`
Then all you need to do is supplying your fat Jar to a Worker which is currently done by extending docker image and copying the fat jar on classpath.
This is going to change in future by downloading jars from a repository.
