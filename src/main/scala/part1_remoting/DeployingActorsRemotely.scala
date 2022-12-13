package part1_remoting

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, AddressFromURIString, Deploy, Props, Terminated}
import akka.remote.RemoteScope
import akka.routing.FromConfig
import com.typesafe.config.ConfigFactory

object DeployingActorsRemotely_LocalApp extends App {

    val system = ActorSystem("LocalActorSystem", ConfigFactory.load("part1_remoting/deployingActorsRemotely")
    .getConfig("localApp"))

    val simpleActor = system.actorOf(Props[SimpleActor], "remoteActor") // /user/remoteActor
    simpleActor ! "hello remote actor"

    // actor path of a remotely deployed actor.
    println(simpleActor)
    //akka://RemoteActorSystem@localhost:2552/user/remoteActor    EXPECTED, but....
    //akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/remoteActor
    //val selection = system.actorSelection("akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/remoteActor")


    // #1 programmatic remote actor deployment
    val remoteSystemAddress: Address = AddressFromURIString("akka://RemoteActorSystem@localhost:2552")
    val remotelyDeployedActor = system.actorOf(
        Props[SimpleActor].withDeploy(
            Deploy(scope = RemoteScope(remoteSystemAddress))
        )
    )
    remotelyDeployedActor ! "Hi remotely deployed Actor"

    // #2 routers with routees deployed remotely
    val poolRouter = system.actorOf(FromConfig.props(Props[SimpleActor]), "myRouterWithRemoteChildren")
    (1 to 10).map(i => s"message$i").foreach(poolRouter ! _)


    // #3 watching remote actors
    class ParentActor extends Actor with ActorLogging {
        override def receive: Receive = {
            case "create" =>
                log.info("Creating remote child")
                val child = context.actorOf(Props[SimpleActor], "remoteChild")
                context.watch(child)
            case Terminated(ref) =>
                log.warning(s"Child $ref terminated")
        }
    }

    val parentActor = system.actorOf(Props[ParentActor], "watcher")
    parentActor ! "create"
    //    Thread.sleep(1000)
    //    system.actorSelection("akka://RemoteActorSystem@localhost:2552/remote/akka/LocalActorSystem@localhost:2551/user/watcher/remoteChild") ! PoisonPill
    /** Remote failure detector / The Phi accrual failure detector
     * - When we comment the PoisonPill, The Child termination message is still sent.
     *
     * - Actor systems send heartbeat messages to each other once a connection is established (to validate & maintain connection).
     *      -start a connection b/w 2 actors.
     *      (1.)by sending a message to remote actor (2.) deploying a remote actor on an actor system "AS".
     *
     * - If a heartbeat message times out, its reach score(PHI score) for the connection to remote Actor System
     *      starts to increase and keeps increasing the longer it takes heartbeat to arrive and the more heartbeat are
     *      timed out.
     *
     * - If reach score(PHI score) passes a threshold and default value for threshold is 10, the connection is
     *      considered quarantined = unreachable. Once that happens, the local AS sends Terminated messages to
     *      remote death watchers of remote actors.
     *
     * - If a local actor is subscribed to a remote actor deathwatch and remote AS is unreachable/quarantined,
     *      then the local AS will send the local actor a terminated message.
     *
     * - If remote AS wants to reconnect with local AS, It must be restarted to establish connection.
     */
}

object DeployingActorsRemotely_RemoteApp extends App{
    val system = ActorSystem("RemoteActorSystem", ConfigFactory.load("part1_remoting/deployingActorsRemotely")
        .getConfig("remoteApp"))
}
