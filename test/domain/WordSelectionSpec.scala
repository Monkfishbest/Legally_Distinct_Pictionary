package domain

import munit.FunSuite

import scala.util.Random

final class WordSelectionSpec extends FunSuite:
  private val builtIn = Word("1", "Cat", WordDifficulty.Simple, WordSource.BuiltIn)
  private val custom = Word(
    "2",
    "  Space  Ship ",
    WordDifficulty.Simple,
    WordSource.Custom,
    roomId = Some("room-1")
  )

  test("selects built-in and room custom words for the chosen difficulty"):
    val selected = WordSelection.choose(
      Vector(builtIn, custom),
      WordDifficulty.Simple,
      "room-1",
      Random(0)
    )

    assert(selected.isRight)

  test("does not include another room's custom words"):
    val selected = WordSelection.choose(
      Vector(custom),
      WordDifficulty.Simple,
      "room-2",
      Random(0)
    )

    assertEquals(selected, Left(GameError.NoWordsAvailable(WordDifficulty.Simple)))

  test("normalizes valid words"):
    assertEquals(custom.normalizedText, "space ship")

  test("requires custom words to belong to a room"):
    val invalid = Word("3", "Tree", WordDifficulty.Simple, WordSource.Custom)

    assertEquals(
      WordSelection.validate(invalid),
      Left(GameError.InvalidWord("Custom words must belong to a room"))
    )
