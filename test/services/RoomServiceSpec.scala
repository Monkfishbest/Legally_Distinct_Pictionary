package services

import domain.*
import munit.FunSuite

final class RoomServiceSpec extends FunSuite:
  test("creates a room with the owner as its first player"):
    val result = RoomService().create("Alice")

    assert(result.isRight)
    val (room, player) = result.toOption.get
    assertEquals(room.players, Vector(player))
    assertEquals(room.ownerId, player.id)

  test("allows another player to join"):
    val service = RoomService()
    val (room, _) = service.create("Alice").toOption.get

    val joined = service.join(room.code, "Bob").toOption.get

    assertEquals(joined._1.players.map(_.name), Vector("Alice", "Bob"))

  test("rejects blank player names"):
    assertEquals(RoomService().create("   "), Left(GameError.PlayerNameRequired))

  test("rejects duplicate player names within a room"):
    val service = RoomService()
    val (room, _) = service.create("Alice").toOption.get

    assertEquals(service.join(room.code, "alice"), Left(GameError.DuplicatePlayerName))
