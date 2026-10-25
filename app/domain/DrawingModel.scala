package domain

enum DrawingOperationType:
  case Stroke, Fill, Clear, Undo

final case class DrawPoint(x: Double, y: Double)

final case class DrawingOperation(
    id: String,
    turnNumber: Int,
    playerId: String,
    operationType: DrawingOperationType,
    points: Vector[DrawPoint],
    color: String,
    width: Double,
    sequence: Long
)
