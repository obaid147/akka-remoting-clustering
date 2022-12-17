package part3_advanced_clustering

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import com.typesafe.config.ConfigFactory

import java.util.{Date, UUID}
import scala.concurrent.duration.DurationInt
import scala.util.Random

case class OysterCard(id: String, amount: Double)
case class EntryAttempt(oysterCard: OysterCard, date: Date)
case object EntryAccepted
case class EntryRejected(reason: String)

// passivate message
case object TerminateValidator

/////////////////////////////////
// Actors
/////////////////////////////////
object Turnstile {
    def props(validator: ActorRef): Props = Props(new Turnstile(validator))
}

class Turnstile(validator: ActorRef) extends Actor with ActorLogging {
    override def receive: Receive = {
        case o: OysterCard => validator ! EntryAttempt(o, new Date)
        case EntryAccepted => log.info("Green: Please pass")
        case EntryRejected(reason) => log.info(s"RED: $reason")
    }
}

class OysterCardValidator extends Actor with ActorLogging {
    /* stores lots and lots of data...(MEMORY HEAVY)*/
    override def preStart(): Unit = {
        super.preStart()
        log.info("Validator starting...........")
        context.setReceiveTimeout(10.seconds) // if no message received for 10 seconds, ReceiveTimeout => Passivate
    }

    override def receive: Receive = {
        case EntryAttempt(card @ OysterCard(id, amount), _) =>
                log.info(s"Validating $card")
            if(amount > 2.5) sender() ! EntryAccepted
            else sender() ! EntryRejected(s"[$id] not enough funds, Please top up")

        case ReceiveTimeout =>
            context.parent ! Passivate(TerminateValidator)

        case TerminateValidator => // I am sure that I won't be contacted again, so safe to stop
            context.stop(self)
    }
}

/////////////////////////////////
// Sharding settings
/////////////////////////////////
object TurnstileSettings {
    val numberOfShards = 10 // use 10x no.of nodes in your cluster.
    val numberOfEntities = 100 // 10x no.of shards

    val extractEntityId: ShardRegion.ExtractEntityId = {
        case attempt @ EntryAttempt(OysterCard(cardId, _), _) =>
            val entityId = cardId.hashCode.abs % numberOfEntities
            (entityId.toString, attempt)
    }

    val extractShardId: ShardRegion.ExtractShardId = {
        case EntryAttempt(OysterCard(cardId, _), _) =>
            val shardId = cardId.hashCode.abs % numberOfShards
            shardId.toString
        case ShardRegion.StartEntity(entityId) =>
            (entityId.toLong % numberOfShards).toString
    }
    /**
    * Message -> extractEntityId 44
    *         -> extractShardId 9
    * Everytime in future, a message extracts the EntityID, It must also extract the same shardID
    * Devise your function the way (extractEntityId, extractShardId) pair stays consistent throughout the entire application.
    *

     * There must be NO two messages M1 and M2 for which
     * extractEntityId(M1) == extractEntityId(M2) and extractShardId(M1) != extractShardId(M2)
     *
     * Let's say we got:-
     * M1 -> E73, S9
     * M2 -> E37, S10
     *
     * If Shards S9 and S10 operate on different nodes, they will create Entity E37 twice in the cluster.
     * THIS IS BAD
     *
     * If we use ase ShardRegion.StartEntity(entityId), We will place additional restriction on the way we associate
     *  entityID's and shardID's.
     *
     * if for an entityId get shardId   entityId -> shardId,
     * then FORALL messages M, if extractEntityId(M) = entityId, then extractShardId(M) MUST BE shardId
     *
     * If we set ClusterSharding Settings .withRememberEntities(true) then this message ShardRegion.StartEntity(entityId)
     * will be sent to shardRegion when it takes responsibility of new shard which was previously moved
     */
}


/////////////////////////////////
// Cluster Nodes
/////////////////////////////////
class TubeStation(port: Int, numberOfTurnstiles: Int) extends App {
    val config = ConfigFactory.parseString(
        s"""
          |akka.remote.artery.canonical.port = $port""".stripMargin)
        .withFallback(ConfigFactory.load("part3_advanced_clustering/clusterShardingExample.conf"))

    val system = ActorSystem("RTJVMCluster", config)

    // setting up cluster sharding
    val validatorShardRegionRef: ActorRef = ClusterSharding(system).start(
        typeName = "OysterCardValidator",
        entityProps = Props[OysterCardValidator],
        settings = ClusterShardingSettings(system).withRememberEntities(true),
        extractEntityId = TurnstileSettings.extractEntityId,
        extractShardId = TurnstileSettings.extractShardId
    )

    val turnstiles = (1 to numberOfTurnstiles).map(_ => system.actorOf(Turnstile.props(validatorShardRegionRef)))

    Thread.sleep(10000)

    for (_ <- 1 to 1000) {
        val randomTurnstileIndex = Random.nextInt(numberOfTurnstiles)
        val randomTurnstile = turnstiles(randomTurnstileIndex)

        randomTurnstile ! OysterCard(UUID.randomUUID().toString, Random.nextDouble() * 10)
        Thread.sleep(200)
    }
}

object PiccadillyCircus extends TubeStation(2551, 10)
object Westminster extends TubeStation(2561, 5)
object CharCross extends TubeStation(2571, 15)
