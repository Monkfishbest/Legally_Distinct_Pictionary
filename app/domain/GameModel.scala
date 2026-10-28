package domain

enum GamePhase:
  case Lobby, WordSelection, Countdown, Drawing, TurnComplete, Complete

enum WordDifficulty:
  case Simple, Medium, Hard, Impossible

  def multiplier: BigDecimal = this match
    case Simple     => BigDecimal("1.0")
    case Medium     => BigDecimal("1.5")
    case Hard       => BigDecimal("2.0")
    case Impossible => BigDecimal("3.0")

enum WordSource:
  case BuiltIn, Custom

final case class Word(
    id: String,
    displayText: String,
    difficulty: WordDifficulty,
    source: WordSource,
    roomId: Option[String] = None,
    active: Boolean = true
):
  def normalizedText: String = Word.normalize(displayText)

final case class Player(
    id: String,
    name: String,
    score: Int = 0
)

final case class Room(
    code: String,
    ownerId: String,
    players: Vector[Player] = Vector.empty,
    settings: GameSettings = GameSettings()
)

final case class GameSettings(
    turnDurationSeconds: Int = 60,
    turnsPerPlayer: Int = 1,
    reduceTimerEachTurn: Boolean = false
):
  val minimumTurnDurationSeconds: Int = 10

  def durationForTurn(turnNumber: Int): Int =
    val reduction = if reduceTimerEachTurn then (turnNumber.max(1) - 1) * 10 else 0
    (turnDurationSeconds - reduction).max(minimumTurnDurationSeconds)

final case class GameState(
    id: String,
    players: Vector[Player],
    settings: GameSettings = GameSettings(),
    phase: GamePhase = GamePhase.Lobby,
    artistIndex: Int = 0,
    turnNumber: Int = 0,
    difficulty: Option[WordDifficulty] = None,
    selectedWordId: Option[String] = None,
    confirmedPlayerIds: Vector[String] = Vector.empty
):
  def artist: Option[Player] = players.lift(artistIndex)

object Word:
  def normalize(text: String): String =
    text.trim.toLowerCase.replaceAll("\\s+", " ")
