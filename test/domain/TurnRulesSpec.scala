package domain

import munit.FunSuite

final class TurnRulesSpec extends FunSuite:
  private val players = Vector(
    Player("artist", "Artist"),
    Player("player-2", "Player 2")
  )

  test("rotates the artist and returns to word selection"):
    val state = GameState(
      "game-1",
      players,
      phase = GamePhase.TurnComplete,
      artistIndex = 0,
      turnNumber = 1
    )

    val next = GameRules.nextTurn(state).toOption.get

    assertEquals(next.phase, GamePhase.WordSelection)
    assertEquals(next.artist.map(_.id), Some("player-2"))
    assertEquals(next.turnNumber, 2)
