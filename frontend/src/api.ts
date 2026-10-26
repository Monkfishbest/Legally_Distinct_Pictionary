import type { GalleryItem, Room } from './types'

const apiBase = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, { headers: { 'Content-Type': 'application/json' }, ...options })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.error ?? `Request failed (${response.status})`)
  return body as T
}

export const createRoom = (name: string, settings?: { turnDurationSeconds: number; turnsPerPlayer: number }) =>
  request<Room>('/api/rooms', { method: 'POST', body: JSON.stringify({ name, ...settings }) })

export const createCustomWord = (text: string, difficulty: string) =>
  request('/api/words', {
    method: 'POST', body: JSON.stringify({ text, difficulty }),
  })

export const joinRoom = (code: string, name: string) =>
  request<Room>(`/api/rooms/${encodeURIComponent(code)}/join`, {
    method: 'POST', body: JSON.stringify({ name }),
  })

export const getRoom = (code: string) => request<Room>(`/api/rooms/${encodeURIComponent(code)}`)

export const getGallery = (gameId: string) =>
  request<GalleryItem[]>(`/api/games/${encodeURIComponent(gameId)}/gallery`).then(items =>
    items.map(item => ({
      ...item,
      imageUrl: item.imageUrl.startsWith('http') ? item.imageUrl : `${apiBase}${item.imageUrl}`,
    }))
  )

export async function uploadFinishedImage(gameId: string, turnNumber: number, playerId: string, blob: Blob) {
  const response = await fetch(`${apiBase}/api/games/${encodeURIComponent(gameId)}/turns/${turnNumber}/image?playerId=${encodeURIComponent(playerId)}`, {
    method: 'POST', headers: { 'Content-Type': 'image/png' }, body: blob,
  })
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error ?? 'Image upload failed')
}
