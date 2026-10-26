package services

import domain.*
import munit.FunSuite
import realtime.*

final class GameCommandSpec extends FunSuite:
  test("executes a complete turn through the command service"):
    val service = RoomService()
    val (room, artist) = service.create("Alice").toOption.get
    val (joinedRoom, player) = service.join(room.code, "Bob").toOption.get

    val started = service.execute(room.code, StartGame(artist.id)).toOption.get
    service.execute(room.code, ChooseDifficulty(artist.id, WordDifficulty.Hard))
    val drawing = service.beginDrawing(room.code).toOption.get
    val confirmed = service
      .execute(room.code, ConfirmPlayer(artist.id, player.id, elapsedSeconds = 0))
      .toOption
      .get
    val completed = service.execute(room.code, EndTurn(artist.id)).toOption.get
    val next = service.execute(room.code, NextTurn(artist.id)).toOption.get

    assertEquals(started.phase, GamePhase.WordSelection)
    assertEquals(drawing.phase, GamePhase.Drawing)
    assertEquals(confirmed.confirmedPlayerIds, Vector(player.id))
    assertEquals(completed.phase, GamePhase.TurnComplete)
    assertEquals(next.phase, GamePhase.WordSelection)
    assertEquals(next.artist.map(_.id), Some(player.id))
    assert(joinedRoom.players.nonEmpty)

  test("only the room owner can start a game"):
    val service = RoomService()
    val (room, artist) = service.create("Alice").toOption.get
    val (_, player) = service.join(room.code, "Bob").toOption.get

    assertEquals(service.execute(room.code, StartGame(player.id)), Left(GameError.NotRoomOwner))

  test("rejects malformed commands"):
    assertEquals(
      GameCommand.parse("not-json"),
      Left(GameError.InvalidCommand("Message must be valid JSON"))
    )
