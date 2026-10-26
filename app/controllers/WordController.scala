package controllers

import domain.*
import persistence.WordRepository
import play.api.libs.json.*
import play.api.mvc.*

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}
import java.sql.SQLException

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
      case None => Future.successful(BadRequest(errorJson("INVALID_DIFFICULTY", "Unknown difficulty")))
      case Some(parsedDifficulty) =>
        wordRepository.findEligible("global", parsedDifficulty)
          .map(words => Ok(Json.toJson(words.map(toJson))))
          .recover:
            case _: SQLException => ServiceUnavailable(errorJson("DATABASE_UNAVAILABLE", "The word database is temporarily unavailable"))

  def create: Action[JsValue] = Action.async(parse.json):
    _.body.validate[CreateWordRequest].fold(
      errors => Future.successful(BadRequest(errorJson("INVALID_REQUEST", JsError.toJson(errors).toString))),
      request =>
        parseDifficulty(request.difficulty) match
          case None => Future.successful(BadRequest(errorJson("INVALID_DIFFICULTY", "Unknown difficulty")))
          case Some(difficulty) =>
            val response = wordRepository.createCustom(request.text, difficulty).map:
              case Right(word) => Created(toJson(word))
              case Left(GameError.InvalidWord(reason)) => BadRequest(errorJson("INVALID_WORD", reason))
              case Left(GameError.DuplicateWord) => Conflict(errorJson("DUPLICATE_WORD", "That custom word already exists for this difficulty"))
              case Left(GameError.DatabaseUnavailable) => ServiceUnavailable(errorJson("DATABASE_UNAVAILABLE", "The word database is temporarily unavailable"))
              case Left(_) => InternalServerError(errorJson("WORD_CREATE_FAILED", "Could not create word"))
            response.recover:
              case _: SQLException => ServiceUnavailable(errorJson("DATABASE_UNAVAILABLE", "The word database is temporarily unavailable"))
    )

  private def errorJson(code: String, message: String): JsObject =
    Json.obj("code" -> code, "error" -> message)

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
