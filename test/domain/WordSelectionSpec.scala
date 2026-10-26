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

  test("selects built-in and custom words for the chosen difficulty"):
    val selected = WordSelection.choose(
      Vector(builtIn, custom),
      WordDifficulty.Simple,
      "room-1",
      Random(0)
    )

    assert(selected.isRight)

  test("custom words are available in every room"):
    val selected = WordSelection.choose(
      Vector(custom),
      WordDifficulty.Simple,
      "room-2",
      Random(0)
    )

    assert(selected.isRight)

  test("normalizes valid words"):
    assertEquals(custom.normalizedText, "space ship")

  test("allows global custom words without a room"):
    val invalid = Word("3", "Tree", WordDifficulty.Simple, WordSource.Custom)

    assert(WordSelection.validate(invalid).isRight)
