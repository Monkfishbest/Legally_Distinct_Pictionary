package services

import domain.*
import munit.FunSuite
import realtime.*

final class DrawingCommandSpec extends FunSuite:
  test("accepts a stroke from the current artist and assigns a sequence"):
    val service = RoomService()
    val (room, artist) = service.create("Alice").toOption.get
    val (_, player) = service.join(room.code, "Bob").toOption.get
    service.execute(room.code, StartGame(artist.id))
    service.execute(room.code, ChooseDifficulty(artist.id, WordDifficulty.Simple))
    service.beginDrawing(room.code)

    val result = service.applyDrawing(
      room.code,
      DrawingOperationCommand(
        artist.id,
        "Stroke",
        Vector(DrawPoint(10, 20), DrawPoint(30, 40)),
        "#000000",
        4
      )
    )

    assertEquals(result.toOption.get.sequence, 1L)
    assertEquals(
      service.applyDrawing(
        room.code,
        DrawingOperationCommand(player.id, "Stroke", Vector(DrawPoint(1, 1)), "#000000", 4)
      ),
      Left(GameError.NotArtist)
    )

  test("rejects invalid stroke data"):
    val service = RoomService()
    val (room, artist) = service.create("Alice").toOption.get
    service.join(room.code, "Bob")
    service.execute(room.code, StartGame(artist.id))
    service.execute(room.code, ChooseDifficulty(artist.id, WordDifficulty.Simple))
    service.beginDrawing(room.code)

    val result = service.applyDrawing(
      room.code,
      DrawingOperationCommand(artist.id, "Stroke", Vector.empty, "red", 0)
    )

    assertEquals(
      result,
      Left(GameError.InvalidDrawingOperation("A stroke requires at least one point"))
    )

  test("accepts a full-canvas fill from the artist"):
    val service = RoomService()
    val (room, artist) = service.create("Alice").toOption.get
    service.join(room.code, "Bob")
    service.execute(room.code, StartGame(artist.id))
    service.execute(room.code, ChooseDifficulty(artist.id, WordDifficulty.Simple))
    service.beginDrawing(room.code)

    val result = service.applyDrawing(
      room.code,
      DrawingOperationCommand(artist.id, "Fill", Vector.empty, "#112233", 1)
    )

    assertEquals(result.toOption.get.operationType, DrawingOperationType.Fill)
