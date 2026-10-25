package domain

final case class TurnImage(
    id: String,
    gameId: String,
    turnNumber: Int,
    wordId: Option[String],
    storageKey: String,
    mimeType: String
)

final case class GalleryItem(
    gameId: String,
    turnNumber: Int,
    answer: Option[String],
    difficulty: Option[WordDifficulty],
    storageKey: String,
    mimeType: String
)
