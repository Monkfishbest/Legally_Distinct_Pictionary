package controllers

import domain.*
import play.api.libs.json.Json
import play.api.mvc.*
import services.RoomService

import javax.inject.*

@Singleton
final class ImageController @Inject() (
    controllerComponents: ControllerComponents,
    roomService: RoomService
) extends AbstractController(controllerComponents):
  private val maxImageBytes = 10 * 1024 * 1024

  def upload(gameId: String, turnNumber: Int): Action[RawBuffer] = Action(parse.raw(maxLength = maxImageBytes)):
    request =>
      request.getQueryString("playerId") match
        case None => BadRequest(Json.obj("error" -> "playerId is required"))
        case Some(playerId) =>
          request.body.asBytes() match
            case None => BadRequest(Json.obj("error" -> "Image body is required"))
            case Some(bytes) =>
              roomService.saveFinishedImage(gameId, playerId, turnNumber, bytes.toArray) match
                case Right(image) =>
                  Created(Json.obj(
                    "id" -> image.id,
                    "gameId" -> image.gameId,
                    "turnNumber" -> image.turnNumber,
                    "imageUrl" -> s"/api/games/${image.gameId}/turns/${image.turnNumber}/image"
                  ))
                case Left(error) => errorResult(error)

  def gallery(gameId: String): Action[AnyContent] = Action:
    roomService.gallery(gameId) match
      case Left(error) => errorResult(error)
      case Right(items) =>
        Ok(Json.toJson(items.map: item =>
          Json.obj(
            "gameId" -> item.gameId,
            "turnNumber" -> item.turnNumber,
                    "artistName" -> item.artistName,
            "answer" -> item.answer,
            "difficulty" -> item.difficulty.map(_.toString),
            "imageUrl" -> s"/api/games/${item.gameId}/turns/${item.turnNumber}/image"
          )
        ))

  def image(gameId: String, turnNumber: Int): Action[AnyContent] = Action:
    roomService.loadImage(gameId, turnNumber) match
      case Right(Some((image, bytes))) => Ok(bytes).as(image.mimeType)
      case Right(None) => NotFound(Json.obj("error" -> "Image not found"))
      case Left(error) => errorResult(error)

  private def errorResult(error: GameError): Result = error match
    case GameError.InvalidRoomCode => NotFound(Json.obj("error" -> "Game not found"))
    case GameError.PlayerNotFound(_) | GameError.NotArtist => Forbidden(Json.obj("error" -> "Only the artist can upload this image"))
    case GameError.ImageTooLarge => EntityTooLarge(Json.obj("error" -> "Image must be 10 MB or smaller"))
    case GameError.ImageNotFound => NotFound(Json.obj("error" -> "Image not found"))
    case GameError.InvalidPhase(expected, actual) => Conflict(Json.obj("error" -> s"Expected phase $expected, but was $actual"))
    case GameError.InvalidCommand(reason) => BadRequest(Json.obj("error" -> reason))
    case GameError.PersistenceFailure(reason) =>
      InternalServerError(Json.obj("error" -> reason))
    case GameError.ImageStorageFailure(reason) =>
      InternalServerError(Json.obj("error" -> reason))
    case _ => InternalServerError(Json.obj("error" -> "Image operation failed"))
