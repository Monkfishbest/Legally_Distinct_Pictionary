import { fireEvent, render, screen } from '@testing-library/react'
import { DifficultyPicker, TurnActions } from './App'

const players = [
  { id: 'artist', name: 'Artist', score: 0 },
  { id: 'guess-1', name: 'Alex', score: 0 },
  { id: 'guess-2', name: 'Sam', score: 0 },
]

describe('turn controls', () => {
  it('shows the selected word beside every difficulty option', () => {
    render(<DifficultyPicker isArtist options={[
      { id: '1', difficulty: 'Simple', displayText: 'Apple' },
      { id: '2', difficulty: 'Medium', displayText: 'Inhaler' },
      { id: '3', difficulty: 'Hard', displayText: 'Volcano' },
      { id: '4', difficulty: 'Impossible', displayText: 'Quantum entanglement' },
    ]} onChoose={() => undefined} />)

    expect(screen.getByText('Apple')).toBeInTheDocument()
    expect(screen.getByText('Inhaler')).toBeInTheDocument()
    expect(screen.getByText('Volcano')).toBeInTheDocument()
    expect(screen.getByText('Quantum entanglement')).toBeInTheDocument()
  })

  it('disables difficulty choices while words are unavailable', () => {
    render(<DifficultyPicker isArtist options={[]} onChoose={() => undefined} />)
    expect(screen.getByRole('button', { name: /Simple Loading word/i })).toBeDisabled()
  })

  it('never offers the artist as a correct guess', () => {
    render(<TurnActions isArtist forceSelection players={players} confirmed={[]} artistId="artist" onConfirm={() => undefined} />)
    expect(screen.queryByRole('button', { name: 'Artist' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Alex' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sam' })).toBeInTheDocument()
  })

  it('does not offer an early end-turn button', () => {
    render(<TurnActions isArtist forceSelection={false} players={players} confirmed={[]} artistId="artist" onOpenSelection={() => undefined} onConfirm={() => undefined} />)
    fireEvent.click(screen.getByRole('button', { name: /Someone guessed it/i }))
    expect(screen.queryByRole('button', { name: /End turn/i })).not.toBeInTheDocument()
  })

  it('confirms a selected player and excludes confirmed players', () => {
    const onConfirm = vi.fn()
    render(<TurnActions isArtist forceSelection players={players} confirmed={['guess-1']} artistId="artist" onConfirm={onConfirm} />)
    expect(screen.queryByRole('button', { name: 'Alex' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Sam' }))
    expect(onConfirm).toHaveBeenCalledWith('guess-2')
  })
})
