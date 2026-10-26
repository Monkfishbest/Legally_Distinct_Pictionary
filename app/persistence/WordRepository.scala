package persistence

import domain.*
import play.api.Configuration

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

trait WordRepository:
  def findEligible(roomId: String, difficulty: WordDifficulty): Future[Vector[Word]]
  def createCustom(displayText: String, difficulty: WordDifficulty): Future[Either[GameError, Word]]

final class JdbcWordRepository @Inject() (
    configuration: Configuration,
    executionContext: ExecutionContext
) extends WordRepository:
  private given ExecutionContext = executionContext
  private val url = configuration.get[String]("pictionary.database.url")
  private val user = configuration.get[String]("pictionary.database.user")
  private val password = configuration.get[String]("pictionary.database.password")

  private def connection(): Connection = DriverManager.getConnection(url, user, password)

  override def findEligible(_roomId: String, difficulty: WordDifficulty): Future[Vector[Word]] = Future:
    using(connection()) { connection =>
      val statement = connection.prepareStatement(
        """
          |SELECT id, display_text, difficulty, source, room_id, active
          |FROM words
          |WHERE active = TRUE
          |  AND difficulty = ?
          |  AND source IN ('BuiltIn', 'Custom')
          |ORDER BY display_text
          |""".stripMargin
      )
      statement.setString(1, difficulty.toString)
      using(statement) { statement =>
        using(statement.executeQuery()) { results =>
          Iterator
            .continually(results)
            .takeWhile(_.next())
            .map(readWord)
            .toVector
        }
      }
    }

  override def createCustom(
      displayText: String,
      difficulty: WordDifficulty
  ): Future[Either[GameError, Word]] = Future:
    val candidate = Word(
      UUID.randomUUID().toString,
      displayText,
      difficulty,
      WordSource.Custom,
      roomId = None
    )

    WordSelection.validate(candidate) match
      case invalid @ Left(_) => invalid
      case Right(validWord) =>
        try
          val storedWord = using(connection()) { connection =>
            val existingStatement = connection.prepareStatement(
              "SELECT id, display_text, difficulty, source, room_id, active FROM words WHERE source = 'Custom' AND normalized_text = ? AND difficulty = ? LIMIT 1"
            )
            val existing = using(existingStatement) { statement =>
              statement.setString(1, validWord.normalizedText)
              statement.setString(2, validWord.difficulty.toString)
              using(statement.executeQuery()) { results =>
                if results.next() then Some(readWord(results)) else None
              }
            }
            existing.getOrElse:
              val statement = connection.prepareStatement(
                """
                  |INSERT INTO words (id, display_text, normalized_text, difficulty, source, room_id)
                  |VALUES (?, ?, ?, ?, 'Custom', NULL)
                  |""".stripMargin
              )
              using(statement) { statement =>
                statement.setObject(1, UUID.fromString(validWord.id))
                statement.setString(2, validWord.displayText)
                statement.setString(3, validWord.normalizedText)
                statement.setString(4, validWord.difficulty.toString)
                statement.executeUpdate()
              }
              validWord
          }
          Right(storedWord)
        catch
          case _: java.sql.SQLException =>
            Left(GameError.InvalidWord("That custom word already exists"))

  private def readWord(results: ResultSet): Word =
    Word(
      results.getString("id"),
      results.getString("display_text"),
      WordDifficulty.valueOf(results.getString("difficulty")),
      WordSource.valueOf(results.getString("source")),
      Option(results.getString("room_id")),
      results.getBoolean("active")
    )

  private def using[A <: AutoCloseable, B](resource: A)(operation: A => B): B =
    try operation(resource)
    finally resource.close()
