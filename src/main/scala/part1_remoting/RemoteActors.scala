package part1_remoting

import akka.actor.{Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, Identify, Props}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object RemoteActors extends App {

    val localSystem = ActorSystem("LocalSystem",
        ConfigFactory.load("part1_remoting/remoteActors.conf"))
    /*val remoteSystem = ActorSystem("RemoteSystem",
        ConfigFactory.load("part1_remoting/remoteActors.conf").getConfig("remoteSystem"))*/

    val localSimpleActor = localSystem.actorOf(Props[SimpleActor], "localSimpleActor")
    /*val remoteSimpleActor = remoteSystem.actorOf(Props[SimpleActor], "remoteSimpleActor")*/

    localSimpleActor ! "Hey local actor"
    /*remoteSimpleActor ! "Hey remote actor"*/

    /** --- send message to remoteSimpleActor from local ---*/

    /**Method #1
    using actorSelection => querying for actorPath & ActorSys will respond with actorRef which we can send msg to.*/

    // PATH of actor akka://RemoteSystem@localhost:2552/     PATH of actor within ActorSystem user/remoteSimpleActor
    val remoteActorSelection = localSystem.actorSelection("akka://RemoteSystem@localhost:2552/user/remoteSimpleActor")
    remoteActorSelection ! "hello from \"local\" JVM"

    /** Method 2
     * resolve the actorSelection to an actor ref*/

    import localSystem.dispatcher
    implicit val timeout = Timeout(3.seconds)
    val remoteActorRef: Future[ActorRef] = remoteActorSelection.resolveOne()
    remoteActorRef.onComplete {
        case Success(actorRef) => actorRef ! "I have resolved you in future"
        case Failure(ex) => println(s"Failed to resolve the remote actor:- $ex")
    }

    /** Method 3
     * Actor identification via messages
     * - actor resolver will ask for an actor selection from the local actor system
     * - actor resolver will send a Identify(42) to the actor selection
     * - the remote actor will AUTOMATICALLY respond with ActorIdentity(42, actorRef)
     * - the actor resolver is free to use the remote actorRef*/
    class ActorResolver extends Actor with ActorLogging {
        override def preStart(): Unit = {
            val selection = context.actorSelection("akka://RemoteSystem@localhost:2552/user/remoteSimpleActor")
            selection ! Identify(11) // special message sent to any actor and actor will respond with actorIdentity message.
        }
        override def receive: Receive = {
            case ActorIdentity(11, Some(actorRef)) =>
                actorRef ! "Thank you for identifying yourself"
        }
    }
    localSystem.actorOf(Props[ActorResolver], "localActorResolver")
}

object RemoteActors_Remote extends App {
    val remoteSystem = ActorSystem("RemoteSystem",
        ConfigFactory.load("part1_remoting/remoteActors.conf").getConfig("remoteSystem"))

    val remoteSimpleActor = remoteSystem.actorOf(Props[SimpleActor], "remoteSimpleActor")

    remoteSimpleActor ! "Hey remote actor"
}
