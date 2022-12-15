package part2_clustering

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import com.typesafe.config.ConfigFactory

object ChatDomain {
    case class ChatMessage(nickname: String, contents: String)
    case class UserMessage(contents: String)
    case class EnterRoom(fullAddress: String, nickname: String)
}

object ChatActor {
    def props(nickname: String, port: Int): Props = Props(new ChatActor(nickname, port))
}

class ChatActor(nickname: String, port: Int) extends Actor with ActorLogging {
    import ChatDomain._
    val cluster = Cluster(context.system)

    override def preStart(): Unit =
        cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent])

    override def postStop(): Unit = cluster.unsubscribe(self)

    override def receive: Receive = online(Map())

    def online(chatRoom: Map[String, String]): Receive = {

        case MemberUp(member) =>
            // TODO send a special enter room msg to ChatActor deployed on new node(actorSelection)
            val remoteChatActorSelection = getChatActor(member.address.toString)
            remoteChatActorSelection ! EnterRoom(s"${self.path.address}@localhost:$port", nickname)

        case MemberRemoved(member, _) =>
            // TODO REMOVE member
            val remoteNickname = chatRoom(member.address.toString)
            log.info(s"$remoteNickname left the room.")
            context.become(online(chatRoom - member.address.toString))

        case EnterRoom(remoteAddress, remoteNickname) =>
            // TODO add member
            if(remoteNickname != nickname) {
                log.info(s"$remoteNickname entered the room.")
                context.become(online(chatRoom + (remoteAddress -> remoteNickname)))
            }

        case UserMessage(contents) =>
            // TODO broadcast contents as ChatMessages to the rest of cluster members sent from a person
            chatRoom.keys.foreach{ remoteAddressAsString =>
                getChatActor(remoteAddressAsString) ! ChatMessage(nickname, contents)
            }

        case ChatMessage(remoteNickname, contents) =>
            log.info(s"[$remoteNickname]:- $contents")
    }

    def getChatActor(memberAddress: String) =
        context.actorSelection(s"$memberAddress/user/chatActor")
}


class ChatApp(nickname: String, port: Int) extends App {
    import ChatActor._
    import ChatDomain._

    val config = ConfigFactory.parseString(
        s"""
          |akka.remote.artery.canonical.port=$port
          |""".stripMargin).withFallback(ConfigFactory.load("part2_clustering/clusterChat.conf"))

    val system = ActorSystem("RTJVMCluster", config)
    val chatActor = system.actorOf(props(nickname, port), "chatActor")

    scala.io.Source.stdin.getLines().foreach { line =>
        chatActor ! UserMessage(line)
    }
}

object Alice extends ChatApp("Alice", 2551)
object Bob extends ChatApp("Bob", 2552)
object Martin extends ChatApp("Martin", 2553)