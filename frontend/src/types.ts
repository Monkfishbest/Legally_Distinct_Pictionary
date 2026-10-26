export type Difficulty = 'Simple' | 'Medium' | 'Hard' | 'Impossible'
export type Phase = 'Lobby' | 'WordSelection' | 'Countdown' | 'Drawing' | 'TurnComplete' | 'Complete'

export interface Player {
  id: string
  name: string
  score: number
}

export interface Room {
  code: string
  ownerId: string
  connectedPlayerIds?: string[]
  currentPlayerId: string | null
  players: Player[]
  settings: { turnDurationSeconds: number; turnsPerPlayer: number }
}

export interface GameState {
  id: string
  phase: Phase
  turnNumber: number
  artistId: string | null
  difficulty: Difficulty | null
  confirmedPlayerIds: string[]
  players: Player[]
}

export interface TurnReveal {
  word: string
  difficulty: Difficulty
  correct: boolean
}

export interface WordOption {
  id: string
  displayText: string
  difficulty: Difficulty
}

export interface Point { x: number; y: number }
export interface DrawingOperation {
  id: string
  turnNumber: number
  playerId: string
  operationType: 'Stroke' | 'Fill' | 'Clear' | 'Undo'
  points: Point[]
  color: string
  width: number
  sequence: number
}

export interface GalleryItem {
  gameId: string
  turnNumber: number
  artistName: string
  answer: string | null
  difficulty: Difficulty | null
  imageUrl: string
  mimeType: string
}

export type ServerEvent =
  | { type: 'room.state'; room: Omit<Room, 'currentPlayerId'> }
  | { type: 'game.state'; state: GameState }
  | { type: 'drawing.operation'; operation: DrawingOperation }
  | { type: 'drawing.snapshot'; operations: DrawingOperation[] }
  | { type: 'turn.word_assigned'; word: string; difficulty: Difficulty }
  | { type: 'turn.countdown'; seconds: number }
  | { type: 'turn.timer'; deadlineEpochMs: number }
  | { type: 'turn.timeout' }
  | { type: 'turn.word_options'; options: WordOption[] }
  | { type: 'turn.reveal'; word: string; difficulty: Difficulty; correct: boolean }
  | { type: 'error'; error: string }
