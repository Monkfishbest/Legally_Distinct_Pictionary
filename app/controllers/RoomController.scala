package controllers

import domain.*
import play.api.libs.json.*
import play.api.mvc.*
import realtime.RoomHub
import services.RoomService

import javax.inject.*

final case class PlayerRequest(
    name: String,
    turnDurationSeconds: Option[Int] = None,
    turnsPerPlayer: Option[Int] = None,
    reduceTimerEachTurn: Option[Boolean] = None
)

object PlayerRequest:
  given Reads[PlayerRequest] = Json.reads[PlayerRequest]

@Singleton
final class RoomController @Inject() (
    controllerComponents: ControllerComponents,
    roomService: RoomService,
    roomHub: RoomHub
) extends AbstractController(controllerComponents):

  def create: Action[JsValue] = Action(parse.json): request =>
    request.body.validate[PlayerRequest].fold(
      errors => BadRequest(Json.obj("error" -> JsError.toJson(errors))),
      playerRequest =>
        val settings = GameSettings(
          playerRequest.turnDurationSeconds.getOrElse(GameSettings().turnDurationSeconds),
          playerRequest.turnsPerPlayer.getOrElse(GameSettings().turnsPerPlayer),
          playerRequest.reduceTimerEachTurn.getOrElse(GameSettings().reduceTimerEachTurn)
        )
        roomService.create(playerRequest.name, settings) match
          case Right((room, player)) => Created(roomJson(room, Some(player.id)))
          case Left(GameError.PlayerNameRequired) => BadRequest(Json.obj("error" -> "Player name must be 1-30 characters"))
          case Left(_) => InternalServerError(Json.obj("error" -> "Could not create room"))
    )

  def get(code: String): Action[AnyContent] = Action:
    roomService.find(code) match
      case Some(room) => Ok(roomJson(room, None))
      case None => NotFound(Json.obj("error" -> "Room not found"))

  def join(code: String): Action[JsValue] = Action(parse.json): request =>
    request.body.validate[PlayerRequest].fold(
      errors => BadRequest(Json.obj("error" -> JsError.toJson(errors))),
      playerRequest =>
        roomService.join(code, playerRequest.name) match
          case Right((room, player)) =>
            roomHub.broadcastRoom(room)
            Ok(roomJson(room, Some(player.id)))
          case Left(GameError.InvalidRoomCode) => NotFound(Json.obj("error" -> "Room not found"))
          case Left(GameError.RoomFull) => Conflict(Json.obj("error" -> "Room is full"))
          case Left(GameError.DuplicatePlayerName) => Conflict(Json.obj("error" -> "That name is already in use in this room"))
          case Left(GameError.PlayerNameRequired) => BadRequest(Json.obj("error" -> "Player name must be 1-30 characters"))
          case Left(_) => InternalServerError(Json.obj("error" -> "Could not join room"))
    )

  private def roomJson(room: Room, currentPlayerId: Option[String]): JsObject =
    Json.obj(
      "code" -> room.code,
      "ownerId" -> room.ownerId,
      "currentPlayerId" -> currentPlayerId,
      "players" -> room.players.map: player =>
        Json.obj("id" -> player.id, "name" -> player.name, "score" -> player.score),
      "settings" -> Json.obj(
        "turnDurationSeconds" -> room.settings.turnDurationSeconds,
        "turnsPerPlayer" -> room.settings.turnsPerPlayer,
        "reduceTimerEachTurn" -> room.settings.reduceTimerEachTurn
      )
    )
