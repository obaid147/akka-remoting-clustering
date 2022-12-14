package part2_clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, MemberJoined, MemberRemoved, MemberUp, UnreachableMember}
import com.typesafe.config.ConfigFactory

class ClusterSubscriber extends Actor with ActorLogging {
    val cluster = Cluster(context.system)

    override def preStart(): Unit = { // Subscribing to cluster event
        cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
        /*
        1. cluster.subscribe(self):-
            ActorSystem which is part of cluster will send classOf[MemberEvent], classOf[UnreachableMember] messages to
            ClusterSubscriber actor whenever a new MemberEvent was triggered.
            So, whenever a new node joins, this actor will receive an appropriate MemberEvent as a message.(handled in receive)
        2. cluster.subscribe(InitialStateAsEvents):-
            If the ActorSystem containing this ClusterSubscriber actor starts joining the cluster later,
            The ClusterSubscriber will receive same messages as if it had started joining at the beginning of the cluster.
            We can also use InitialStateAsSnapshot if we just want the state of cluster at the moment of joining.
        3. classOf[MemberEvent], classOf[UnreachableMember], .... many more...:-
            These will be sent to us as messages from the ActorSystem which is part of the cluster
         */
    }

    override def postStop(): Unit = cluster.unsubscribe(self)

    override def receive: Receive = {
        case MemberJoined(member) =>
            log.info(s"new member in town: ${member.address}")
        case MemberUp(member) if member.hasRole("numberCruncher") =>
            log.info(s"Hello brother: ${member.address}")
        case MemberUp(member) =>
            log.info(s"Let's welcome newest member: ${member.address}")
        // It welcomes itself and new members also welcome old members. use ClusteringBasics_ManualRegistration
        case MemberRemoved(member, previousStatus) =>
            log.info(s"Poor ${member.address} was removed from $previousStatus")
        case UnreachableMember(member) =>
            log.info(s"Uh oh, member ${member.address} is unreachable")
        case m: MemberEvent =>
            log.info(s"another member event: $m")
    }
}
object ClusteringBasics extends App {

    def startCluster(ports: List[Int]): Unit = ports.foreach { port =>
            val config = ConfigFactory.parseString(
                s"""
                   |akka.remote.artery.canonical.port=$port
                   |""".stripMargin).withFallback(ConfigFactory.load("part2_clustering/clusteringBasics.conf"))

        val system = ActorSystem("RTJVMCluster", config) // All the ActorSystems in a cluster must have same name
        system.actorOf(Props[ClusterSubscriber], "clusterSubscriber")

        // Thread.sleep(2000)
        }

    startCluster(List(2551, 2552, 0)) // port=0 means system will allocate a port for us.
    // startCluster(List(2552, 2551, 0)) // [2552] couldn't join seed node ... will try again
}

object ClusteringBasics_ManualRegistration extends App {

    val system = ActorSystem("RTJVMCluster",
        ConfigFactory.load("part2_clustering/clusteringBasics.conf")
            .getConfig("manualRegistration"))

    val cluster = Cluster(system) // Cluster.get(system)
    def joinExistingCluster = cluster.joinSeedNodes(List(
        Address("akka", "RTJVMCluster", "localhost", 2551), // akka://RTJVMCluster@localhost:2551
        Address("akka", "RTJVMCluster", "localhost", 2552)
        // equivalent with AddressFromURIString("akka://RTJVMCluster@localhost:2552")
    ))

    def joinExistingNode = //
        cluster.join(Address("akka", "RTJVMCluster", "localhost", 50466))

    def joinMyself = //
        cluster.join(Address("akka", "RTJVMCluster", "localhost", 2555))

    //    joinExistingNode
    //    joinMyself
    joinExistingCluster

    system.actorOf(Props[ClusterSubscriber], "clusterSubscriber")

}
