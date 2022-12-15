package part3_advanced_clustering

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.concurrent.duration.DurationInt

/*
  A small payment service - CENTRALIZED (single instance of this system through out the entire cluster)
   - maintaining a well-defined transaction ordering
   - interacting with legacy systems
   - etc
 */

case class Order(items: List[String], total: Double)
case class Transaction(orderId: Int, txnId: String, amount: Double)

class PaymentSystem extends Actor with ActorLogging {
    override def receive: Receive = {
        case t: Transaction =>
            log.info(s"Validating transaction $t") // complex business logic here...
        case m => log.info(s"Received unknown message: $m")
    }
}

class PaymentSystemNode(port: Int, shouldStartSingleton: Boolean = true) extends App {
    val config = ConfigFactory.parseString(
        s"""
          |akka.remote.artery.canonical.port=$port
          |""".stripMargin)
        .withFallback(ConfigFactory.load("part3_advanced_clustering/clusterSingletonExample"))

    val system = ActorSystem("JVMCluster", config)

    if(shouldStartSingleton)
        system.actorOf(
            ClusterSingletonManager.props(
                singletonProps = Props[PaymentSystem],
                terminationMessage = PoisonPill,
                ClusterSingletonManagerSettings(system)
            ),
            "paymentSystem"
        )
}


object Node1 extends PaymentSystemNode(2551)
object Node2 extends PaymentSystemNode(2552)
object Node3 extends PaymentSystemNode(2553, false)
/*
When we start nodes and then OnlineShopCheckout, The messages are delivered to Node1.
When we kill Node1, messages are delivered to Node 2.
When we kill Node2 also, messages are delivered to dead letter because flag is set to false in Node3.
Node3 does not start the cluster singleton manager
*/


// client
class OnlineShopCheckout(paymentSystem: ActorRef) extends Actor with ActorLogging {
    //var orderId = 0

    override def receive: Receive = order(0)
    def order(orderId: Int): Receive = {
        case Order(_, totalAmount) =>
            log.info(s"Received order number $orderId for amount $totalAmount, sending transaction to validate")
            val newTransaction = Transaction(orderId, UUID.randomUUID().toString, totalAmount)
            paymentSystem ! newTransaction
            context.become(order(orderId + 1))
    }
}

object OnlineShopCheckout {
    def props(paymentSystem: ActorRef): Props = Props(new OnlineShopCheckout(paymentSystem))
}

object PaymentSystemClient extends App {
    val config = ConfigFactory.parseString(
        """
          |akka.remote.artery.canonical.port = 0
        """.stripMargin)
        .withFallback(ConfigFactory.load("part3_advanced_clustering/clusterSingletonExample.conf"))
    val system = ActorSystem("JVMCluster", config)

    val proxy = system.actorOf(
        ClusterSingletonProxy.props(
            singletonManagerPath = "/user/paymentSystem",
            settings = ClusterSingletonProxySettings(system)
        ),
        "paymentSystemProxy"
    )

    val onlineShopCheckout = system.actorOf(OnlineShopCheckout.props(proxy), "onlineShopCheckout")

    import system.dispatcher
    import scala.util.Random
    system.scheduler.schedule(5.seconds, 1.second, () => {
        val randomOrder = Order(List(), Random.nextDouble() * 100) // 0 to 100$
        onlineShopCheckout ! randomOrder
    })
}

