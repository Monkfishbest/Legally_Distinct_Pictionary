package services

import domain.*
import persistence.{GamePersistence, ImageStorage, NoopGamePersistence, NoopImageStorage, NoopTurnImageRepository, TurnImageRepository}

import javax.inject.Inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*
import org.slf4j.LoggerFactory

final class RoomService @Inject() (
    gamePersistence: GamePersistence,
    imageStorage: ImageStorage,
    turnImageRepository: TurnImageRepository
):
  private val rooms = new ConcurrentHashMap[String, Room]().asScala
  private val games = new ConcurrentHashMap[String, GameState]().asScala
  private val drawings = new ConcurrentHashMap[String, Vector[DrawingOperation]]().asScala
  private val drawingSequences = new ConcurrentHashMap[String, Long]().asScala
  private val drawingPersistenceExecutor = Executors.newSingleThreadExecutor((runnable: Runnable) =>
    val thread = new Thread(runnable, "drawing-persistence")
    thread.setDaemon(true)
    thread
  )
  private val logger = LoggerFactory.getLogger(classOf[RoomService])
  private val maxPlayers = 12

  loadPersistedState()

  private def loadPersistedState(): Unit =
    gamePersistence.loadRooms().foreach: persistedRooms =>
      persistedRooms.foreach(room => rooms.put(room.code, room))
    gamePersistence.loadGames().foreach: persistedGames =>
      persistedGames.foreach(game => games.put(game.id, game))
    gamePersistence.loadDrawings().foreach: persistedDrawings =>
      persistedDrawings.groupBy(_._1).foreach:
        case (gameId, entries) =>
          val operations = entries.map(_._2).sortBy(_.sequence)
          drawings.put(gameId, operations)
          operations.lastOption.foreach(operation => drawingSequences.put(gameId, operation.sequence))

  def create(playerName: String, settings: GameSettings = GameSettings()): Either[GameError, (Room, Player)] =
    validPlayerName(playerName).flatMap { name =>
      if settings.turnDurationSeconds <= 0 || settings.turnsPerPlayer <= 0 then Left(GameError.InvalidSettings)
      else
        val player = Player(UUID.randomUUID().toString, name)
        val room = Room(generateCode(), player.id, Vector(player), settings)
        gamePersistence.saveRoom(room).map { _ =>
          rooms.put(room.code, room)
          (room, player)
        }
    }

  def join(code: String, playerName: String): Either[GameError, (Room, Player)] =
    validPlayerName(playerName).flatMap { name =>
      rooms.get(code.trim.toUpperCase).toRight(GameError.InvalidRoomCode).flatMap { room =>
        Either.cond(room.players.size < maxPlayers, (), GameError.RoomFull).flatMap { _ =>
          val player = Player(UUID.randomUUID().toString, name)
          val updated = room.copy(players = room.players :+ player)
          gamePersistence.saveRoom(updated).map { _ =>
            rooms.put(room.code, updated)
            (updated, player)
          }
        }
      }
    }

  def find(code: String): Option[Room] = rooms.get(code.trim.toUpperCase)

  def hasPlayer(code: String, playerId: String): Boolean =
    find(code).exists(_.players.exists(_.id == playerId))

  def currentState(code: String): Option[GameState] =
    games.get(code.trim.toUpperCase)

  def beginDrawing(code: String): Either[GameError, GameState] =
    this.synchronized:
      val normalizedCode = code.trim.toUpperCase
      games.get(normalizedCode).toRight(GameError.InvalidRoomCode).flatMap: state =>
        Either.cond(state.phase == GamePhase.Countdown, (), GameError.InvalidPhase(GamePhase.Countdown, state.phase)).flatMap: _ =>
          val updated = state.copy(phase = GamePhase.Drawing)
          gamePersistence.saveGame(updated).map: _ =>
            games.put(normalizedCode, updated)
            updated

  def execute(
      code: String,
      command: realtime.GameCommand,
      selectedWord: Option[Word] = None
  ): Either[GameError, GameState] =
    this.synchronized:
      val normalizedCode = code.trim.toUpperCase
      val commandResult = for
        room <- rooms.get(normalizedCode).toRight(GameError.InvalidRoomCode)
        _ <- Either.cond(room.players.exists(_.id == command.playerId), (), GameError.PlayerNotFound(command.playerId))
        current = games.getOrElse(normalizedCode, GameState(normalizedCode, room.players, room.settings))
        updated <- applyCommand(room, current, command, selectedWord)
      yield (room, current, updated)

      commandResult.flatMap { case (room, current, updated) =>
          val updatedRoom = room.copy(players = updated.players)
          val persistenceResult = for
            _ <- gamePersistence.saveRoom(updatedRoom)
            _ <- gamePersistence.saveGame(updated)
            _ <- saveConfirmationIfNeeded(current, updated, command)
          yield updated

          persistenceResult.map { persistedState =>
            games.put(normalizedCode, persistedState)
            rooms.put(normalizedCode, updatedRoom)
            if persistedState.phase == GamePhase.WordSelection then
              drawings.remove(normalizedCode)
              drawingSequences.remove(normalizedCode)
            persistedState
          }
      }

  def applyDrawing(
      code: String,
      command: realtime.DrawingOperationCommand
  ): Either[GameError, DrawingOperation] =
    this.synchronized:
      val normalizedCode = code.trim.toUpperCase
      val operationResult = for
        room <- rooms.get(normalizedCode).toRight(GameError.InvalidRoomCode)
        _ <- Either.cond(room.players.exists(_.id == command.playerId), (), GameError.PlayerNotFound(command.playerId))
        state <- games.get(normalizedCode).toRight(GameError.InvalidCommand("Game has not started"))
        _ <- Either.cond(state.phase == GamePhase.Drawing, (), GameError.DrawingNotAllowed)
        _ <- Either.cond(state.artist.exists(_.id == command.playerId), (), GameError.NotArtist)
        operationType <- parseOperationType(command.operationType)
        _ <- validateDrawing(operationType, command)
      yield
        val sequence = drawingSequences.getOrElse(normalizedCode, 0L) + 1L
        DrawingOperation(
          UUID.randomUUID().toString,
          state.turnNumber,
          command.playerId,
          operationType,
          command.points,
          command.color,
          command.width,
          sequence
        )

      operationResult.flatMap: operation =>
        drawings.update(normalizedCode, drawings.getOrElse(normalizedCode, Vector.empty) :+ operation)
        drawingSequences.put(normalizedCode, operation.sequence)
        drawingPersistenceExecutor.execute(() =>
          gamePersistence.saveDrawing(normalizedCode, operation) match
            case Left(error) => logger.warn(s"Could not persist drawing operation ${operation.id}: $error")
            case Right(_) => ()
        )
        Right(operation)

  def drawingSnapshot(code: String): Either[GameError, Vector[DrawingOperation]] =
    find(code).toRight(GameError.InvalidRoomCode).map:
      _ => drawings.getOrElse(code.trim.toUpperCase, Vector.empty)

  def saveFinishedImage(
      code: String,
      playerId: String,
      turnNumber: Int,
      bytes: Array[Byte]
  ): Either[GameError, TurnImage] =
    this.synchronized:
      val normalizedCode = code.trim.toUpperCase
      for
        room <- rooms.get(normalizedCode).toRight(GameError.InvalidRoomCode)
        state <- games.get(normalizedCode).toRight(GameError.InvalidCommand("Game has not started"))
        _ <- Either.cond(room.players.exists(_.id == playerId), (), GameError.PlayerNotFound(playerId))
        _ <- Either.cond(state.phase == GamePhase.TurnComplete, (), GameError.InvalidPhase(GamePhase.TurnComplete, state.phase))
        _ <- Either.cond(state.turnNumber == turnNumber, (), GameError.InvalidCommand("Turn number does not match the active turn"))
        _ <- ensureArtist(state, playerId)
        _ <- Either.cond(bytes.nonEmpty, (), GameError.InvalidCommand("Image cannot be empty"))
        _ <- Either.cond(bytes.length <= 10 * 1024 * 1024, (), GameError.ImageTooLarge)
        storageKey <- imageStorage.save(normalizedCode, turnNumber, bytes)
        image = TurnImage(UUID.randomUUID().toString, normalizedCode, turnNumber, state.selectedWordId, storageKey, "image/png")
        _ <- turnImageRepository.save(image)
      yield image

  def readImage(storageKey: String): Either[GameError, Array[Byte]] = imageStorage.read(storageKey)

  def gallery(gameId: String): Either[GameError, Vector[GalleryItem]] = turnImageRepository.gallery(gameId)

  def loadImage(gameId: String, turnNumber: Int): Either[GameError, Option[(TurnImage, Array[Byte])]] =
    turnImageRepository.find(gameId, turnNumber).flatMap:
      case None => Right(None)
      case Some(image) => imageStorage.read(image.storageKey).map(bytes => Some((image, bytes)))

  private def applyCommand(
      room: Room,
      state: GameState,
      command: realtime.GameCommand,
      selectedWord: Option[Word]
  ): Either[GameError, GameState] =
    command match
      case _: realtime.StartGame if command.playerId != room.ownerId =>
        Left(GameError.NotRoomOwner)

      case realtime.StartGame(_) =>
        GameRules.startGame(state)

      case realtime.ChooseDifficulty(_, difficulty, _) =>
        ensureArtist(state, command.playerId).flatMap(_ =>
          GameRules
            .chooseDifficulty(state, difficulty)
            .map(_.copy(selectedWordId = selectedWord.map(_.id)))
        )

      case realtime.ConfirmPlayer(_, targetPlayerId, elapsedSeconds) =>
        ensureArtist(state, command.playerId).flatMap:
          _ => GameRules.confirmPlayer(state, targetPlayerId, elapsedSeconds.max(0))

      case realtime.EndTurn(_) =>
        ensureArtist(state, command.playerId).flatMap(_ => GameRules.endTurn(state))

      case realtime.NextTurn(_) =>
        ensureArtist(state, command.playerId).flatMap(_ => GameRules.nextTurn(state))

      case _: realtime.DrawingOperationCommand | _: realtime.DrawingSnapshotRequest =>
        Left(GameError.InvalidCommand("Drawing commands must use the drawing command handler"))

  private def ensureArtist(state: GameState, playerId: String): Either[GameError, Unit] =
    Either.cond(state.artist.exists(_.id == playerId), (), GameError.NotArtist)

  private def saveConfirmationIfNeeded(
      before: GameState,
      after: GameState,
      command: realtime.GameCommand
  ): Either[GameError, Unit] =
    command match
      case realtime.ConfirmPlayer(_, playerId, _) =>
        val beforeScore = before.players.find(_.id == playerId).map(_.score).getOrElse(0)
        val afterScore = after.players.find(_.id == playerId).map(_.score).getOrElse(beforeScore)
        gamePersistence.saveConfirmation(after.id, after.turnNumber, playerId, afterScore - beforeScore)
      case _ => Right(())

  private def parseOperationType(value: String): Either[GameError, DrawingOperationType] =
    DrawingOperationType.values.find(_.toString.equalsIgnoreCase(value)).toRight(
      GameError.InvalidDrawingOperation(s"Unknown operation type: $value")
    )

  private def validateDrawing(
      operationType: DrawingOperationType,
      command: realtime.DrawingOperationCommand
  ): Either[GameError, Unit] =
    operationType match
      case DrawingOperationType.Stroke if command.points.isEmpty =>
        Left(GameError.InvalidDrawingOperation("A stroke requires at least one point"))
      case _ if command.points.size > 5000 =>
        Left(GameError.InvalidDrawingOperation("A drawing operation has too many points"))
      case _ if command.width <= 0 || command.width > 100 =>
        Left(GameError.InvalidDrawingOperation("Stroke width must be between 0 and 100"))
      case _ if !command.color.matches("#[0-9a-fA-F]{6}") =>
        Left(GameError.InvalidDrawingOperation("Color must be a hex RGB value"))
      case _ => Right(())

  private def validPlayerName(name: String): Either[GameError, String] =
    val trimmed = name.trim
    Either.cond(trimmed.nonEmpty && trimmed.length <= 30, trimmed, GameError.PlayerNameRequired)

  private def generateCode(): String =
    var code = ""
    while code.isEmpty || rooms.contains(code) do
      code = UUID.randomUUID().toString.replace("-", "").take(6).toUpperCase
    code

object RoomService:
  def apply(): RoomService =
    new RoomService(new NoopGamePersistence(), new NoopImageStorage(), new NoopTurnImageRepository())
