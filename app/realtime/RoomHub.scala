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
  private val connections = TrieMap.empty[String, Set[(String, ActorRef, Boolean)]]
  private val selectedWords = TrieMap.empty[String, Word]
  private val selectedWordIds = TrieMap.empty[String, Set[String]]
  private val selectionInFlight = TrieMap.empty[String, Boolean]
  private val turnDeadlines = TrieMap.empty[String, Long]
  private val countdownScheduler = Executors.newScheduledThreadPool(1)

  def flow(roomCode: String, playerId: String, phoneConnection: Boolean): Flow[String, String, Any] =
    val source = Source
      .actorRef[String](256, OverflowStrategy.dropHead)
      .watchTermination() { (connection, completed) =>
        add(roomCode, playerId, phoneConnection, connection)
        completed.onComplete(_ => remove(roomCode, connection))(using executionContext)
        connection
      }

    val sink = Sink.foreach[String](message => handleMessage(roomCode, playerId, message))
    Flow.fromSinkAndSourceCoupled(sink, source)

  def broadcastRoom(room: Room): Unit =
    broadcast(room.code, GameEvent.roomState(room, connectedPlayerIds(room.code)))

  private def add(roomCode: String, playerId: String, phoneConnection: Boolean, connection: ActorRef): Unit =
    connections.updateWith(roomCode):
      case Some(existing) => Some(existing + ((playerId, connection, phoneConnection)))
      case None => Some(Set((playerId, connection, phoneConnection)))
    roomService.find(roomCode).foreach: room =>
      connection ! GameEvent.roomState(room, connectedPlayerIds(roomCode))
      broadcastRoom(room)
    roomService.currentState(roomCode).foreach: state =>
      connection ! GameEvent.state(state)
      if state.phase == GamePhase.Drawing then
        turnDeadlines.get(roomCode.trim.toUpperCase).foreach(deadline => connection ! GameEvent.timer(deadline))
      if state.phase == GamePhase.WordSelection && state.artist.exists(_.id == playerId) then
        sendWordOptions(roomCode, state.artist.map(_.id))
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
    connections.get(roomCode).fold(Set.empty[String])(_.collect { case (playerId, _, true) => playerId })

  private def broadcast(roomCode: String, message: String): Unit =
    connections.get(roomCode).foreach(_.foreach { case (_, connection, _) => connection ! message })

  private def handleMessage(roomCode: String, playerId: String, message: String): Unit =
    GameCommand.parse(message) match
      case Left(error) => broadcast(roomCode, GameEvent.error(error))
      case Right(command) =>
        val authenticatedCommand = command.asPlayer(playerId)
        authenticatedCommand match
          case ChooseDifficulty(_, difficulty, wordId) =>
            val normalizedRoomCode = roomCode.trim.toUpperCase
            val canSelect = roomService.currentState(normalizedRoomCode).exists(state =>
              state.phase == GamePhase.WordSelection && state.artist.exists(_.id == playerId)
            ) && selectionInFlight.putIfAbsent(normalizedRoomCode, true).isEmpty
            if !canSelect then
              broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("A word is already being selected")))
            else wordRepository.findEligible(roomCode, difficulty).map { words =>
              val usedWordIds = selectedWordIds.getOrElse(roomCode, Set.empty)
              val availableWords = words.filterNot(word => usedWordIds.contains(word.id))
              val selected = wordId.flatMap(id => availableWords.find(_.id == id)).orElse(availableWords.headOption)
              selected match {
                case Some(word) =>
                  roomService.execute(roomCode, authenticatedCommand, Some(word)) match {
                    case Left(error) => broadcast(roomCode, GameEvent.error(error))
                    case Right(state) =>
                      selectedWords.put(roomCode, word)
                      selectedWordIds.update(roomCode, usedWordIds + word.id)
                      broadcast(roomCode, GameEvent.state(state))
                      sendToPlayer(roomCode, playerId, GameEvent.wordAssigned(word))
                      broadcast(roomCode, GameEvent.countdown(5))
                      (1 to 4).foreach: second =>
                        countdownScheduler.schedule(
                          new Runnable:
                            def run(): Unit = broadcast(roomCode, GameEvent.countdown(5 - second))
                          , second, TimeUnit.SECONDS
                        )
                      countdownScheduler.schedule(
                        new Runnable:
                          def run(): Unit =
                            roomService.beginDrawing(roomCode).foreach: updated =>
                              val deadline = System.currentTimeMillis() + updated.settings.turnDurationSeconds.toLong * 1000L
                              turnDeadlines.put(roomCode.trim.toUpperCase, deadline)
                              broadcast(roomCode, GameEvent.state(updated))
                              broadcast(roomCode, GameEvent.timer(deadline))
                              countdownScheduler.schedule(
                                new Runnable:
                                  def run(): Unit = endTimedOutTurn(roomCode, updated.artist.map(_.id).getOrElse(""))
                                , updated.settings.turnDurationSeconds.toLong, TimeUnit.SECONDS
                              )
                        , 5, TimeUnit.SECONDS
                      )
                  }
                case None => broadcast(roomCode, GameEvent.error(GameError.InvalidWord("The selected word is no longer available")))
              }
            }.recover {
              case _: Throwable => broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("Word selection failed")))
            }.andThen { case _ => selectionInFlight.remove(normalizedRoomCode) }

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
            else if authenticatedCommand.isInstanceOf[EndTurn] && !canEndTurn(roomCode) then
              broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("The artist must keep drawing until time runs out or someone guesses")))
            else roomService.execute(roomCode, authenticatedCommand) match
              case Left(error) => broadcast(roomCode, GameEvent.error(error))
              case Right(state) =>
                broadcast(roomCode, GameEvent.state(state))
                if state.phase == GamePhase.WordSelection && state.turnNumber == 1 then
                  selectedWordIds.remove(roomCode)
                if state.phase == GamePhase.WordSelection then
                  sendWordOptions(roomCode, state.artist.map(_.id))
                if state.phase == GamePhase.TurnComplete then
                  turnDeadlines.remove(roomCode.trim.toUpperCase)
                  selectedWords.get(roomCode).foreach(word => broadcast(roomCode, GameEvent.turnReveal(word, state.confirmedPlayerIds.nonEmpty)))
                  countdownScheduler.schedule(
                    new Runnable:
                      def run(): Unit = roomService.execute(roomCode, NextTurn(state.artist.map(_.id).getOrElse(""))) match
                        case Right(nextState) =>
                          broadcast(roomCode, GameEvent.state(nextState))
                          if nextState.phase == GamePhase.WordSelection then sendWordOptions(roomCode, nextState.artist.map(_.id))
                        case Left(_) => ()
                    , 5, TimeUnit.SECONDS
                  )

  private def sendToPlayer(roomCode: String, playerId: String, message: String): Unit =
    connections.get(roomCode).foreach(_.foreach:
      case (connectedPlayerId, connection, _) if connectedPlayerId == playerId => connection ! message
      case _ => ()
    )

  private def canEndTurn(roomCode: String): Boolean =
    roomService.currentState(roomCode).exists: state =>
      state.confirmedPlayerIds.nonEmpty

  private def endTimedOutTurn(roomCode: String, artistId: String): Unit =
    roomService.execute(roomCode, EndTurn(artistId)) match
      case Right(state) =>
        turnDeadlines.remove(roomCode.trim.toUpperCase)
        broadcast(roomCode, GameEvent.state(state))
        selectedWords.get(roomCode).foreach(word => broadcast(roomCode, GameEvent.turnReveal(word, correct = false)))
        countdownScheduler.schedule(
          new Runnable:
            def run(): Unit = roomService.execute(roomCode, NextTurn(artistId)).foreach: nextState =>
              broadcast(roomCode, GameEvent.state(nextState))
              if nextState.phase == GamePhase.WordSelection then sendWordOptions(roomCode, nextState.artist.map(_.id))
          , 5, TimeUnit.SECONDS
        )
      case Left(_) => ()

  private def sendWordOptions(roomCode: String, playerId: Option[String]): Unit =
    playerId.foreach: artistId =>
      val usedWordIds = selectedWordIds.getOrElse(roomCode, Set.empty)
      Future
        .sequence(WordDifficulty.values.toVector.map: difficulty =>
          wordRepository.findEligible(roomCode, difficulty).map(_.find(word => !usedWordIds.contains(word.id)))
        )
        .map(options => sendToPlayer(roomCode, artistId, GameEvent.wordOptions(options.flatten)))
        .recover { case _ => broadcast(roomCode, GameEvent.error(GameError.InvalidCommand("Word options could not be loaded"))) }
