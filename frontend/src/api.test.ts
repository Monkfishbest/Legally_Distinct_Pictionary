import { createCustomWord, createRoom, joinRoom } from './api'

describe('frontend API contract', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => vi.unstubAllGlobals())

  it('sends custom words with their selected difficulty', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('{}', { status: 201 }))

    await createCustomWord('Pear', 'Simple')

    expect(fetch).toHaveBeenCalledWith('/api/words', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ text: 'Pear', difficulty: 'Simple' }),
    }))
  })

  it('sends host settings when creating a room', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'ABC123' }), { status: 201 }))

    await createRoom('Alex', { turnDurationSeconds: 45, turnsPerPlayer: 2, reduceTimerEachTurn: true })

    expect(fetch).toHaveBeenCalledWith('/api/rooms', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ name: 'Alex', turnDurationSeconds: 45, turnsPerPlayer: 2, reduceTimerEachTurn: true }),
    }))
  })

  it('uses the room code when joining', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ code: 'ABC123' }), { status: 200 }))

    await joinRoom('abc123', 'Alex')

    expect(fetch).toHaveBeenCalledWith('/api/rooms/abc123/join', expect.objectContaining({ method: 'POST' }))
  })
})
