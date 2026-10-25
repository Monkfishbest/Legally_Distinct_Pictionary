import { useEffect, useRef, useState } from 'react'
import type { DrawingOperation, Point } from './types'

interface Props {
  operations: DrawingOperation[]
  canDraw: boolean
  color: string
  width: number
  onOperation: (operationType: string, points?: Point[]) => void
  onLiveOperation?: (operationType: string, points: Point[]) => void
  onCanvasReady?: (canvas: HTMLCanvasElement) => void
}

export function CanvasBoard({ operations, canDraw, color, width, onOperation, onLiveOperation, onCanvasReady }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [draft, setDraft] = useState<Point[]>([])
  const draftRef = useRef<Point[]>([])
  const pendingAcknowledgement = useRef<{ sequence: number } | null>(null)
  const strokeStartSequence = useRef(0)
  const sentLiveOperations = useRef(0)
  const drawing = useRef(false)

  useEffect(() => {
    const latestSequence = operations.reduce((latest, operation) => Math.max(latest, operation.sequence), 0)
    if (pendingAcknowledgement.current && latestSequence >= pendingAcknowledgement.current.sequence) {
      pendingAcknowledgement.current = null
      draftRef.current = []
      setDraft([])
    }
  }, [operations])

  useEffect(() => {
    const element = canvas.current
    if (!element) return
    onCanvasReady?.(element)
    const context = element.getContext('2d')!
    context.fillStyle = '#fffdf8'
    context.fillRect(0, 0, element.width, element.height)
    for (const operation of operations) {
      if (operation.operationType === 'Fill') {
        context.fillStyle = operation.color
        context.fillRect(0, 0, element.width, element.height)
      } else if (operation.operationType === 'Clear') {
        context.fillStyle = '#fffdf8'
        context.fillRect(0, 0, element.width, element.height)
      } else if (operation.operationType === 'Stroke') {
        drawStroke(context, operation.points, operation.color, operation.width)
      }
    }
    if (draft.length) drawStroke(context, draft, color, width)
  }, [operations, draft, color, width, onCanvasReady])

  useEffect(() => {
    if (!canDraw && drawing.current) {
      drawing.current = false
      pendingAcknowledgement.current = null
      draftRef.current = []
      setDraft([])
    }
  }, [canDraw])

  const point = (event: React.PointerEvent<HTMLCanvasElement>): Point => {
    const rect = event.currentTarget.getBoundingClientRect()
    return {
      x: ((event.clientX - rect.left) / rect.width) * event.currentTarget.width,
      y: ((event.clientY - rect.top) / rect.height) * event.currentTarget.height,
    }
  }

  return <canvas
    ref={canvas} width={900} height={560} className="canvas-board"
    onPointerDown={event => {
      if (!canDraw) return
      drawing.current = true
      const firstPoint = point(event)
      strokeStartSequence.current = operations.reduce((latest, operation) => Math.max(latest, operation.sequence), 0)
      sentLiveOperations.current = 0
      draftRef.current = [firstPoint]
      try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* Some mobile browsers reject capture after a cancelled touch. */ }
      setDraft([firstPoint])
    }}
    onPointerMove={event => {
      if (drawing.current && canDraw) {
        const nextPoint = point(event)
        const previousPoint = draftRef.current[draftRef.current.length - 1]
        if (previousPoint) {
          sentLiveOperations.current += 1
          onLiveOperation?.('Stroke', [previousPoint, nextPoint])
        }
        draftRef.current = [...draftRef.current, nextPoint]
        setDraft(current => [...current, nextPoint])
      }
    }}
    onPointerUp={() => {
      if (!drawing.current) return
      drawing.current = false
      const expectedOperations = Math.max(1, sentLiveOperations.current)
      pendingAcknowledgement.current = { sequence: strokeStartSequence.current + expectedOperations }
      if (draftRef.current.length === 1) onOperation('Stroke', draftRef.current)
    }}
    onPointerCancel={() => {
      drawing.current = false
      pendingAcknowledgement.current = null
      draftRef.current = []
      setDraft([])
    }}
  />
}

function drawStroke(context: CanvasRenderingContext2D, points: Point[], color: string, width: number) {
  if (!points.length) return
  context.strokeStyle = color
  context.lineWidth = width
  context.lineCap = 'round'
  context.lineJoin = 'round'
  context.beginPath()
  context.moveTo(points[0].x, points[0].y)
  for (const point of points.slice(1)) context.lineTo(point.x, point.y)
  if (points.length === 1) context.lineTo(points[0].x + 0.1, points[0].y + 0.1)
  context.stroke()
}
