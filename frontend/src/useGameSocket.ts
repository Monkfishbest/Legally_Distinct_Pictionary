import { useCallback, useEffect, useRef, useState } from 'react'
import type { Difficulty, DrawingOperation, GameState, Room, ServerEvent, TurnReveal, WordOption } from './types'

export function useGameSocket(roomCode: string | null, playerId: string | null, phoneConnection = false) {
  const socket = useRef<WebSocket | null>(null)
  const pendingMessages = useRef<string[]>([])
  const [connected, setConnected] = useState(false)
  const [game, setGame] = useState<GameState | null>(null)
  const [room, setRoom] = useState<Partial<Room> | null>(null)
  const [operations, setOperations] = useState<DrawingOperation[]>([])
  const [word, setWord] = useState<string | null>(null)
  const [wordDifficulty, setWordDifficulty] = useState<Difficulty | null>(null)
  const [reveal, setReveal] = useState<TurnReveal | null>(null)
  const [wordOptions, setWordOptions] = useState<WordOption[]>([])
  const [countdown, setCountdown] = useState<number | null>(null)
  const [turnDeadline, setTurnDeadline] = useState<number | null>(null)
  const [serverTimeOffset, setServerTimeOffset] = useState(0)
  const [timedOut, setTimedOut] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!roomCode || !playerId) return
    const configuredWsUrl = (import.meta.env.VITE_WS_URL ?? '').replace(/\/$/, '')
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const wsOrigin = configuredWsUrl || `${protocol}://${window.location.host}`
    let stopped = false
    let retryAttempt = 0
    let retryTimer: number | null = null

    const connect = () => {
      if (stopped) return
      const phoneQuery = phoneConnection ? '&phone=1' : ''
      const ws = new WebSocket(`${wsOrigin}/api/rooms/${roomCode}/ws?playerId=${playerId}${phoneQuery}`)
      socket.current = ws
      const syncTime = () => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'time.ping', playerId, clientSentAt: Date.now() }))
      }
      const timeSyncTimer = window.setInterval(syncTime, 30000)
      ws.onopen = () => {
        retryAttempt = 0
        setConnected(true)
        setError(null)
        syncTime()
        for (const pending of pendingMessages.current.splice(0)) ws.send(pending)
      }
      ws.onclose = () => {
        window.clearInterval(timeSyncTimer)
        if (socket.current === ws) socket.current = null
        setConnected(false)
        if (!stopped) {
          setError('Connection interrupted. Reconnecting...')
          const delay = Math.min(1000 * 2 ** retryAttempt, 10000)
          retryAttempt += 1
          retryTimer = window.setTimeout(connect, delay)
        }
      }
      ws.onerror = () => setError('Connection interrupted. Reconnecting...')
      ws.onmessage = (message) => {
        const event = JSON.parse(message.data) as ServerEvent
        if (event.type === 'room.state') setRoom(event.room)
        if (event.type === 'game.state') {
          setGame(event.state)
          if (event.state.phase === 'WordSelection') {
            setOperations([])
            setWord(null)
            setWordDifficulty(null)
            setReveal(null)
            setWordOptions([])
            setCountdown(null)
            setTurnDeadline(null)
            setTimedOut(false)
          }
          if (event.state.phase === 'Drawing') {
            setCountdown(null)
            setTimedOut(false)
          }
        }
        if (event.type === 'drawing.snapshot') setOperations(event.operations)
        if (event.type === 'drawing.operation') setOperations(current => [...current, event.operation])
        if (event.type === 'turn.word_assigned') { setWord(event.word); setWordDifficulty(event.difficulty) }
        if (event.type === 'turn.countdown') setCountdown(event.seconds > 0 ? event.seconds : null)
        if (event.type === 'turn.timer') setTurnDeadline(event.deadlineEpochMs)
        if (event.type === 'time.pong') {
          const now = Date.now()
          const midpoint = (event.clientSentAt + now) / 2
          setServerTimeOffset(event.serverEpochMs - midpoint)
        }
        if (event.type === 'turn.timeout') {
          setCountdown(null)
          setTimedOut(true)
        }
        if (event.type === 'turn.word_options') setWordOptions(event.options)
        if (event.type === 'turn.reveal') setReveal(event)
        if (event.type === 'error') setError(event.error)
      }
    }
    connect()
    return () => {
      stopped = true
      if (retryTimer !== null) window.clearTimeout(retryTimer)
      socket.current?.close()
      socket.current = null
      setConnected(false)
    }
  }, [roomCode, playerId, phoneConnection])

  const send = useCallback((payload: object) => {
    const message = JSON.stringify(payload)
    const currentSocket = socket.current
    if (currentSocket?.readyState === WebSocket.OPEN) currentSocket.send(message)
    else {
      pendingMessages.current = [...pendingMessages.current.slice(-19), message]
      setError('Connection interrupted. Reconnecting...')
    }
  }, [])

  return { connected, room, game, operations, word, wordDifficulty, wordOptions, countdown, turnDeadline, serverTimeOffset, timedOut, reveal, error, setError, send }
}
