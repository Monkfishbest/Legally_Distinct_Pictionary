package controllers

import domain.*
import persistence.WordRepository
import play.api.libs.json.*
import play.api.mvc.*

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

final case class CreateWordRequest(text: String, difficulty: String)

object CreateWordRequest:
  given Reads[CreateWordRequest] = Json.reads[CreateWordRequest]

@Singleton
final class WordController @Inject() (
    controllerComponents: ControllerComponents,
    wordRepository: WordRepository
)(using ExecutionContext)
    extends AbstractController(controllerComponents):

  def list(difficulty: String): Action[AnyContent] = Action.async:
    parseDifficulty(difficulty) match
      case None => Future.successful(BadRequest(Json.obj("error" -> "Unknown difficulty")))
      case Some(parsedDifficulty) =>
        wordRepository.findEligible("global", parsedDifficulty).map:
          words => Ok(Json.toJson(words.map(toJson)))

  def create: Action[JsValue] = Action.async(parse.json):
    _.body.validate[CreateWordRequest].fold(
      errors => Future.successful(BadRequest(Json.obj("error" -> JsError.toJson(errors)))),
      request =>
        parseDifficulty(request.difficulty) match
          case None => Future.successful(BadRequest(Json.obj("error" -> "Unknown difficulty")))
          case Some(difficulty) =>
            wordRepository.createCustom(request.text, difficulty).map:
              case Right(word) => Created(toJson(word))
              case Left(GameError.InvalidWord(reason)) => BadRequest(Json.obj("error" -> reason))
              case Left(_) => InternalServerError(Json.obj("error" -> "Could not create word"))
    )

  private def parseDifficulty(value: String): Option[WordDifficulty] =
    WordDifficulty.values.find(_.toString.equalsIgnoreCase(value))

  private def toJson(word: Word): JsObject =
    Json.obj(
      "id" -> word.id,
      "text" -> word.displayText,
      "difficulty" -> word.difficulty.toString,
      "source" -> word.source.toString,
      "roomId" -> word.roomId,
      "active" -> word.active
    )
