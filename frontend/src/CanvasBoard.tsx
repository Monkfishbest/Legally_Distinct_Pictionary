import { useEffect, useRef } from 'react'
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
  const draftRef = useRef<Point[]>([])
  const pendingLivePoints = useRef<Point[] | null>(null)
  const pendingAcknowledgement = useRef<number | null>(null)
  const renderedSequence = useRef(0)
  const sentLiveOperations = useRef(0)
  const lastLiveSentAt = useRef(0)
  const drawing = useRef(false)

  const redrawAll = (context: CanvasRenderingContext2D, entries: DrawingOperation[]) => {
    context.fillStyle = '#fffdf8'
    context.fillRect(0, 0, context.canvas.width, context.canvas.height)
    const rendered = entries.reduce<DrawingOperation[]>((result, operation) => {
      if (operation.operationType === 'Undo') return result.slice(0, -1)
      return [...result, operation]
    }, [])
    for (const operation of rendered) applyOperation(context, operation)
    renderedSequence.current = entries.reduce((latest, operation) => Math.max(latest, operation.sequence), 0)
  }

  useEffect(() => {
    const element = canvas.current
    if (!element) return
    onCanvasReady?.(element)
    const context = element.getContext('2d')!
    redrawAll(context, operations)
    // The canvas is initialized once. Incoming operations are applied incrementally below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const context = canvas.current?.getContext('2d')
    if (!context) return
    // Replaying the authoritative operation list keeps Clear, Undo, and Fill
    // consistent even when they arrive while a local stroke is awaiting ack.
    redrawAll(context, operations)
    pendingAcknowledgement.current = null
    draftRef.current = []
  }, [operations])

  useEffect(() => {
    if (!canDraw && drawing.current) {
      drawing.current = false
      pendingAcknowledgement.current = null
      pendingLivePoints.current = null
      draftRef.current = []
    }
  }, [canDraw])

  const point = (event: React.PointerEvent<HTMLCanvasElement>): Point => {
    const rect = event.currentTarget.getBoundingClientRect()
    return {
      x: ((event.clientX - rect.left) / rect.width) * event.currentTarget.width,
      y: ((event.clientY - rect.top) / rect.height) * event.currentTarget.height,
    }
  }

  const sendPendingLivePoints = () => {
    const points = pendingLivePoints.current
    if (!points || points.length < 2) return
    onLiveOperation?.('Stroke', points)
    sentLiveOperations.current += 1
    pendingLivePoints.current = null
    lastLiveSentAt.current = performance.now()
  }

  return <canvas
    ref={canvas} width={900} height={560} className="canvas-board"
    onPointerDown={event => {
      if (!canDraw) return
      drawing.current = true
      const firstPoint = point(event)
      const context = event.currentTarget.getContext('2d')!
      drawStroke(context, [firstPoint], color, width)
      sentLiveOperations.current = 0
      lastLiveSentAt.current = 0
      pendingLivePoints.current = null
      draftRef.current = [firstPoint]
      try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* Mobile browsers may reject capture after cancellation. */ }
    }}
    onPointerMove={event => {
      if (!drawing.current || !canDraw) return
      const nextPoint = point(event)
      const previousPoint = draftRef.current[draftRef.current.length - 1]
      if (previousPoint) {
        const segment = [previousPoint, nextPoint]
        drawStroke(event.currentTarget.getContext('2d')!, segment, color, width)
        pendingLivePoints.current = pendingLivePoints.current
          ? [...pendingLivePoints.current, nextPoint]
          : [previousPoint, nextPoint]
        if (performance.now() - lastLiveSentAt.current >= 30) sendPendingLivePoints()
      }
      draftRef.current.push(nextPoint)
    }}
    onPointerUp={() => {
      if (!drawing.current) return
      sendPendingLivePoints()
      drawing.current = false
      const expectedOperations = Math.max(1, sentLiveOperations.current)
      pendingAcknowledgement.current = renderedSequence.current + expectedOperations
      if (draftRef.current.length === 1) onOperation('Stroke', draftRef.current)
    }}
    onPointerCancel={() => {
      drawing.current = false
      pendingAcknowledgement.current = null
      pendingLivePoints.current = null
      draftRef.current = []
    }}
  />
}

function applyOperation(context: CanvasRenderingContext2D, operation: DrawingOperation) {
  if (operation.operationType === 'Fill') {
    context.fillStyle = operation.color
    context.fillRect(0, 0, context.canvas.width, context.canvas.height)
  } else if (operation.operationType === 'Clear') {
    context.fillStyle = '#fffdf8'
    context.fillRect(0, 0, context.canvas.width, context.canvas.height)
  } else if (operation.operationType === 'Stroke') {
    drawStroke(context, operation.points, operation.color, operation.width)
  }
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
