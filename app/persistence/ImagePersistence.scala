package persistence

import domain.*
import play.api.Configuration

import javax.inject.Inject
import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager, ResultSet}
import java.util.UUID

trait ImageStorage:
  def save(gameId: String, turnNumber: Int, bytes: Array[Byte]): Either[GameError, String]
  def read(storageKey: String): Either[GameError, Array[Byte]]

final class LocalImageStorage @Inject() (configuration: Configuration) extends ImageStorage:
  private val root: Path = Paths.get(configuration.get[String]("pictionary.media.directory"))

  override def save(gameId: String, turnNumber: Int, bytes: Array[Byte]): Either[GameError, String] =
    try
      Files.createDirectories(root)
      val key = s"$gameId/$turnNumber-${UUID.randomUUID()}.png"
      val path = root.resolve(key).normalize()
      if !path.startsWith(root.normalize()) then Left(GameError.ImageStorageFailure("Invalid storage key"))
      else
        Files.createDirectories(path.getParent)
        Files.write(path, bytes)
        Right(key)
    catch
      case error: Throwable => Left(GameError.ImageStorageFailure(error.getMessage))

  override def read(storageKey: String): Either[GameError, Array[Byte]] =
    try
      val path = root.resolve(storageKey).normalize()
      if !path.startsWith(root.normalize()) then Left(GameError.ImageStorageFailure("Invalid storage key"))
      else if Files.exists(path) then Right(Files.readAllBytes(path))
      else Left(GameError.ImageNotFound)
    catch
      case error: Throwable => Left(GameError.ImageStorageFailure(error.getMessage))

trait TurnImageRepository:
  def save(image: TurnImage): Either[GameError, Unit]
  def find(gameId: String, turnNumber: Int): Either[GameError, Option[TurnImage]]
  def gallery(gameId: String): Either[GameError, Vector[GalleryItem]]

final class JdbcTurnImageRepository @Inject() (configuration: Configuration) extends TurnImageRepository:
  private val url = configuration.get[String]("pictionary.database.url")
  private val user = configuration.get[String]("pictionary.database.user")
  private val password = configuration.get[String]("pictionary.database.password")

  override def save(image: TurnImage): Either[GameError, Unit] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          """
            |INSERT INTO turn_images (id, game_id, turn_number, word_id, storage_key, mime_type)
            |VALUES (?, ?, ?, ?, ?, ?)
            |ON CONFLICT (game_id, turn_number) DO UPDATE SET storage_key = EXCLUDED.storage_key,
            |mime_type = EXCLUDED.mime_type, word_id = EXCLUDED.word_id
            |""".stripMargin
        )) { statement =>
          statement.setObject(1, UUID.fromString(image.id))
          statement.setString(2, image.gameId)
          statement.setInt(3, image.turnNumber)
          image.wordId match
            case Some(wordId) => statement.setObject(4, UUID.fromString(wordId))
            case None => statement.setObject(4, null)
          statement.setString(5, image.storageKey)
          statement.setString(6, image.mimeType)
          statement.executeUpdate()
        }
      }

  override def find(gameId: String, turnNumber: Int): Either[GameError, Option[TurnImage]] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          "SELECT id, game_id, turn_number, word_id, storage_key, mime_type FROM turn_images WHERE game_id = ? AND turn_number = ?"
        )) { statement =>
          statement.setString(1, gameId)
          statement.setInt(2, turnNumber)
          using(statement.executeQuery()) { results =>
            if results.next() then Some(readImage(results)) else None
          }
        }
      }

  override def gallery(gameId: String): Either[GameError, Vector[GalleryItem]] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          """
            |SELECT i.game_id, i.turn_number, w.display_text, t.difficulty, i.storage_key, i.mime_type
            |FROM turn_images i
            |JOIN turns t ON t.game_id = i.game_id AND t.turn_number = i.turn_number
            |LEFT JOIN words w ON w.id = i.word_id
            |WHERE i.game_id = ?
            |ORDER BY i.turn_number
            |""".stripMargin
        )) { statement =>
          statement.setString(1, gameId)
          using(statement.executeQuery()) { results =>
            Iterator.continually(results).takeWhile(_.next()).map: row =>
              GalleryItem(
                row.getString("game_id"),
                row.getInt("turn_number"),
                Option(row.getString("display_text")),
                Option(row.getString("difficulty")).flatMap(value => WordDifficulty.values.find(_.toString == value)),
                row.getString("storage_key"),
                row.getString("mime_type")
              )
            .toVector
          }
        }
      }

  private def readImage(results: ResultSet): TurnImage =
    TurnImage(
      results.getString("id"),
      results.getString("game_id"),
      results.getInt("turn_number"),
      Option(results.getString("word_id")),
      results.getString("storage_key"),
      results.getString("mime_type")
    )

  private def connection(): Connection = DriverManager.getConnection(url, user, password)

  private def attempt[A](operation: => A): Either[GameError, A] =
    try Right(operation)
    catch case error: Throwable => Left(GameError.PersistenceFailure(error.getMessage))

  private def using[A <: AutoCloseable, B](resource: A)(operation: A => B): B =
    try operation(resource)
    finally resource.close()

final class NoopTurnImageRepository extends TurnImageRepository:
  def save(image: TurnImage): Either[GameError, Unit] = Right(())
  def find(gameId: String, turnNumber: Int): Either[GameError, Option[TurnImage]] = Right(None)
  def gallery(gameId: String): Either[GameError, Vector[GalleryItem]] = Right(Vector.empty)

final class NoopImageStorage extends ImageStorage:
  def save(gameId: String, turnNumber: Int, bytes: Array[Byte]): Either[GameError, String] = Right("noop.png")
  def read(storageKey: String): Either[GameError, Array[Byte]] = Left(GameError.ImageNotFound)
