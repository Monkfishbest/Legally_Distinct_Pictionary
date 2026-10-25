package controllers

import play.api.mvc.*
import realtime.RoomHub
import services.RoomService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
final class RoomWebSocketController @Inject() (
    roomService: RoomService,
    roomHub: RoomHub
)(using ExecutionContext):
  def connect(code: String): WebSocket =
    WebSocket.acceptOrResult[String, String] { request =>
      val normalizedCode = code.trim.toUpperCase
      request.getQueryString("playerId") match
        case None => Future.successful(Left(Results.BadRequest("playerId is required")))
        case Some(playerId) if !roomService.hasPlayer(normalizedCode, playerId) =>
          Future.successful(Left(Results.Forbidden("Player is not in this room")))
        case Some(playerId) => Future.successful(Right(roomHub.flow(normalizedCode, playerId)))
    }
