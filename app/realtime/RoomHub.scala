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
                            def run(): Unit =
                              val stillCountingDown = roomService.currentState(roomCode).exists(currentState =>
                                currentState.phase == GamePhase.Countdown && currentState.turnNumber == state.turnNumber
                              )
                              if stillCountingDown then broadcast(roomCode, GameEvent.countdown(5 - second))
                          , second, TimeUnit.SECONDS
                        )
                      countdownScheduler.schedule(
                        new Runnable:
                          def run(): Unit =
                            // A countdown is asynchronous.  It may outlive the turn that
                            // created it (for example when a client ends/rematches quickly),
                            // so it must never advance whatever phase happens to be current.
                            val current = roomService.currentState(roomCode)
                            if current.exists(currentState =>
                              currentState.phase == GamePhase.Countdown && currentState.turnNumber == state.turnNumber
                            ) then roomService.beginDrawing(roomCode).foreach: updated =>
                              val turnDuration = updated.settings.durationForTurn(updated.turnNumber)
                              val deadline = System.currentTimeMillis() + turnDuration.toLong * 1000L
                              turnDeadlines.put(roomCode.trim.toUpperCase, deadline)
                              broadcast(roomCode, GameEvent.state(updated))
                              broadcast(roomCode, GameEvent.timer(deadline))
                              countdownScheduler.schedule(
                                new Runnable:
                                  def run(): Unit =
                                    val stillDrawing = roomService.currentState(roomCode).exists(state =>
                                      state.phase == GamePhase.Drawing && state.turnNumber == updated.turnNumber
                                    )
                                    if stillDrawing then
                                      broadcast(roomCode, GameEvent.timeout)
                                      countdownScheduler.schedule(
                                        new Runnable:
                                          def run(): Unit = endTimedOutTurn(
                                            roomCode,
                                            updated.artist.map(_.id).getOrElse(""),
                                            updated.turnNumber
                                          )
                                        , 5, TimeUnit.SECONDS
                                      )
                            , updated.settings.durationForTurn(updated.turnNumber).toLong, TimeUnit.SECONDS
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
            if turnHasExpired(roomCode) then ()
            else roomService.applyDrawing(roomCode, drawing).foreach(operation => broadcast(roomCode, GameEvent.drawingOperation(operation)))

          case TimePing(_, clientSentAt) =>
            sendToPlayer(roomCode, playerId, GameEvent.timePong(clientSentAt))

          case _: DrawingSnapshotRequest =>
            roomService.drawingSnapshot(roomCode) match
              case Left(error) => sendToPlayer(roomCode, playerId, GameEvent.error(error))
              case Right(operations) => sendToPlayer(roomCode, playerId, GameEvent.drawingSnapshot(operations))

          case _ =>
            val commandForExecution = authenticatedCommand match
              case ConfirmPlayer(_, targetPlayerId, _) =>
                ConfirmPlayer(playerId, targetPlayerId, elapsedSeconds(roomCode))
              case EndTurn(_, _) =>
                EndTurn(playerId, elapsedSeconds(roomCode))
              case other => other
            val canStart = authenticatedCommand match
              case _: StartGame => roomService.find(roomCode).exists(room => room.players.forall(player => connectedPlayerIds(roomCode).contains(player.id)))
              case _ => true
            if !canStart then broadcast(roomCode, GameEvent.error(GameError.PlayersNotConnected))
            else roomService.execute(roomCode, commandForExecution) match
              case Left(error) => broadcast(roomCode, GameEvent.error(error))
              case Right(state) =>
                broadcast(roomCode, GameEvent.state(state))
                if state.phase == GamePhase.Lobby then
                  turnDeadlines.remove(roomCode.trim.toUpperCase)
                  selectedWords.remove(roomCode)
                  selectedWordIds.remove(roomCode)
                  roomService.find(roomCode).foreach(broadcastRoom)
                if state.phase == GamePhase.WordSelection && state.turnNumber == 1 then
                  selectedWordIds.remove(roomCode)
                if state.phase == GamePhase.WordSelection then
                  sendWordOptions(roomCode, state.artist.map(_.id))
                if state.phase == GamePhase.TurnComplete then
                  turnDeadlines.remove(roomCode.trim.toUpperCase)
                  selectedWords.get(roomCode).foreach(word => broadcast(roomCode, GameEvent.turnReveal(word, state.confirmedPlayerIds.nonEmpty)))
                  countdownScheduler.schedule(
                    new Runnable:
                      def run(): Unit =
                        val stillComplete = roomService.currentState(roomCode).exists(current =>
                          current.phase == GamePhase.TurnComplete && current.turnNumber == state.turnNumber
                        )
                        if stillComplete then
                          roomService.execute(roomCode, NextTurn(state.artist.map(_.id).getOrElse(""))) match
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
      state.confirmedPlayerIds.nonEmpty || turnHasExpired(roomCode)

  private def turnHasExpired(roomCode: String): Boolean =
    turnDeadlines.get(roomCode.trim.toUpperCase).exists(_ <= System.currentTimeMillis())

  private def elapsedSeconds(roomCode: String): Int =
    val normalizedRoomCode = roomCode.trim.toUpperCase
    val duration = roomService.currentState(normalizedRoomCode)
      .map(state => state.settings.durationForTurn(state.turnNumber))
      .orElse(roomService.find(normalizedRoomCode).map(_.settings.durationForTurn(1)))
      .getOrElse(0)
    val deadline = turnDeadlines.getOrElse(normalizedRoomCode, System.currentTimeMillis())
    (duration - math.ceil((deadline - System.currentTimeMillis()).max(0).toDouble / 1000.0).toInt).max(0)

  private def endTimedOutTurn(roomCode: String, artistId: String, turnNumber: Int): Unit =
    val stillDrawing = roomService.currentState(roomCode).exists(state =>
      state.phase == GamePhase.Drawing && state.turnNumber == turnNumber && state.artist.exists(_.id == artistId)
    )
    if !stillDrawing then return
    val duration = roomService.currentState(roomCode).map(state => state.settings.durationForTurn(state.turnNumber)).getOrElse(0)
    roomService.execute(roomCode, EndTurn(artistId, duration)) match
      case Right(state) =>
        turnDeadlines.remove(roomCode.trim.toUpperCase)
        broadcast(roomCode, GameEvent.state(state))
        selectedWords.get(roomCode).foreach(word => broadcast(roomCode, GameEvent.turnReveal(word, correct = false)))
        countdownScheduler.schedule(
          new Runnable:
            def run(): Unit =
              val stillComplete = roomService.currentState(roomCode).exists(state =>
                state.phase == GamePhase.TurnComplete && state.turnNumber == turnNumber
              )
              if stillComplete then roomService.execute(roomCode, NextTurn(artistId)).foreach: nextState =>
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
