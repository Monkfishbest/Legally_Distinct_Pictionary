package domain

import munit.FunSuite

final class GameRulesSpec extends FunSuite:
  private val players = Vector(
    Player("artist", "Artist"),
    Player("player-2", "Player 2"),
    Player("player-3", "Player 3")
  )

  private def drawingState(difficulty: WordDifficulty = WordDifficulty.Medium): GameState =
    val lobby = GameState("game-1", players)
    val wordSelection = GameRules.startGame(lobby).toOption.get
    GameRules.chooseDifficulty(wordSelection, difficulty).toOption.get

  test("starts a game in word selection phase"):
    val result = GameRules.startGame(GameState("game-1", players))

    assertEquals(result.map(_.phase), Right(GamePhase.WordSelection))

  test("requires at least two players"):
    val result = GameRules.startGame(GameState("game-1", Vector(players.head)))

    assertEquals(result, Left(GameError.NotEnoughPlayers))

  test("difficulty affects confirmed player score"):
    val simple = GameRules.confirmPlayer(
      drawingState(WordDifficulty.Simple),
      "player-2",
      elapsedSeconds = 0
    ).toOption.get
    val hard = GameRules.confirmPlayer(
      drawingState(WordDifficulty.Hard),
      "player-2",
      elapsedSeconds = 0
    ).toOption.get

    assert(hard.players.find(_.id == "player-2").get.score > simple.players.find(_.id == "player-2").get.score)

  test("cannot confirm the same player twice"):
    val state = drawingState()
    val confirmed = GameRules.confirmPlayer(state, "player-2", elapsedSeconds = 10).toOption.get

    assertEquals(
      GameRules.confirmPlayer(confirmed, "player-2", elapsedSeconds = 15),
      Left(GameError.PlayerAlreadyConfirmed("player-2"))
    )

  test("artist receives participation points when a player is confirmed"):
    val confirmed = GameRules.confirmPlayer(drawingState(), "player-2", elapsedSeconds = 10).toOption.get
    val finished = GameRules.endTurn(confirmed).toOption.get

    assertEquals(finished.phase, GamePhase.TurnComplete)
    assertEquals(finished.players.find(_.id == "artist").get.score, 75)
