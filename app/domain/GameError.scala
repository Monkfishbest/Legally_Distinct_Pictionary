package domain

enum GameError:
  case NotEnoughPlayers
  case PlayersNotConnected
  case InvalidPhase(expected: GamePhase, actual: GamePhase)
  case InvalidSettings
  case PlayerNotFound(playerId: String)
  case ArtistCannotBeConfirmed
  case PlayerAlreadyConfirmed(playerId: String)
  case DifficultyNotSelected
  case InvalidWord(reason: String)
  case DuplicateWord
  case DatabaseUnavailable
  case NoWordsAvailable(difficulty: WordDifficulty)
  case InvalidRoomCode
  case PlayerNameRequired
  case DuplicatePlayerName
  case RoomFull
  case NotRoomOwner
  case NotArtist
  case InvalidCommand(reason: String)
  case DrawingNotAllowed
  case InvalidDrawingOperation(reason: String)
  case PersistenceFailure(reason: String)
  case ImageStorageFailure(reason: String)
  case ImageNotFound
  case ImageTooLarge
