package realtime

import domain.*
import play.api.libs.json.*

import scala.util.control.NonFatal

sealed trait GameCommand:
  def playerId: String

  def asPlayer(authenticatedPlayerId: String): GameCommand = this match
    case StartGame(_) => StartGame(authenticatedPlayerId)
    case ChooseDifficulty(_, difficulty, wordId) => ChooseDifficulty(authenticatedPlayerId, difficulty, wordId)
    case ConfirmPlayer(_, targetPlayerId, elapsedSeconds) =>
      ConfirmPlayer(authenticatedPlayerId, targetPlayerId, elapsedSeconds)
    case EndTurn(_, _) => EndTurn(authenticatedPlayerId)
    case NextTurn(_) => NextTurn(authenticatedPlayerId)
    case Rematch(_) => Rematch(authenticatedPlayerId)
    case DrawingOperationCommand(_, operationType, points, color, width) =>
      DrawingOperationCommand(authenticatedPlayerId, operationType, points, color, width)
    case DrawingSnapshotRequest(_) => DrawingSnapshotRequest(authenticatedPlayerId)
    case TimePing(_, clientSentAt) => TimePing(authenticatedPlayerId, clientSentAt)

final case class StartGame(playerId: String) extends GameCommand
final case class ChooseDifficulty(playerId: String, difficulty: WordDifficulty, wordId: Option[String] = None) extends GameCommand
final case class ConfirmPlayer(playerId: String, targetPlayerId: String, elapsedSeconds: Int) extends GameCommand
final case class EndTurn(playerId: String, elapsedSeconds: Int = 0) extends GameCommand
final case class NextTurn(playerId: String) extends GameCommand
final case class Rematch(playerId: String) extends GameCommand
final case class DrawingOperationCommand(
    playerId: String,
    operationType: String,
    points: Vector[DrawPoint],
    color: String,
    width: Double
) extends GameCommand
final case class DrawingSnapshotRequest(playerId: String) extends GameCommand
final case class TimePing(playerId: String, clientSentAt: Long) extends GameCommand

object GameCommand:
  private final case class PlayerPayload(playerId: String)
  private final case class DifficultyPayload(playerId: String, difficulty: String, wordId: Option[String])
  private final case class ConfirmPayload(playerId: String, targetPlayerId: String, elapsedSeconds: Int)
  private final case class DrawingPayload(
      playerId: String,
      operationType: String,
      points: Vector[DrawPoint],
      color: String,
      width: Double
  )
  private final case class TimePingPayload(playerId: String, clientSentAt: Long)

  private given Reads[PlayerPayload] = Json.reads[PlayerPayload]
  private given Reads[DifficultyPayload] = Json.reads[DifficultyPayload]
  private given Reads[ConfirmPayload] = Json.reads[ConfirmPayload]
  private given Reads[DrawPoint] = Json.reads[DrawPoint]
  private given Reads[DrawingPayload] = Json.reads[DrawingPayload]
  private given Reads[TimePingPayload] = Json.reads[TimePingPayload]

  def parse(message: String): Either[GameError, GameCommand] =
    try
      Json.parse(message).validate[JsObject].asEither.left.map:
        errors => GameError.InvalidCommand(JsError.toJson(errors).toString)
      .flatMap: payload =>
        (payload \ "type").asOpt[String] match
          case None => Left(GameError.InvalidCommand("Missing command type"))
          case Some("game.start") =>
            payload.validate[PlayerPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid game.start payload"))
              .map(value => StartGame(value.playerId))
          case Some("turn.choose_difficulty") =>
            payload.validate[DifficultyPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid turn.choose_difficulty payload"))
              .flatMap(value => parseDifficulty(value.playerId, value.difficulty, value.wordId))
          case Some("turn.confirm_player") =>
            payload.validate[ConfirmPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid turn.confirm_player payload"))
              .map(value => ConfirmPlayer(value.playerId, value.targetPlayerId, value.elapsedSeconds))
          case Some("turn.end") =>
            payload.validate[PlayerPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid turn.end payload"))
              .map(value => EndTurn(value.playerId))
          case Some("time.ping") =>
            payload.validate[TimePingPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid time.ping payload"))
              .map(value => TimePing(value.playerId, value.clientSentAt))
          case Some("turn.next") =>
            payload.validate[PlayerPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid turn.next payload"))
              .map(value => NextTurn(value.playerId))
          case Some("game.rematch") =>
            payload.validate[PlayerPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid game.rematch payload"))
              .map(value => Rematch(value.playerId))
          case Some("drawing.operation") =>
            payload.validate[DrawingPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid drawing.operation payload"))
              .map(value =>
                DrawingOperationCommand(
                  value.playerId,
                  value.operationType,
                  value.points,
                  value.color,
                  value.width
                )
              )
          case Some("drawing.snapshot_request") =>
            payload.validate[PlayerPayload].asEither
              .left.map(_ => GameError.InvalidCommand("Invalid drawing.snapshot_request payload"))
              .map(value => DrawingSnapshotRequest(value.playerId))
          case Some(other) => Left(GameError.InvalidCommand(s"Unknown command type: $other"))
    catch
      case NonFatal(_) => Left(GameError.InvalidCommand("Message must be valid JSON"))

  private def parseDifficulty(playerId: String, value: String, wordId: Option[String]): Either[GameError, GameCommand] =
    WordDifficulty.values.find(_.toString.equalsIgnoreCase(value)) match
      case Some(difficulty) => Right(ChooseDifficulty(playerId, difficulty, wordId))
      case None => Left(GameError.InvalidCommand(s"Unknown difficulty: $value"))
