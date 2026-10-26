import { act, renderHook } from '@testing-library/react'
import { useGameSocket } from './useGameSocket'

class MockWebSocket {
  static instances: MockWebSocket[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 3
  readonly url: string
  readyState: number = WebSocket.CONNECTING
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  sent: string[] = []

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
  }

  send(message: string) {
    this.sent.push(message)
  }

  close() {
    this.readyState = WebSocket.CLOSED
    this.onclose?.()
  }

  open() {
    this.readyState = WebSocket.OPEN
    this.onopen?.()
  }

  receive(event: unknown) {
    this.onmessage?.({ data: JSON.stringify(event) })
  }
}

describe('useGameSocket', () => {
  beforeEach(() => {
    MockWebSocket.instances = []
    vi.stubGlobal('WebSocket', MockWebSocket)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('marks phone connections in the websocket URL', () => {
    renderHook(() => useGameSocket('ABC123', 'player-1', true))

    expect(MockWebSocket.instances[0].url).toContain('/api/rooms/ABC123/ws?playerId=player-1&phone=1')
  })

  it('does not mark the main display connection as a phone', () => {
    renderHook(() => useGameSocket('ABC123', 'player-1'))

    expect(MockWebSocket.instances[0].url).not.toContain('phone=1')
  })

  it('clears drawing operations when the next turn begins', () => {
    const { result } = renderHook(() => useGameSocket('ABC123', 'player-1'))
    const ws = MockWebSocket.instances[0]
    act(() => ws.open())

    act(() => ws.receive({
      type: 'drawing.snapshot',
      operations: [{ id: 'stroke-1', turnNumber: 1, playerId: 'player-1', operationType: 'Stroke', points: [{ x: 1, y: 2 }], color: '#000000', width: 4, sequence: 1 }],
    }))
    expect(result.current.operations).toHaveLength(1)

    act(() => ws.receive({
      type: 'game.state',
      state: { id: 'game-1', phase: 'WordSelection', turnNumber: 2, artistId: 'player-2', difficulty: null, confirmedPlayerIds: [], players: [] },
    }))
    expect(result.current.operations).toEqual([])
  })

  it('tracks the countdown and reveal events used by the turn screens', () => {
    const { result } = renderHook(() => useGameSocket('ABC123', 'player-1'))
    const ws = MockWebSocket.instances[0]
    act(() => ws.open())

    act(() => ws.receive({ type: 'turn.countdown', seconds: 5 }))
    expect(result.current.countdown).toBe(5)

    act(() => ws.receive({ type: 'turn.timer', deadlineEpochMs: Date.now() + 60000 }))
    expect(result.current.turnDeadline).toBeGreaterThan(Date.now())

    act(() => ws.receive({ type: 'turn.reveal', word: 'Pear', difficulty: 'Simple', correct: true }))
    expect(result.current.reveal).toEqual({ type: 'turn.reveal', word: 'Pear', difficulty: 'Simple', correct: true })

    act(() => ws.receive({ type: 'turn.countdown', seconds: 0 }))
    expect(result.current.countdown).toBeNull()
  })

  it('does not locally tick the server-owned countdown', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useGameSocket('ABC123', 'player-1'))
    const ws = MockWebSocket.instances[0]
    act(() => ws.open())
    act(() => ws.receive({ type: 'turn.countdown', seconds: 5 }))
    act(() => vi.advanceTimersByTime(5000))
    expect(result.current.countdown).toBe(5)
    act(() => ws.receive({ type: 'game.state', state: { id: 'game-1', phase: 'Drawing', turnNumber: 1, artistId: 'player-1', difficulty: 'Simple', confirmedPlayerIds: [], players: [] } }))
    expect(result.current.countdown).toBeNull()
  })

  it('queues a command during a disconnect and sends it after reconnecting', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useGameSocket('ABC123', 'player-1', true))
    const first = MockWebSocket.instances[0]
    act(() => first.close())
    act(() => result.current.send({ type: 'turn.confirm_player', playerId: 'player-1', targetPlayerId: 'player-2', elapsedSeconds: 1 }))

    act(() => vi.advanceTimersByTime(1000))
    const replacement = MockWebSocket.instances[1]
    act(() => replacement.open())

    expect(replacement.sent).toHaveLength(1)
    expect(JSON.parse(replacement.sent[0]).type).toBe('turn.confirm_player')
  })
})
