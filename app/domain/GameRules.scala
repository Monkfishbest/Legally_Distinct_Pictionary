package domain

object GameRules:
  val baseConfirmedPlayerPoints: Int = 100
  val artistParticipationPoints: Int = 50
  val placementBonus: Int = 25
  val placementBonusStep: Int = 5

  def startGame(state: GameState): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.Lobby =>
        Left(GameError.InvalidPhase(GamePhase.Lobby, state.phase))

      case state if state.players.size < 2 =>
        Left(GameError.NotEnoughPlayers)

      case state if state.settings.turnDurationSeconds <= 0 || state.settings.turnsPerPlayer <= 0 =>
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
            phase = GamePhase.Drawing,
            difficulty = Some(difficulty),
            confirmedPlayerIds = Vector.empty
          )
        )

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
        val placement = state.confirmedPlayerIds.size + 1
        val score = confirmedPlayerScore(state, elapsedSeconds, placement)
        Right(
          addScore(
            state.copy(confirmedPlayerIds = state.confirmedPlayerIds :+ playerId),
            playerId,
            score
          )
        )

  def endTurn(state: GameState): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.Drawing =>
        Left(GameError.InvalidPhase(GamePhase.Drawing, state.phase))

      case state if state.confirmedPlayerIds.isEmpty =>
        Right(state.copy(phase = GamePhase.TurnComplete))

      case state if state.artist.isEmpty =>
        Left(GameError.PlayerNotFound("artist"))

      case state =>
        val artist = state.artist.get
        val multiplier = state.difficulty.map(_.multiplier).getOrElse(BigDecimal(1))
        val score = (BigDecimal(artistParticipationPoints) * multiplier)
          .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          .toInt

        Right(addScore(state.copy(phase = GamePhase.TurnComplete), artist.id, score))

  def nextTurn(state: GameState): Either[GameError, GameState] =
    state match
      case state if state.phase != GamePhase.TurnComplete =>
        Left(GameError.InvalidPhase(GamePhase.TurnComplete, state.phase))

      case state if state.players.isEmpty =>
        Left(GameError.NotEnoughPlayers)

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

  private def confirmedPlayerScore(
      state: GameState,
      elapsedSeconds: Int,
      placement: Int
  ): Int =
    val remainingSeconds = (state.settings.turnDurationSeconds - elapsedSeconds).max(0)
    val remainingRatio = BigDecimal(remainingSeconds) / state.settings.turnDurationSeconds
    val multiplier = state.difficulty.map(_.multiplier).getOrElse(BigDecimal(1))
    val timeScore = (BigDecimal(baseConfirmedPlayerPoints) * multiplier * remainingRatio)
      .setScale(0, BigDecimal.RoundingMode.HALF_UP)
      .toInt

    timeScore + (placementBonus - ((placement - 1) * placementBonusStep)).max(0)

  private def addScore(state: GameState, playerId: String, points: Int): GameState =
    state.copy(
      players = state.players.map:
        player =>
          if player.id == playerId then player.copy(score = player.score + points)
          else player
    )
