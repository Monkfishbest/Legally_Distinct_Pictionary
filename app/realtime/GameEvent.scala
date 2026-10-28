package realtime

import domain.*
import play.api.libs.json.*

object GameEvent:
  def roomState(room: Room, connectedPlayerIds: Set[String] = Set.empty): String =
    Json.obj(
      "type" -> "room.state",
      "room" -> Json.obj(
        "code" -> room.code,
        "ownerId" -> room.ownerId,
        "connectedPlayerIds" -> connectedPlayerIds.toVector,
        "players" -> room.players.map: player =>
          Json.obj("id" -> player.id, "name" -> player.name, "score" -> player.score),
        "settings" -> Json.obj(
          "turnDurationSeconds" -> room.settings.turnDurationSeconds,
          "turnsPerPlayer" -> room.settings.turnsPerPlayer,
          "reduceTimerEachTurn" -> room.settings.reduceTimerEachTurn
        )
      )
    ).toString

  def drawingOperation(operation: DrawingOperation): String =
    Json.obj(
      "type" -> "drawing.operation",
      "operation" -> operationJson(operation)
    ).toString

  def drawingSnapshot(operations: Vector[DrawingOperation]): String =
    Json.obj(
      "type" -> "drawing.snapshot",
      "operations" -> operations.map(operationJson)
    ).toString

  def wordAssigned(word: Word): String =
    Json.obj(
      "type" -> "turn.word_assigned",
      "word" -> word.displayText,
      "difficulty" -> word.difficulty.toString
    ).toString

  def countdown(seconds: Int): String =
    Json.obj("type" -> "turn.countdown", "seconds" -> seconds).toString

  def timer(deadlineEpochMs: Long): String =
    Json.obj("type" -> "turn.timer", "deadlineEpochMs" -> deadlineEpochMs).toString

  def timePong(clientSentAt: Long): String =
    Json.obj("type" -> "time.pong", "clientSentAt" -> clientSentAt, "serverEpochMs" -> System.currentTimeMillis()).toString

  def timeout: String =
    Json.obj("type" -> "turn.timeout").toString

  def wordOptions(words: Vector[Word]): String =
    Json.obj(
      "type" -> "turn.word_options",
      "options" -> words.map(word => Json.obj(
        "id" -> word.id,
        "displayText" -> word.displayText,
        "difficulty" -> word.difficulty.toString
      ))
    ).toString

  def turnReveal(word: Word, correct: Boolean): String =
    Json.obj(
      "type" -> "turn.reveal",
      "word" -> word.displayText,
      "difficulty" -> word.difficulty.toString,
      "correct" -> correct
    ).toString

  def state(state: GameState): String =
    Json.obj(
      "type" -> "game.state",
      "state" -> Json.obj(
        "id" -> state.id,
        "phase" -> state.phase.toString,
        "turnNumber" -> state.turnNumber,
        "artistId" -> state.artist.map(_.id),
        "difficulty" -> state.difficulty.map(_.toString),
        "confirmedPlayerIds" -> state.confirmedPlayerIds,
        "players" -> state.players.map: player =>
          Json.obj("id" -> player.id, "name" -> player.name, "score" -> player.score)
      )
    ).toString

  def error(error: GameError): String =
    Json.obj(
      "type" -> "error",
      "error" -> (error match
        case GameError.InvalidCommand(reason) => reason
        case GameError.InvalidPhase(expected, actual) => s"Expected phase $expected, but was $actual"
        case GameError.PlayerNotFound(id) => s"Player not found: $id"
        case GameError.NotRoomOwner => "Only the room owner can start the game"
        case GameError.NotArtist => "Only the current artist can do that"
        case GameError.ArtistCannotBeConfirmed => "The artist cannot be confirmed"
        case GameError.PlayerAlreadyConfirmed(id) => s"Player already confirmed: $id"
        case GameError.DifficultyNotSelected => "A difficulty must be selected first"
        case GameError.NotEnoughPlayers => "At least two players are required"
        case GameError.PlayersNotConnected => "Every player must connect their phone before the game starts"
        case GameError.InvalidSettings => "The game settings are invalid"
        case GameError.NoWordsAvailable(difficulty) => s"No words available for $difficulty"
        case GameError.InvalidWord(reason) => reason
        case GameError.DuplicateWord => "That custom word already exists"
        case GameError.DatabaseUnavailable => "The word database is temporarily unavailable"
        case GameError.InvalidRoomCode => "Room not found"
        case GameError.PlayerNameRequired => "Player name must be 1-30 characters"
        case GameError.DuplicatePlayerName => "That name is already in use in this room"
        case GameError.RoomFull => "Room is full"
        case GameError.DrawingNotAllowed => "Drawing is not allowed right now"
        case GameError.InvalidDrawingOperation(reason) => reason
        case GameError.PersistenceFailure(reason) => s"Persistence failed: $reason"
        case GameError.ImageStorageFailure(reason) => s"Image storage failed: $reason"
        case GameError.ImageNotFound => "Image not found"
        case GameError.ImageTooLarge => "Image is too large")
    ).toString

  private def operationJson(operation: DrawingOperation): JsObject =
    Json.obj(
      "id" -> operation.id,
      "turnNumber" -> operation.turnNumber,
      "playerId" -> operation.playerId,
      "operationType" -> operation.operationType.toString,
      "points" -> operation.points.map(point => Json.obj("x" -> point.x, "y" -> point.y)),
      "color" -> operation.color,
      "width" -> operation.width,
      "sequence" -> operation.sequence
    )
