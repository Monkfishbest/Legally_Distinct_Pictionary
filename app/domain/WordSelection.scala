package domain

import scala.util.Random

object WordSelection:
  private val maxWordLength = 100

  def validate(word: Word): Either[GameError, Word] =
    val normalized = word.normalizedText

    word match
      case word if normalized.isEmpty =>
        Left(GameError.InvalidWord("Word cannot be empty"))

      case word if normalized.length > maxWordLength =>
        Left(GameError.InvalidWord(s"Word cannot exceed $maxWordLength characters"))

      case word if normalized.exists(_.isControl) =>
        Left(GameError.InvalidWord("Word cannot contain control characters"))

      case word => Right(word.copy(displayText = word.displayText.trim))

  def eligible(
      words: Vector[Word],
      difficulty: WordDifficulty,
      _roomId: String,
      includeBuiltIn: Boolean = true,
      includeCustom: Boolean = true
  ): Vector[Word] =
    words.filter:
      word =>
        word.active &&
          word.difficulty == difficulty &&
          ((includeBuiltIn && word.source == WordSource.BuiltIn) ||
            (includeCustom && word.source == WordSource.Custom))

  def choose(
      words: Vector[Word],
      difficulty: WordDifficulty,
      roomId: String,
      random: Random = Random()
  ): Either[GameError, Word] =
    val candidates = eligible(words, difficulty, roomId)

    candidates match
      case Vector() => Left(GameError.NoWordsAvailable(difficulty))
      case candidates => Right(candidates(random.nextInt(candidates.size)))
