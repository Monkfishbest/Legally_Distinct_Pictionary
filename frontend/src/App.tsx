import { useEffect, useRef, useState } from 'react'
import { createCustomWord, createRoom, getGallery, joinRoom, uploadFinishedImage } from './api'
import { CanvasBoard } from './CanvasBoard'
import { useGameSocket } from './useGameSocket'
import type { Difficulty, GalleryItem, Room } from './types'
import { QRCodeSVG } from 'qrcode.react'
import './styles.css'

const difficulties: Difficulty[] = ['Simple', 'Medium', 'Hard', 'Impossible']
const publicOrigin = import.meta.env.VITE_PUBLIC_URL ?? window.location.origin

export default function App() {
  const phoneParams = new URLSearchParams(window.location.search)
  const phoneRoom = phoneParams.get('phone') === '1' ? phoneParams.get('room') : null
  const phonePlayer = phoneParams.get('playerId')
  if (phoneRoom && phonePlayer) return <PhoneController roomCode={phoneRoom} playerId={phonePlayer} />

  const [room, setRoom] = useState<Room | null>(null)
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<'create' | 'join'>('create')
  const [turnDurationSeconds, setTurnDurationSeconds] = useState(60)
  const [turnsPerPlayer, setTurnsPerPlayer] = useState(1)
  const [customWord, setCustomWord] = useState('')
  const [customDifficulty, setCustomDifficulty] = useState<Difficulty>('Simple')
  const [customMessage, setCustomMessage] = useState<string | null>(null)

  const enter = async () => {
    try {
      setError(null)
      setRoom(mode === 'create' ? await createRoom(name, { turnDurationSeconds, turnsPerPlayer }) : await joinRoom(code, name))
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Could not join room') }
  }

  const submitHomeWord = async () => {
    try { await createCustomWord(customWord, customDifficulty); setCustomWord(''); setCustomMessage('Custom word added to the shared word pool.') }
    catch (caught) { setCustomMessage(caught instanceof Error ? caught.message : 'Could not add custom word') }
  }

  if (!room) return <main className="shell landing">
    <div className="brand">LEGALLY DISTINCT <span>PICTIONARY</span></div>
    <p className="eyebrow">CLASSIC MODE</p>
    <h1>Make the room.<br /><em>Make the picture.</em></h1>
    <div className="entry-card">
      <div className="tabs"><button className={mode === 'create' ? 'active' : ''} onClick={() => setMode('create')}>Create room</button><button className={mode === 'join' ? 'active' : ''} onClick={() => setMode('join')}>Join room</button></div>
      <label>Your name<input value={name} onChange={event => setName(event.target.value)} placeholder="e.g. Alex" maxLength={30} /></label>
      {mode === 'join' && <label>Room code<input value={code} onChange={event => setCode(event.target.value.toUpperCase())} placeholder="ABC123" maxLength={6} /></label>}
      {mode === 'create' && <div className="host-settings"><label>Seconds per round<input type="number" min={10} max={600} value={turnDurationSeconds} onChange={event => setTurnDurationSeconds(Number(event.target.value))} /></label><label>Rounds per player<input type="number" min={1} max={20} value={turnsPerPlayer} onChange={event => setTurnsPerPlayer(Number(event.target.value))} /></label></div>}
      <button className="primary" onClick={enter} disabled={!name.trim() || (mode === 'join' && code.length !== 6)}>{mode === 'create' ? 'Create room' : 'Join room'} <span>→</span></button>
      {error && <p className="error">{error}</p>}
    </div>
    <div className="home-custom-words"><p className="eyebrow">SUBMIT A CUSTOM WORD</p><p>Add a word to the shared pool for everyone to use in future games.</p><div><input value={customWord} onChange={event => setCustomWord(event.target.value)} placeholder="Custom word" maxLength={100} /><select value={customDifficulty} onChange={event => setCustomDifficulty(event.target.value as Difficulty)}>{difficulties.map(value => <option key={value}>{value}</option>)}</select><button className="secondary" disabled={!customWord.trim()} onClick={submitHomeWord}>Add word</button></div>{customMessage && <small>{customMessage}</small>}</div>
  </main>

  return <GameRoom room={room} onError={setError} />
}

function GameRoom({ room, onError }: { room: Room; onError: (message: string | null) => void }) {
  const [currentRoom, setCurrentRoom] = useState(room)
  const socket = useGameSocket(room.code, room.currentPlayerId)
  const [gallery, setGallery] = useState<GalleryItem[]>([])
  const [timeLeft, setTimeLeft] = useState<number | null>(null)
  const isOwner = currentRoom.ownerId === currentRoom.currentPlayerId
  const me = socket.game?.players.find(player => player.id === room.currentPlayerId) ?? currentRoom.players.find(player => player.id === room.currentPlayerId)

  useEffect(() => { if (socket.error) onError(socket.error) }, [socket.error, onError])
  useEffect(() => {
    if (socket.room) setCurrentRoom(current => ({ ...current, ...socket.room, currentPlayerId: current.currentPlayerId }))
  }, [socket.room])
  useEffect(() => {
    if (socket.game?.phase === 'Complete') getGallery(socket.game.id).then(setGallery).catch(error => onError(error.message))
  }, [socket.game?.phase, socket.game?.id, onError])
  useEffect(() => {
    if (!socket.game || socket.game.phase !== 'Drawing') {
      setTimeLeft(null)
      return
    }
    setTimeLeft(currentRoom.settings.turnDurationSeconds)
    const timer = window.setInterval(() => setTimeLeft(current => current === null ? null : Math.max(0, current - 1)), 1000)
    return () => window.clearInterval(timer)
  }, [socket.game?.turnNumber, socket.game?.phase, currentRoom.settings.turnDurationSeconds])
  useEffect(() => {
    if (socket.reveal) playRevealSound(socket.reveal.correct)
  }, [socket.reveal])

  const send = (type: string, values: object = {}) => socket.send({ type, playerId: room.currentPlayerId, ...values })
  const game = socket.game
  const phase = game?.phase ?? 'Lobby'
  const players = game?.players ?? currentRoom.players
  const finished = phase === 'Complete'
  const phoneUrl = `${publicOrigin}/?phone=1&room=${encodeURIComponent(room.code)}&playerId=${encodeURIComponent(game?.artistId ?? room.currentPlayerId ?? '')}`

  if (finished && game) return <Gallery gameId={game.id} items={gallery} />

  return <main className="shell game-shell">
    <header className="topbar"><div className="brand">LEGALLY DISTINCT <span>PICTIONARY</span></div><div className="room-pill">ROOM <strong>{room.code}</strong><button onClick={() => navigator.clipboard?.writeText(room.code)}>copy</button></div>{timeLeft !== null && <div className={`round-timer ${timeLeft <= 10 ? 'urgent' : ''}`}><span>TIME</span><strong>{formatTime(timeLeft)}</strong></div>}<div className="phone-qr"><QRCodeSVG value={phoneUrl} size={54} /><span>SCAN FOR PHONE</span></div><div className="connection"><i className={socket.connected ? 'online' : ''} /> {socket.connected ? 'Connected' : 'Connecting...'}</div></header>
    <section className="game-layout">
      <aside className="round-panel"><span className="round-label">ROUND</span><strong>{game?.turnNumber ?? 0}<small> / {room.settings.turnsPerPlayer * players.length}</small></strong><div className={`round-timer ${timeLeft !== null && timeLeft <= 10 ? 'urgent' : ''}`}><span>TIME</span><b>{timeLeft === null ? '--:--' : formatTime(timeLeft)}</b></div>{game?.difficulty && <span className="round-points">{game.difficulty} round</span>}</aside>
      <section className="board-area">
        <div className="phase-bar"><div><span className="eyebrow">{phase === 'Lobby' ? 'WAITING ROOM' : `TURN ${game?.turnNumber ?? 0}`}</span><h2>{phaseTitle(phase, false)}</h2></div></div>
        {phase === 'Lobby' && <Lobby room={currentRoom} isOwner={isOwner} phoneUrl={phoneUrl} onStart={() => send('game.start')} />}
        {phase === 'WordSelection' && <DifficultyPicker isArtist={false} options={[]} onChoose={() => undefined} />}
        {(phase === 'Drawing' || phase === 'TurnComplete') && <><div className="canvas-wrap"><CanvasBoard operations={socket.operations} canDraw={false} color="#171923" width={8} onOperation={() => undefined} /></div><div className="display-note">The artist is drawing from their phone.</div></>}
        {phase === 'Drawing' && <div className="waiting-note">The artist controls the word, correct guesses, and end of turn from their phone.</div>}
        {phase === 'TurnComplete' && socket.reveal && <Reveal reveal={socket.reveal} operations={socket.operations} />}
      </section>
      <aside className="sidebar"><div className="score-head"><span>PLAYERS</span><span>SCORE</span></div>{players.map(player => <div className={`player-row ${player.id === game?.artistId ? 'artist' : ''}`} key={player.id}><span className="avatar">{player.name.slice(0, 1).toUpperCase()}</span><span>{player.name}{player.id === room.currentPlayerId && <small> YOU</small>}{player.id === game?.artistId && <b>✎</b>}</span><strong>{player.score}</strong></div>)}</aside>
    </section>
    {socket.error && <div className="toast error">{socket.error}</div>}
    <div className="me-label">Playing as <strong>{me?.name}</strong></div>
  </main>
}

function Lobby({ room, isOwner, phoneUrl, onStart }: { room: Room; isOwner: boolean; phoneUrl: string; onStart: () => void }) {
  const [word, setWord] = useState('')
  const [difficulty, setDifficulty] = useState<Difficulty>('Simple')
  const [wordMessage, setWordMessage] = useState<string | null>(null)
  const submitWord = async () => {
    try { await createCustomWord(word, difficulty); setWord(''); setWordMessage('Custom word added to the shared word pool.') }
    catch (error) { setWordMessage(error instanceof Error ? error.message : 'Could not add custom word') }
  }
  return <div className="lobby"><div className="lobby-icon">✦</div><h3>Invite your people</h3><p>Share the room code, then scan the QR code on the painter’s phone.</p><div className="big-code">{room.code}</div><div className="qr-card"><QRCodeSVG value={phoneUrl} size={124} bgColor="#fffdf8" fgColor="#10131c" /><small>SCAN TO CONTROL THE GAME</small></div><button className="primary" disabled={!isOwner || room.players.length < 2} onClick={onStart}>{room.players.length < 2 ? 'Waiting for players...' : 'Start game →'}</button><div className="custom-word-form"><p className="eyebrow">ADD A CUSTOM WORD</p><input value={word} onChange={event => setWord(event.target.value)} placeholder="e.g. family inside joke" maxLength={100} /><select value={difficulty} onChange={event => setDifficulty(event.target.value as Difficulty)}>{difficulties.map(value => <option key={value}>{value}</option>)}</select><button className="secondary" disabled={!word.trim()} onClick={submitWord}>Add word</button>{wordMessage && <small>{wordMessage}</small>}</div></div>
}

function PhoneController({ roomCode, playerId }: { roomCode: string; playerId: string }) {
  const socket = useGameSocket(roomCode, playerId)
  const [color, setColor] = useState('#171923')
  const [width, setWidth] = useState(8)
  const [canvas, setCanvas] = useState<HTMLCanvasElement | null>(null)
  const [uploading, setUploading] = useState(false)
  const [gallery, setGallery] = useState<GalleryItem[]>([])
  const [roundExpired, setRoundExpired] = useState(false)
  const [selectingGuess, setSelectingGuess] = useState(false)
  const [readyToDraw, setReadyToDraw] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const lastSavedTurn = useRef<number | null>(null)
  const game = socket.game
  const phase = game?.phase ?? 'Lobby'
  const isArtist = game?.artistId === playerId

  useEffect(() => {
    if (game?.phase !== 'Drawing' || !isArtist) {
      setRoundExpired(false)
      return
    }
    setRoundExpired(false)
    let remaining = socket.room?.settings?.turnDurationSeconds ?? 60
    const timer = window.setInterval(() => {
      remaining -= 1
      if (remaining <= 0) {
        setRoundExpired(true)
        setSelectingGuess(true)
        window.clearInterval(timer)
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [game?.phase, game?.turnNumber, isArtist, socket.room?.settings?.turnDurationSeconds])

  useEffect(() => {
    if (game?.phase !== 'Drawing' || !isArtist) setSelectingGuess(false)
  }, [game?.phase, game?.turnNumber, isArtist])

  useEffect(() => {
    if (game?.phase !== 'Drawing') setReadyToDraw(false)
  }, [game?.phase, game?.turnNumber])

  useEffect(() => {
    if (game?.phase !== 'TurnComplete' || !isArtist || !canvas || lastSavedTurn.current === game.turnNumber) return
    lastSavedTurn.current = game.turnNumber
    setUploading(true)
    canvas.toBlob(async blob => {
      try {
        if (!blob) throw new Error('Could not capture the finished drawing')
        await uploadFinishedImage(game.id, game.turnNumber, playerId, blob)
        send('turn.next')
      } catch (caught) {
        lastSavedTurn.current = null
        setSaveError(caught instanceof Error ? caught.message : 'Could not save the drawing')
      } finally { setUploading(false) }
    }, 'image/png')
  }, [game?.phase, game?.turnNumber, game?.id, isArtist, canvas, playerId])

  useEffect(() => {
    if (game?.phase === 'Complete') getGallery(game.id).then(setGallery).catch(() => undefined)
  }, [game?.phase, game?.id])

  const send = (type: string, values: object = {}) => socket.send({ type, playerId, ...values })
  const showWordBriefing = phase === 'Drawing' && isArtist && Boolean(socket.word) && !readyToDraw && !roundExpired
  if (game?.phase === 'Complete') return <Gallery gameId={game.id} items={gallery} />

  return <main className="shell phone-shell">
    <header className="topbar"><div className="brand">LEGALLY DISTINCT <span>PICTIONARY</span></div><div className="room-pill">ROOM <strong>{roomCode}</strong></div><div className="connection"><i className={socket.connected ? 'online' : ''} /> {socket.connected ? 'Connected' : 'Connecting...'}</div></header>
    <section className="phone-panel">{phaseTitle(phase, isArtist) && <h1>{phaseTitle(phase, isArtist)}</h1>}{socket.word && isArtist && <div className="phone-word"><small>{socket.wordDifficulty ?? 'SELECTED'} →</small><strong>{socket.word}</strong><span>Keep it secret.</span></div>}
      {phase === 'Lobby' && <div className="phone-wait">Waiting for the host to start the game.</div>}
      {phase === 'WordSelection' && <DifficultyPicker isArtist={isArtist} options={socket.wordOptions} onChoose={(difficulty, wordId) => send('turn.choose_difficulty', { difficulty, wordId })} />}
      {showWordBriefing && <WordBriefing difficulty={socket.wordDifficulty} word={socket.word!} onStart={() => setReadyToDraw(true)} />}
      {!showWordBriefing && (phase === 'Drawing' && isArtist && selectingGuess ? <TurnActions key={game?.turnNumber} isArtist forceSelection players={game?.players ?? []} confirmed={game?.confirmedPlayerIds ?? []} artistId={playerId} onConfirm={targetPlayerId => { send('turn.confirm_player', { targetPlayerId, elapsedSeconds: 0 }); send('turn.end') }} onEnd={() => send('turn.end')} /> : <>{(phase === 'Drawing' || phase === 'TurnComplete') && <><div className="canvas-wrap"><CanvasBoard operations={socket.operations} canDraw={isArtist && phase === 'Drawing' && !roundExpired} color={color} width={width} onCanvasReady={setCanvas} onOperation={(operationType, points) => send('drawing.operation', { operationType, points: points ?? [], color, width })} onLiveOperation={(operationType, points) => send('drawing.operation', { operationType, points, color, width })} /></div><DrawingControls canDraw={isArtist && phase === 'Drawing' && !roundExpired} color={color} width={width} setColor={setColor} setWidth={setWidth} onAction={operationType => send('drawing.operation', { operationType, points: [], color, width })} /></>}
      {phase === 'Drawing' && <TurnActions key={game?.turnNumber} isArtist={isArtist} forceSelection={false} players={game?.players ?? []} confirmed={game?.confirmedPlayerIds ?? []} artistId={playerId} onOpenSelection={() => setSelectingGuess(true)} onConfirm={targetPlayerId => { send('turn.confirm_player', { targetPlayerId, elapsedSeconds: 0 }); send('turn.end') }} onEnd={() => send('turn.end')} />}</>)}
      {phase === 'TurnComplete' && isArtist && <div className="next-turn"><p>{uploading ? 'Saving drawing...' : saveError ?? 'Drawing saved. Starting next turn...'}</p></div>}
    </section>
    {socket.error && <div className="toast error">{socket.error}</div>}
  </main>
}

function DifficultyPicker({ isArtist, options, onChoose }: { isArtist: boolean; options: { id: string; displayText: string; difficulty: Difficulty }[]; onChoose: (difficulty: Difficulty, wordId: string) => void }) {
  return <div className="difficulty-picker"><p>{isArtist ? 'Choose the word you want to draw.' : 'The artist is choosing a word.'}</p>{isArtist && <div className="difficulty-grid">{difficulties.map((difficulty, index) => { const option = options.find(candidate => candidate.difficulty === difficulty); return <button key={difficulty} className={`difficulty ${difficulty.toLowerCase()}`} disabled={!option} onClick={() => option && onChoose(difficulty, option.id)}><span>{['●', '◆', '▲', '✦'][index]}</span><strong>{difficulty}</strong><b>{option?.displayText ?? 'Loading word...'}</b><small>{['1× points', '1.5× points', '2× points', '3× points'][index]}</small></button> })}</div>}</div>
}

function WordBriefing({ difficulty, word, onStart }: { difficulty: Difficulty | null; word: string; onStart: () => void }) {
  return <div className="word-briefing"><p className="eyebrow">YOUR DRAWING WORD</p><h2>{difficulty ?? 'SELECTED'} <span>→</span> {word}</h2><p>Keep it secret from the other players.</p><button className="primary" onClick={onStart}>Start drawing →</button></div>
}

function DrawingControls({ canDraw, color, width, setColor, setWidth, onAction }: { canDraw: boolean; color: string; width: number; setColor: (value: string) => void; setWidth: (value: number) => void; onAction: (type: string) => void }) {
  return <div className="drawing-controls"><label>COLOUR<input type="color" value={color} disabled={!canDraw} onChange={event => setColor(event.target.value)} /></label><label className="range">SIZE <input type="range" min="2" max="40" value={width} disabled={!canDraw} onChange={event => setWidth(Number(event.target.value))} /></label><button disabled={!canDraw} onClick={() => onAction('Fill')}>Fill</button><button disabled={!canDraw} onClick={() => onAction('Undo')}>Undo</button><button disabled={!canDraw} onClick={() => onAction('Clear')}>Clear</button></div>
}

function TurnActions({ isArtist, forceSelection, players, confirmed, artistId, onOpenSelection, onConfirm, onEnd }: { isArtist: boolean; forceSelection: boolean; players: { id: string; name: string }[]; confirmed: string[]; artistId: string; onOpenSelection?: () => void; onConfirm: (id: string) => void; onEnd: () => void }) {
  if (isArtist && !forceSelection) return <div className="turn-actions"><button className="primary" onClick={onOpenSelection}>Someone guessed it</button></div>
  if (isArtist) return <div className="guess-screen"><p>{forceSelection ? 'Time is up. Who guessed correctly?' : 'Who guessed correctly?'}</p>{players.filter(player => player.id !== artistId && !confirmed.includes(player.id)).map(player => <button key={player.id} onClick={() => onConfirm(player.id)}>{player.name}</button>)}<button className="secondary" onClick={onEnd}>No one guessed it / End turn</button></div>
  return <div className="waiting-note">The artist controls guesses and the end of this turn.</div>
}

function Reveal({ reveal, operations }: { reveal: { word: string; difficulty: string; correct: boolean }; operations: import('./types').DrawingOperation[] }) {
  return <div className={`reveal ${reveal.correct ? 'reveal-correct' : 'reveal-incorrect'}`}><div className="reveal-drawing"><CanvasBoard operations={operations} canDraw={false} color="#171923" width={8} onOperation={() => undefined} /></div><div className="reveal-answer"><div className="reveal-confetti">{reveal.correct ? '🎉' : '💨'}</div><p className="eyebrow">THE WORD WAS:</p><strong>{reveal.word}</strong><small>{reveal.difficulty}</small><h3>{reveal.correct ? 'Party time!' : 'Nobody got it.'}</h3></div></div>
}

function playRevealSound(correct: boolean) {
  const audio = new AudioContext()
  const oscillator = audio.createOscillator()
  const gain = audio.createGain()
  oscillator.connect(gain).connect(audio.destination)
  oscillator.type = correct ? 'triangle' : 'sawtooth'
  oscillator.frequency.setValueAtTime(correct ? 523 : 120, audio.currentTime)
  oscillator.frequency.exponentialRampToValueAtTime(correct ? 1046 : 55, audio.currentTime + 0.45)
  gain.gain.setValueAtTime(0.001, audio.currentTime)
  gain.gain.exponentialRampToValueAtTime(0.18, audio.currentTime + 0.03)
  gain.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + 0.5)
  oscillator.start()
  oscillator.stop(audio.currentTime + 0.5)
  oscillator.addEventListener('ended', () => void audio.close())
}

function Gallery({ gameId, items }: { gameId: string; items: GalleryItem[] }) {
  return <main className="shell gallery-page"><div className="brand">LEGALLY DISTINCT <span>PICTIONARY</span></div><p className="eyebrow">GAME COMPLETE</p><h1>The gallery</h1><p className="gallery-subtitle">A little evidence of what happened here.</p><div className="gallery-strip">{items.map(item => <figure key={item.turnNumber}><img src={item.imageUrl} alt={`Turn ${item.turnNumber}`} /><figcaption><small>TURN {item.turnNumber} · {item.difficulty}</small><strong>{item.answer ?? 'Unknown word'}</strong><span>Drawn by {item.artistName}</span></figcaption></figure>)}{!items.length && <p>No finished drawings were uploaded.</p>}</div><button className="secondary" onClick={() => window.location.reload()}>Play again</button><span className="gallery-id">{gameId}</span></main>
}

function phaseTitle(phase: string, isArtist: boolean) {
  if (phase === 'Lobby') return 'Ready when you are.'
  if (phase === 'WordSelection') return isArtist ? 'Pick your difficulty.' : 'The artist is choosing.'
  if (phase === 'TurnComplete') return 'Turn complete.'
  return isArtist ? '' : 'What is it?'
}

function formatTime(seconds: number) {
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
}
