package realtime

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import domain.*
import persistence.WordRepository
import services.RoomService

import javax.inject.*
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.{Executors, TimeUnit}

@Singleton
final class RoomHub @Inject() (
    executionContext: ExecutionContext,
    roomService: RoomService,
    wordRepository: WordRepository
):
  private given ExecutionContext = executionContext
  private val connections = TrieMap.empty[String, Set[(String, ActorRef)]]
  private val selectedWords = TrieMap.empty[String, Word]
  private val countdownScheduler = Executors.newScheduledThreadPool(1)

  def flow(roomCode: String, playerId: String): Flow[String, String, Any] =
    val source = Source
      .actorRef[String](256, OverflowStrategy.dropHead)
      .watchTermination() { (connection, completed) =>
        add(roomCode, playerId, connection)
        completed.onComplete(_ => remove(roomCode, connection))(using executionContext)
        connection
      }

    val sink = Sink.foreach[String](message => handleMessage(roomCode, playerId, message))
    Flow.fromSinkAndSourceCoupled(sink, source)

  def broadcastRoom(room: Room): Unit =
    broadcast(room.code, GameEvent.roomState(room, connectedPlayerIds(room.code)))

  private def add(roomCode: String, playerId: String, connection: ActorRef): Unit =
    connections.updateWith(roomCode):
      case Some(existing) => Some(existing + ((playerId, connection)))
      case None => Some(Set((playerId, connection)))
    roomService.find(roomCode).foreach: room =>
      connection ! GameEvent.roomState(room, connectedPlayerIds(roomCode))
      broadcastRoom(room)
    roomService.currentState(roomCode).foreach(state => connection ! GameEvent.state(state))
    roomService.drawingSnapshot(roomCode).foreach:
      case operations if operations.nonEmpty => connection ! GameEvent.drawingSnapshot(operations)
      case _ => ()

  private def remove(roomCode: String, connection: ActorRef): Unit =
    connections.updateWith(roomCode):
      case Some(existing) if existing.size > 1 => Some(existing.filterNot(_._2 == connection))
      case Some(_) => None
      case None => None
    roomService.find(roomCode).foreach(broadcastRoom)

  private def connectedPlayerIds(roomCode: String): Set[String] =
    connections.get(roomCode).fold(Set.empty[String])(_.map(_._1))

  private def broadcast(roomCode: String, message: String): Unit =
    connections.get(roomCode).foreach(_.foreach { case (_, connection) => connection ! message })

  private def handleMessage(roomCode: String, playerId: String, message: String): Unit =
    GameCommand.parse(message) match
      case Left(error) => broadcast(roomCode, GameEvent.error(error))
      case Right(command) =>
        val authenticatedCommand = command.asPlayer(playerId)
        authenticatedCommand match
          case ChooseDifficulty(_, difficulty, wordId) =>
            wordRepository.findEligible(roomCode, difficulty).map { words =>
              val selected = wordId.flatMap(id => words.find(_.id == id))
              selected match {
                case Some(word) =>
                  roomService.execute(roomCode, authenticatedCommand, Some(word)) match {
                    case Left(error) => broadcast(roomCode, GameEvent.error(error))
                    case Right(state) =>
                      selectedWords.put(roomCode, word)
                      broadcast(roomCode, GameEvent.state(state))
                      sendToPlayer(roomCode, playerId, GameEvent.wordAssigned(word))
                      broadcast(roomCode, GameEvent.countdown(5))
                      countdownScheduler.schedule(
                        new Runnable:
                          def run(): Unit =
                            roomService.beginDrawing(roomCode).foreach(updated => broadcast(roomCode, GameEvent.state(updated)))
                        , 5, TimeUnit.SECONDS
                      )
                  }
                case None => broadcast(roomCode, GameEvent.error(GameError.InvalidWord("The selected word is no longer available")))
              }
            }.recover {
              case _: Throwable => broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("Word selection failed")))
            }

          case drawing: DrawingOperationCommand =>
            roomService.applyDrawing(roomCode, drawing) match
              case Left(error) => broadcast(roomCode, GameEvent.error(error))
              case Right(operation) => broadcast(roomCode, GameEvent.drawingOperation(operation))

          case _: DrawingSnapshotRequest =>
            roomService.drawingSnapshot(roomCode) match
              case Left(error) => sendToPlayer(roomCode, playerId, GameEvent.error(error))
              case Right(operations) => sendToPlayer(roomCode, playerId, GameEvent.drawingSnapshot(operations))

          case _ =>
            val canStart = authenticatedCommand match
              case _: StartGame => roomService.find(roomCode).exists(room => room.players.forall(player => connectedPlayerIds(roomCode).contains(player.id)))
              case _ => true
            if !canStart then broadcast(roomCode, GameEvent.error(GameError.PlayersNotConnected))
            else roomService.execute(roomCode, authenticatedCommand) match
              case Left(error) => broadcast(roomCode, GameEvent.error(error))
              case Right(state) =>
                broadcast(roomCode, GameEvent.state(state))
                if state.phase == GamePhase.WordSelection then
                  sendWordOptions(roomCode, state.artist.map(_.id))
                if state.phase == GamePhase.TurnComplete then
                  selectedWords.get(roomCode).foreach(word => broadcast(roomCode, GameEvent.turnReveal(word, state.confirmedPlayerIds.nonEmpty)))

  private def sendToPlayer(roomCode: String, playerId: String, message: String): Unit =
    connections.get(roomCode).foreach(_.foreach:
      case (connectedPlayerId, connection) if connectedPlayerId == playerId => connection ! message
      case _ => ()
    )

  private def sendWordOptions(roomCode: String, playerId: Option[String]): Unit =
    playerId.foreach: artistId =>
      Future
        .sequence(WordDifficulty.values.toVector.map(difficulty => wordRepository.findEligible(roomCode, difficulty).map(_.headOption)))
        .map(options => sendToPlayer(roomCode, artistId, GameEvent.wordOptions(options.flatten)))
        .recover { case _ => broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("Word options could not be loaded"))) }
