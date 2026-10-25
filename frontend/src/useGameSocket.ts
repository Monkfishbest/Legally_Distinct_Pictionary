import { useCallback, useEffect, useRef, useState } from 'react'
import type { Difficulty, DrawingOperation, GameState, Room, ServerEvent, TurnReveal, WordOption } from './types'

export function useGameSocket(roomCode: string | null, playerId: string | null) {
  const socket = useRef<WebSocket | null>(null)
  const [connected, setConnected] = useState(false)
  const [game, setGame] = useState<GameState | null>(null)
  const [room, setRoom] = useState<Partial<Room> | null>(null)
  const [operations, setOperations] = useState<DrawingOperation[]>([])
  const [word, setWord] = useState<string | null>(null)
  const [wordDifficulty, setWordDifficulty] = useState<Difficulty | null>(null)
  const [reveal, setReveal] = useState<TurnReveal | null>(null)
  const [wordOptions, setWordOptions] = useState<WordOption[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!roomCode || !playerId) return
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${protocol}://${window.location.host}/api/rooms/${roomCode}/ws?playerId=${playerId}`)
    socket.current = ws
    ws.onopen = () => { setConnected(true); setError(null) }
    ws.onclose = () => setConnected(false)
    ws.onerror = () => setError('WebSocket connection failed')
    ws.onmessage = (message) => {
      const event = JSON.parse(message.data) as ServerEvent
      if (event.type === 'room.state') setRoom(event.room)
      if (event.type === 'game.state') {
        setGame(event.state)
        if (event.state.phase === 'WordSelection') {
          setWord(null)
          setWordDifficulty(null)
          setReveal(null)
          setWordOptions([])
        }
      }
      if (event.type === 'drawing.snapshot') setOperations(event.operations)
      if (event.type === 'drawing.operation') setOperations(current => [...current, event.operation])
      if (event.type === 'turn.word_assigned') { setWord(event.word); setWordDifficulty(event.difficulty) }
      if (event.type === 'turn.word_options') setWordOptions(event.options)
      if (event.type === 'turn.reveal') setReveal(event)
      if (event.type === 'error') setError(event.error)
    }
    return () => { ws.close(); socket.current = null; setConnected(false) }
  }, [roomCode, playerId])

  const send = useCallback((payload: object) => {
    if (socket.current?.readyState === WebSocket.OPEN) socket.current.send(JSON.stringify(payload))
    else setError('Not connected to the game')
  }, [])

  return { connected, room, game, operations, word, wordDifficulty, wordOptions, reveal, error, setError, send }
}
