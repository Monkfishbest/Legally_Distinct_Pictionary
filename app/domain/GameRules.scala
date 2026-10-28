package domain

object GameRules:
  val baseConfirmedPlayerPoints: Int = 100
  val timeBonusPerRemainingSecond: Int = 1

  def startGame(state: GameState): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.Lobby =>
        Left(GameError.InvalidPhase(GamePhase.Lobby, state.phase))

      case state if state.players.size < 2 =>
        Left(GameError.NotEnoughPlayers)

      case state if state.settings.turnDurationSeconds < state.settings.minimumTurnDurationSeconds || state.settings.turnsPerPlayer <= 0 =>
        Left(GameError.InvalidSettings)

      case state =>
        Right(
          state.copy(
            phase = GamePhase.WordSelection,
            turnNumber = 1,
            difficulty = None,
            selectedWordId = None,
            confirmedPlayerIds = Vector.empty
          )
        )

  def chooseDifficulty(
      state: GameState,
      difficulty: WordDifficulty
  ): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.WordSelection =>
        Left(GameError.InvalidPhase(GamePhase.WordSelection, state.phase))

      case state =>
        Right(
          state.copy(
            phase = GamePhase.Countdown,
            difficulty = Some(difficulty),
            confirmedPlayerIds = Vector.empty
          )
        )

  def rematch(state: GameState): Either[GameError, GameState] =
    if state.phase != GamePhase.Complete then Left(GameError.InvalidPhase(GamePhase.Complete, state.phase))
    else Right(state.copy(
      players = state.players.map(_.copy(score = 0)),
      phase = GamePhase.Lobby,
      artistIndex = 0,
      turnNumber = 0,
      difficulty = None,
      selectedWordId = None,
      confirmedPlayerIds = Vector.empty
    ))

  def confirmPlayer(
      state: GameState,
      playerId: String,
      elapsedSeconds: Int
  ): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.Drawing =>
        Left(GameError.InvalidPhase(GamePhase.Drawing, state.phase))

      case state if state.difficulty.isEmpty =>
        Left(GameError.DifficultyNotSelected)

      case state if !state.players.exists(_.id == playerId) =>
        Left(GameError.PlayerNotFound(playerId))

      case state if state.artist.exists(_.id == playerId) =>
        Left(GameError.ArtistCannotBeConfirmed)

      case state if state.confirmedPlayerIds.contains(playerId) =>
        Left(GameError.PlayerAlreadyConfirmed(playerId))

      case state =>
        Right(state.copy(confirmedPlayerIds = state.confirmedPlayerIds :+ playerId))

  def endTurn(state: GameState, elapsedSeconds: Int = 0): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.Drawing =>
        Left(GameError.InvalidPhase(GamePhase.Drawing, state.phase))

      case state if state.confirmedPlayerIds.isEmpty =>
        Right(state.copy(phase = GamePhase.TurnComplete))

      case state if state.artist.isEmpty =>
        Left(GameError.PlayerNotFound("artist"))

      case state =>
        val score = roundScore(state, elapsedSeconds)
        val recipients = state.confirmedPlayerIds :+ state.artist.get.id
        Right(recipients.foldLeft(state.copy(phase = GamePhase.TurnComplete))((current, playerId) => addScore(current, playerId, score)))

  def nextTurn(state: GameState): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.TurnComplete =>
        Left(GameError.InvalidPhase(GamePhase.TurnComplete, state.phase))

      case state if state.players.isEmpty =>
        Left(GameError.NotEnoughPlayers)

      case state if state.turnNumber >= state.settings.turnsPerPlayer * state.players.size =>
        Right(state.copy(phase = GamePhase.Complete))

      case state =>
        Right(
          state.copy(
            phase = GamePhase.WordSelection,
            artistIndex = (state.artistIndex + 1) % state.players.size,
            turnNumber = state.turnNumber + 1,
            difficulty = None,
            selectedWordId = None,
            confirmedPlayerIds = Vector.empty
          )
        )

  private def roundScore(state: GameState, elapsedSeconds: Int): Int =
    val duration = state.settings.durationForTurn(state.turnNumber)
    val remainingSeconds = (duration - elapsedSeconds).max(0)
    val multiplier = state.difficulty.map(_.multiplier).getOrElse(BigDecimal(1))
    ((BigDecimal(baseConfirmedPlayerPoints) * multiplier) +
      (BigDecimal(remainingSeconds * timeBonusPerRemainingSecond) * multiplier))
      .setScale(0, BigDecimal.RoundingMode.HALF_UP)
      .toInt

  private def addScore(state: GameState, playerId: String, points: Int): GameState =
    state.copy(
      players = state.players.map:
        player =>
          if player.id == playerId then player.copy(score = player.score + points)
          else player
    )
