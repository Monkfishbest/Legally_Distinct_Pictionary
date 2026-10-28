package persistence

import domain.*
import play.api.Configuration
import play.api.libs.json.{Json, JsValue}

import javax.inject.Inject
import java.sql.{Connection, DriverManager}
import java.util.UUID

trait GamePersistence:
  def saveRoom(room: Room): Either[GameError, Unit]
  def saveGame(state: GameState): Either[GameError, Unit]
  def saveConfirmation(gameId: String, turnNumber: Int, playerId: String, points: Int): Either[GameError, Unit]
  def saveDrawing(gameId: String, operation: DrawingOperation): Either[GameError, Unit]
  def loadRooms(): Either[GameError, Vector[Room]]
  def loadGames(): Either[GameError, Vector[GameState]]
  def loadDrawings(): Either[GameError, Vector[(String, DrawingOperation)]]

final class JdbcGamePersistence @Inject() (configuration: Configuration) extends GamePersistence:
  private val url = configuration.get[String]("pictionary.database.url")
  private val user = configuration.get[String]("pictionary.database.user")
  private val password = configuration.get[String]("pictionary.database.password")

  override def saveRoom(room: Room): Either[GameError, Unit] =
    attempt:
      using(connection()) { connection =>
        connection.setAutoCommit(false)
        try
          execute(connection,
            """
              |INSERT INTO rooms (code, owner_id, turn_duration_seconds, turns_per_player, reduce_timer_each_turn)
              |VALUES (?, ?, ?, ?, ?)
              |ON CONFLICT (code) DO UPDATE SET owner_id = EXCLUDED.owner_id,
              |turn_duration_seconds = EXCLUDED.turn_duration_seconds,
              |turns_per_player = EXCLUDED.turns_per_player,
              |reduce_timer_each_turn = EXCLUDED.reduce_timer_each_turn
              |""".stripMargin
          ) { statement =>
            statement.setString(1, room.code)
            statement.setObject(2, UUID.fromString(room.ownerId))
            statement.setInt(3, room.settings.turnDurationSeconds)
            statement.setInt(4, room.settings.turnsPerPlayer)
            statement.setBoolean(5, room.settings.reduceTimerEachTurn)
          }
          room.players.foreach: player =>
            execute(connection,
              """
                |INSERT INTO room_players (id, room_code, name, score)
                |VALUES (?, ?, ?, ?)
                |ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, score = EXCLUDED.score
                |""".stripMargin
            ) { statement =>
              statement.setObject(1, UUID.fromString(player.id))
              statement.setString(2, room.code)
              statement.setString(3, player.name)
              statement.setInt(4, player.score)
            }
          connection.commit()
        catch
          case error: Throwable =>
            connection.rollback()
            throw error
      }

  override def saveGame(state: GameState): Either[GameError, Unit] =
    attempt:
      using(connection()) { connection =>
        execute(connection,
          """
            |INSERT INTO games (id, room_code, phase, artist_index, turn_number, difficulty, selected_word_id)
            |VALUES (?, ?, ?, ?, ?, ?, ?)
            |ON CONFLICT (id) DO UPDATE SET phase = EXCLUDED.phase,
            |artist_index = EXCLUDED.artist_index, turn_number = EXCLUDED.turn_number,
            |difficulty = EXCLUDED.difficulty, selected_word_id = EXCLUDED.selected_word_id,
            |updated_at = CURRENT_TIMESTAMP
            |""".stripMargin
        ) { statement =>
          statement.setString(1, state.id)
          statement.setString(2, state.id)
          statement.setString(3, state.phase.toString)
          statement.setInt(4, state.artistIndex)
          statement.setInt(5, state.turnNumber)
          state.difficulty match
            case Some(value) => statement.setString(6, value.toString)
            case None => statement.setObject(6, null)
          state.selectedWordId match
            case Some(value) => statement.setObject(7, UUID.fromString(value))
            case None => statement.setObject(7, null)
        }
        execute(connection,
          """
            |INSERT INTO turns (id, game_id, turn_number, artist_player_id, word_id, difficulty, phase)
            |VALUES (?, ?, ?, ?, ?, ?, ?)
            |ON CONFLICT (game_id, turn_number) DO UPDATE SET word_id = EXCLUDED.word_id,
            |difficulty = EXCLUDED.difficulty, phase = EXCLUDED.phase,
            |ended_at = CASE WHEN EXCLUDED.phase = 'TurnComplete' THEN CURRENT_TIMESTAMP ELSE turns.ended_at END
            |""".stripMargin
        ) { statement =>
          statement.setObject(1, UUID.nameUUIDFromBytes(s"${state.id}:${state.turnNumber}".getBytes))
          statement.setString(2, state.id)
          statement.setInt(3, state.turnNumber)
          state.artist match
            case Some(player) => statement.setObject(4, UUID.fromString(player.id))
            case None => statement.setObject(4, null)
          state.selectedWordId match
            case Some(value) => statement.setObject(5, UUID.fromString(value))
            case None => statement.setObject(5, null)
          state.difficulty match
            case Some(value) => statement.setString(6, value.toString)
            case None => statement.setObject(6, null)
          statement.setString(7, state.phase.toString)
        }
      }

  override def saveConfirmation(gameId: String, turnNumber: Int, playerId: String, points: Int): Either[GameError, Unit] =
    attempt:
      using(connection()) { connection =>
        execute(connection,
          """
            |INSERT INTO turn_confirmations (id, game_id, turn_number, player_id, points)
            |VALUES (?, ?, ?, ?, ?)
            |ON CONFLICT (game_id, turn_number, player_id) DO NOTHING
            |""".stripMargin
        ) { statement =>
          statement.setObject(1, UUID.randomUUID())
          statement.setString(2, gameId)
          statement.setInt(3, turnNumber)
          statement.setObject(4, UUID.fromString(playerId))
          statement.setInt(5, points)
        }
      }

  override def saveDrawing(gameId: String, operation: DrawingOperation): Either[GameError, Unit] =
    Right(())

  override def loadRooms(): Either[GameError, Vector[Room]] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          """
            |SELECT r.code, r.owner_id, r.turn_duration_seconds, r.turns_per_player, r.reduce_timer_each_turn,
            |p.id, p.name, p.score
            |FROM rooms r LEFT JOIN room_players p ON p.room_code = r.code
            |ORDER BY r.code, p.name
            |""".stripMargin
        )) { statement =>
          using(statement.executeQuery()) { results =>
            val rows = Iterator.continually(results).takeWhile(_.next()).map: row =>
              (
                row.getString("code"),
                row.getString("owner_id"),
                row.getInt("turn_duration_seconds"),
                row.getInt("turns_per_player"),
                row.getBoolean("reduce_timer_each_turn"),
                Option(row.getString("id")),
                Option(row.getString("name")),
                Option(row.getObject("score")).map(_.asInstanceOf[Integer].intValue()).getOrElse(0)
              )
            rows.toVector.groupBy(_._1).values.map: roomRows =>
              val first = roomRows.head
              Room(
                first._1,
                first._2,
                roomRows.flatMap: row =>
                  for
                    id <- row._6
                    name <- row._7
                  yield Player(id, name, row._8),
                GameSettings(first._3, first._4, first._5)
              )
            .toVector
          }
        }
      }

  override def loadGames(): Either[GameError, Vector[GameState]] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          """
            |SELECT g.id, g.phase, g.artist_index, g.turn_number, g.difficulty,
            |g.selected_word_id, p.id AS player_id, p.name, p.score, r.turn_duration_seconds,
            |r.turns_per_player, r.reduce_timer_each_turn
            |FROM games g
            |JOIN rooms r ON r.code = g.room_code
            |LEFT JOIN room_players p ON p.room_code = r.code
            |ORDER BY g.id, p.name
            |""".stripMargin
        )) { statement =>
          using(statement.executeQuery()) { results =>
            val rows = Iterator.continually(results).takeWhile(_.next()).map: row =>
              (
                row.getString("id"),
                GamePhase.valueOf(row.getString("phase")),
                row.getInt("artist_index"),
                row.getInt("turn_number"),
                Option(row.getString("difficulty")).flatMap(value => WordDifficulty.values.find(_.toString == value)),
                Option(row.getString("selected_word_id")),
                Option(row.getString("player_id")),
                Option(row.getString("name")),
                Option(row.getObject("score")).map(_.asInstanceOf[Integer].intValue()).getOrElse(0),
                row.getInt("turn_duration_seconds"),
                row.getInt("turns_per_player"),
                row.getBoolean("reduce_timer_each_turn")
              )
            rows.toVector.groupBy(_._1).values.map: gameRows =>
              val first = gameRows.head
              GameState(
                first._1,
                gameRows.flatMap: row =>
                  for
                    id <- row._7
                    name <- row._8
                  yield Player(id, name, row._9),
                GameSettings(first._10, first._11, first._12),
                first._2,
                first._3,
                first._4,
                first._5,
                first._6
              )
            .toVector
          }
        }
      }

  override def loadDrawings(): Either[GameError, Vector[(String, DrawingOperation)]] =
    attempt:
      using(connection()) { connection =>
        using(connection.prepareStatement(
          "SELECT game_id, id, turn_number, player_id, operation_type, points_json, color, width, sequence FROM drawing_operations ORDER BY game_id, sequence"
        )) { statement =>
          using(statement.executeQuery()) { results =>
            Iterator.continually(results).takeWhile(_.next()).map: row =>
              val operationType = DrawingOperationType.values.find(_.toString == row.getString("operation_type"))
                .getOrElse(throw IllegalArgumentException("Unknown drawing operation type"))
              val points = Json.parse(row.getString("points_json")).as[Vector[JsValue]].map: point =>
                DrawPoint((point \ "x").as[Double], (point \ "y").as[Double])
              row.getString("game_id") -> DrawingOperation(
                row.getObject("id").toString,
                row.getInt("turn_number"),
                row.getObject("player_id").toString,
                operationType,
                points,
                row.getString("color"),
                row.getDouble("width"),
                row.getLong("sequence")
              )
            .toVector
          }
        }
      }

  private def connection(): Connection = DriverManager.getConnection(url, user, password)

  private def execute(connection: Connection, sql: String)(setParameters: java.sql.PreparedStatement => Unit): Unit =
    using(connection.prepareStatement(sql)) { statement =>
      setParameters(statement)
      statement.executeUpdate()
    }

  private def attempt[A](operation: => A): Either[GameError, A] =
    try Right(operation)
    catch case error: Throwable => Left(GameError.PersistenceFailure(error.getMessage))

  private def using[A <: AutoCloseable, B](resource: A)(operation: A => B): B =
    try operation(resource)
    finally resource.close()

final class NoopGamePersistence extends GamePersistence:
  def saveRoom(room: Room): Either[GameError, Unit] = Right(())
  def saveGame(state: GameState): Either[GameError, Unit] = Right(())
  def saveConfirmation(gameId: String, turnNumber: Int, playerId: String, points: Int): Either[GameError, Unit] = Right(())
  def saveDrawing(gameId: String, operation: DrawingOperation): Either[GameError, Unit] = Right(())
  def loadRooms(): Either[GameError, Vector[Room]] = Right(Vector.empty)
  def loadGames(): Either[GameError, Vector[GameState]] = Right(Vector.empty)
  def loadDrawings(): Either[GameError, Vector[(String, DrawingOperation)]] = Right(Vector.empty)
