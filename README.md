# Pictionary backend

Scala/Play backend for the multiplayer Pictionary application.

## Run with Docker

```bash
docker compose up --build
```

The backend health endpoint is available at <http://localhost:9000/health>.
The React frontend is available at <http://localhost:5173>.

The frontend lives in [`frontend/`](./frontend/) and uses Vite, React, and TypeScript. Its development proxy forwards `/api` and WebSocket traffic to the Play backend container.
The main browser is the shared lobby/display. The painter scans the lobby QR code to open the phone controller, where they select difficulty, see the secret word, draw, confirm guesses, and end turns.

Word endpoints are available at:

- `GET /api/rooms/:roomId/words/:difficulty`
- `POST /api/rooms/:roomId/words` with `{ "text": "Space Ship", "difficulty": "Simple" }`

Room endpoints are available at:

- `POST /api/rooms` with `{ "name": "Alice" }`
- `GET /api/rooms/:code`
- `POST /api/rooms/:code/join` with `{ "name": "Bob" }`
- `GET /api/rooms/:code/ws?playerId=...` for the room WebSocket connection.
- `POST /api/games/:gameId/turns/:turnNumber/image?playerId=...` with a PNG request body.
- `GET /api/games/:gameId/gallery` for ordered finished-turn images and answers.
- `GET /api/games/:gameId/turns/:turnNumber/image` to retrieve a finished image.

The local PostgreSQL service and a persistent media volume are included in Compose. Finished images use the local filesystem adapter at `/data/media`; this can later be replaced with S3/MinIO without changing the game service.

## Current scope

The backend currently includes:

- Scala/Play application bootstrap and health checks.
- PostgreSQL word storage with seeded built-in words.
- Four difficulty levels with difficulty-filtered selection.
- Room-specific custom-word creation and validation.
- PostgreSQL-backed room creation, player joining, game state, turns, scores, and drawing operations.
- Room-scoped WebSocket connections and broadcast transport.
- Server-authoritative game commands, turn rotation, scoring, and private word delivery to the artist.
- Reconnect handling that resends current game state and the active drawing snapshot.
- Finished PNG upload, SQL image metadata, persistent image storage, and ordered gallery endpoints.

Drawing WebSocket messages support `Stroke`, `Fill`, `Clear`, and `Undo`. A `Fill` operation paints the entire canvas with the supplied color.

Drawing WebSocket messages:

```json
{
  "type": "drawing.operation",
  "playerId": "artist-id",
  "operationType": "Stroke",
  "points": [{ "x": 10, "y": 20 }],
  "color": "#000000",
  "width": 4
}
```

Use `{ "type": "drawing.snapshot_request", "playerId": "player-id" }` to request the current turn’s drawing operations. The server assigns operation IDs and sequence numbers.

The database initialization scripts run automatically for a new PostgreSQL volume. For an existing development volume, apply a changed script explicitly, for example:

```bash
docker compose exec -T postgres psql -U pictionary -d pictionary < db/init/002_game_state.sql
```
