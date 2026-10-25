# Classic Pictionary — Design Document

## 1. Product summary

This project is a multiplayer, web-based drawing-and-guessing game inspired by Netflix’s Pictionary-style experience. The first release will support only **Classic mode**:

- Players join a room.
- One player is the artist for each turn.
- The artist receives a secret word and draws it on a shared canvas.
- Players shout guesses aloud outside the application.
- The painter confirms when a player has guessed correctly and selects that player in the UI.
- Confirmed correct guesses earn points, with earlier confirmations receiving more points.
- Turns continue until every player has had the configured number of turns.
- The highest-scoring player wins.

The game also supports a pool of custom words. A room owner can add words to that room’s pool and choose whether a round uses the built-in word list, custom words, or both.

## 1.1 Technology decisions

The implementation will use:

- **Frontend:** React with TypeScript, built with Vite.
- **Backend:** Scala 3 with Play Framework and Pekko WebSockets.
- **Database:** PostgreSQL, accessed through the Scala persistence layer.
- **Image storage:** S3-compatible object storage, using MinIO in Docker locally and a managed S3-compatible service in production.
- **Build and tests:** sbt for Scala code, npm-compatible tooling for the React app, and ScalaTest or MUnit plus frontend tests.
- **Runtime:** All services run in Docker containers from the beginning, orchestrated locally with Docker Compose.

React is a good fit because the game is primarily a component-based interactive UI: lobby, difficulty picker, canvas, player selector, timer, and scoreboard. The browser client communicates with the Scala server using HTTPS and WebSockets.

All frontend application code will use TypeScript: `.ts` for non-UI modules and `.tsx` for React components. Shared client state, WebSocket messages, drawing operations, API responses, and component props should have explicit TypeScript types. Plain JavaScript should not be used for new frontend code.

### Container baseline

The initial `docker-compose.yml` will define these services:

```text
frontend   React/Vite development or production web container
backend    Scala/Play API and WebSocket container
postgres   PostgreSQL database with a persistent named volume
objectstore MinIO object-storage container with a persistent named volume
```

The frontend talks to the backend through the Compose network rather than `localhost`, and the backend talks to PostgreSQL and MinIO through their service names. Environment-specific configuration belongs in Compose files and environment files, not in application source code.

The containers should support both fast development and deployment-like execution:

- Development images mount source code and run hot reloaders where practical.
- Production images build immutable frontend assets and a packaged Scala application.
- Database data lives in a named volume and is not stored in the repository.
- Database migrations run as an explicit backend or migration-container step before the application accepts traffic.
- Health checks ensure the backend waits for PostgreSQL readiness rather than merely waiting for the database container to start.

The standard commands should be documented as `docker compose up --build`, `docker compose down`, and a safe database reset command for local development.

## 2. Goals and non-goals

### Goals for the first version

- Create and join private game rooms.
- Support a configurable number of players, rounds, and turn duration.
- Provide a shared drawing canvas with low-latency updates.
- Keep the current word secret from everyone except the artist.
- Let the painter confirm correct guesses and select the credited player.
- Maintain scores and determine a winner.
- Allow room-specific custom words.
- Recover gracefully from refreshes, reconnects, and a player leaving.

### Non-goals for the first version

- Teams.
- Multiple game modes or special cards.
- Voice or video chat.
- Public matchmaking.
- Moderation tooling beyond basic word validation and room controls.
- Persistent player accounts, unless authentication is needed by the chosen deployment platform.
- Mobile-native applications.

## 3. Core user experience

### 3.0 Phone and mobile-browser support

The first mobile target is a responsive React web app. A player should be able to open a room link on a phone browser, enter a nickname, draw, or be selected as the credited player, and remain connected to the same game as desktop players.

The UI must therefore:

- Use responsive layouts with touch-friendly controls.
- Use Pointer Events for drawing so mouse, stylus, and touch input share one implementation.
- Keep the canvas sized to the device viewport while preserving drawing coordinates when the device rotates.
- Avoid hover-only interactions and small click targets.
- Reconnect the WebSocket after temporary mobile network changes.
- Keep the artist’s word and difficulty visible without requiring desktop-sized panels.

The app can later be made installable as a Progressive Web App (PWA), giving users a home-screen icon and standalone launch experience where the browser supports it. PWA installation requires an appropriate web manifest and secure hosting such as HTTPS.

This does not require a separate mobile backend. Phone browsers use the same HTTP endpoints, WebSocket protocol, game rules, and PostgreSQL data as desktop browsers.

If a native iOS/Android application is needed later, React Native with Expo is the likely follow-up. The backend protocol and domain rules can be reused, while the React web components themselves would be replaced with native components.

### 3.1 Room creation

The room creator becomes the room owner and chooses:

- Room name or generated room code.
- Maximum player count.
- Number of turns per player, or total rounds.
- Turn duration.
- Word source: built-in, custom, or combined.
- Whether spectators are allowed after a player is eliminated or disconnected.

The owner can manage the custom-word pool before starting the game.

### 3.2 Lobby

The lobby displays:

- Room code and share/join link.
- Connected players and readiness state.
- Current game settings.
- Custom-word controls for the room owner.
- Start button, enabled only when the room has enough players.

Players should be able to reconnect to the lobby using a short-lived session token stored by the client.

### 3.3 Turn flow

Each turn follows these states:

1. `TURN_STARTING` — announce the artist and show a short countdown.
2. `WORD_SELECTION` — select a word from the configured pool; show it only to the artist.
3. `DRAWING` — start the timer and broadcast drawing operations.
4. `TURN_COMPLETE` — reveal the answer, award points, and show turn results.
5. `NEXT_TURN` — rotate to the next artist or advance the round.
6. `GAME_COMPLETE` — show the final ranking and allow a rematch.

The artist may clear the canvas, undo their own recent stroke, select a player who has shouted the correct answer, and end the turn early. The application does not capture or evaluate spoken guesses. A painter confirmation is broadcast to the room and recorded as the authoritative scoring event.

### 3.4 Difficulty and scoring

Every word has one of four difficulty levels:

- `SIMPLE`
- `MEDIUM`
- `HARD`
- `IMPOSSIBLE`

The artist chooses the difficulty before the word is assigned. The selected difficulty is visible to every player during the turn and is used by the scoring rules. The artist should see the available difficulty buttons and, where useful, a short description of the expected challenge.

The initial scoring model should be simple and deterministic:

- A painter-confirmed player receives a difficulty-weighted base score multiplied by the remaining-time percentage.
- A small placement bonus rewards earlier confirmations in the same turn.
- The artist receives a difficulty-weighted participation score when at least one player is confirmed.
- A player can score only once per turn.

Example formula:

```text
difficultyMultiplier = {
  SIMPLE: 1.0,
  MEDIUM: 1.5,
  HARD: 2.0,
  IMPOSSIBLE: 3.0
}

confirmedPlayerScore = round(basePoints * difficultyMultiplier * remainingTimeRatio)
                     + placementBonus
artistScore  = round(artistParticipationPoints * difficultyMultiplier)
               if at least one player is confirmed
```

The exact values should live in server-side configuration rather than being embedded in UI code. The server includes the selected difficulty and resulting scores in the relevant events; clients never calculate authoritative scores.

## 4. Word system

### Word records

All words, including the initial built-in catalogue and later room custom words, are stored in PostgreSQL. Each word should contain:

- A normalized answer used for deduplication and administration.
- A display answer used when revealing the word.
- A required difficulty: `SIMPLE`, `MEDIUM`, `HARD`, or `IMPOSSIBLE`.
- A source: `BUILT_IN` or `CUSTOM`.
- Optional category metadata.

### Custom words

Custom words belong to a room. A custom word should contain:

- Original display text.
- Normalized text used for deduplication.
- Creator/player identifier.
- Created timestamp.
- Active/deleted status.
- Chosen difficulty.

Custom words must be validated before insertion. Reject empty words, excessive length, unsupported control characters, and obviously invalid entries. The first version can use a simple blacklist and owner-only deletion; stronger moderation can be added later. Custom-word creation and deletion should be transactional SQL operations.

### Word selection

At the start of `WORD_SELECTION`:

1. The artist chooses a difficulty.
2. Build the eligible pool from active words in that difficulty and the room’s configured sources.
3. Remove words used recently in the same game where possible.
4. Randomly select one word using a server-side random source and a SQL query/repository.
5. Store the selected word ID and difficulty on the turn record.
6. Send the plaintext answer only to the artist’s private channel.

The server must be authoritative. The client must never receive the answer as part of general room state.

## 5. Relational database design

PostgreSQL is the system of record for rooms, players, games, turns, words, painter confirmations, scores, and drawing recovery snapshots. The application may keep active game state in memory for fast realtime updates, but important state changes should be persisted through repositories and transactions.

### Core tables

```sql
CREATE TYPE word_difficulty AS ENUM ('SIMPLE', 'MEDIUM', 'HARD', 'IMPOSSIBLE');
CREATE TYPE word_source AS ENUM ('BUILT_IN', 'CUSTOM');

CREATE TABLE rooms (
  id UUID PRIMARY KEY,
  code VARCHAR(12) NOT NULL UNIQUE,
  owner_player_id UUID,
  status VARCHAR(20) NOT NULL,
  word_source_mode VARCHAR(20) NOT NULL,
  max_players INTEGER NOT NULL,
  turns_per_player INTEGER NOT NULL,
  turn_duration_seconds INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE players (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  display_name VARCHAR(32) NOT NULL,
  session_token_hash TEXT NOT NULL,
  connected BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (room_id, display_name)
);

ALTER TABLE rooms
  ADD CONSTRAINT rooms_owner_fk
  FOREIGN KEY (owner_player_id) REFERENCES players(id) ON DELETE SET NULL;

CREATE TABLE words (
  id UUID PRIMARY KEY,
  room_id UUID REFERENCES rooms(id) ON DELETE CASCADE,
  source word_source NOT NULL,
  display_text VARCHAR(120) NOT NULL,
  normalized_text VARCHAR(120) NOT NULL,
  difficulty word_difficulty NOT NULL,
  created_by_player_id UUID REFERENCES players(id) ON DELETE SET NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((source = 'CUSTOM' AND room_id IS NOT NULL) OR
         (source = 'BUILT_IN' AND room_id IS NULL)),
  UNIQUE (room_id, normalized_text, difficulty)
);

CREATE TABLE games (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  status VARCHAR(20) NOT NULL,
  current_round INTEGER NOT NULL DEFAULT 1,
  current_turn INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

CREATE TABLE game_players (
  game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  score INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (game_id, player_id)
);

CREATE TABLE turns (
  id UUID PRIMARY KEY,
  game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  artist_player_id UUID NOT NULL REFERENCES players(id),
  word_id UUID NOT NULL REFERENCES words(id),
  difficulty word_difficulty NOT NULL,
  turn_number INTEGER NOT NULL,
  started_at TIMESTAMPTZ,
  ends_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  UNIQUE (game_id, turn_number)
);

CREATE TABLE turn_confirmations (
  id UUID PRIMARY KEY,
  turn_id UUID NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
  confirmed_player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  confirmed_by_artist_id UUID NOT NULL REFERENCES players(id),
  placement INTEGER NOT NULL,
  score_awarded INTEGER NOT NULL DEFAULT 0,
  confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (turn_id, confirmed_player_id),
  UNIQUE (turn_id, placement)
);
```

The schema is illustrative; migrations should create the final version incrementally. Add indexes on `words (room_id, difficulty, active)`, `turns (game_id, turn_number)`, and `turn_confirmations (turn_id, confirmed_at)`.

### Data ownership rules

- Built-in words have `source = BUILT_IN` and no `room_id`.
- Custom words have `source = CUSTOM` and exactly one owning room.
- A game records the selected `word_id` and difficulty on each turn so historical results remain stable if a word is later deactivated.
- A turn stores the chosen difficulty even though it is also present on the word, making the game record self-describing and protecting historical scoring from future catalogue changes.
- Each painter confirmation records the selected player, the artist who confirmed it, the confirmation order, and the awarded score.
- Confirmation records and score changes are applied to `game_players` in the same transaction.

## 6. Finished drawing storage and end-of-game gallery

At the end of each turn, the finished canvas is persisted as a PNG. The image bytes should not be stored inside PostgreSQL or a document database. They belong in object storage, which is designed for unstructured files and provides a portable bucket/key model. MinIO provides an S3-compatible object store for the Docker environment, allowing the application to use the same storage API locally and in production.

The SQL database stores the relationship and metadata. The `turn_id` links the image to the artist, selected word, difficulty, game, and room through the existing relational tables.

```sql
CREATE TABLE turn_images (
  id UUID PRIMARY KEY,
  turn_id UUID NOT NULL UNIQUE REFERENCES turns(id) ON DELETE CASCADE,
  storage_bucket VARCHAR(100) NOT NULL,
  storage_key VARCHAR(500) NOT NULL UNIQUE,
  content_type VARCHAR(100) NOT NULL DEFAULT 'image/png',
  byte_size INTEGER NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Image persistence flow

1. The React canvas keeps the current drawing locally and mirrors drawing operations through the WebSocket.
2. When the turn ends, the artist’s client exports the canvas to a PNG.
3. The artist uploads the PNG to a backend endpoint associated with the active turn.
4. The backend verifies that the request comes from the current artist, validates the image type and size, and writes the object to MinIO using a key such as `games/{gameId}/turns/{turnId}.png`.
5. The backend inserts the object metadata into `turn_images` in PostgreSQL.
6. The backend broadcasts that the turn image is ready, then advances the game.

The upload and metadata insert must be handled as a recoverable workflow. If the database insert fails after the object upload, the object should be cleaned up or marked for later cleanup. If the upload fails, the turn should still be able to complete with a placeholder and a retryable image-save status rather than blocking the entire game indefinitely.

### Rolling end-of-game display

When the game completes, the backend returns the gallery in turn order. Each gallery item includes the image reference and the answer metadata joined from `turns` and `words`:

```text
GameGalleryItem {
  turnNumber
  imageUrl
  displayWord
  difficulty
  artistPlayerId
  artistDisplayName
}
```

The React results screen renders these items as a rolling display or carousel. Images should be served through short-lived signed URLs or a backend image endpoint rather than exposing unrestricted object-store credentials. The answer is revealed only after the turn is complete, so the artist’s secret remains protected during play.

## 7. Suggested high-level architecture

Use a client/server architecture with an authoritative realtime game server.

```text
Browser clients
    |
    | HTTPS: lobby, settings, custom words
    | WebSocket: game state, confirmations, drawing operations
    v
Application server
    |
    +-- Room service       creates rooms and manages membership
    +-- Game service       owns state machine, turns, scoring, timers
    +-- Word service       serves built-in and custom word pools
    +-- Realtime gateway   authenticates and broadcasts events
    +-- Persistence        stores relational game data in PostgreSQL
    +-- Media service      stores finished PNGs in MinIO/S3-compatible storage
```

The game service should be the single authority for turn state, timers, painter confirmations, score changes, and word selection. Clients render state and send intentions; they do not decide outcomes.

### 6.1 Multiplayer and concurrency model

The backend is designed to support multiple players in the same room and multiple rooms at the same time. WebSockets provide the persistent, bidirectional connection needed to broadcast events such as drawing operations, painter confirmations, timer updates, score changes, and player presence.

WebSockets are the communication layer, not the multiplayer model by themselves. The server must also:

- Maintain a separate room and game state for every active room.
- Track each player’s connection, role, readiness, score, and reconnect token.
- Route each incoming command to the correct room and game.
- Validate commands against the current phase and player role.
- Serialize state-changing commands within a room so two confirmation actions cannot award the same player points twice.
- Broadcast approved state changes only to players in the relevant room.
- Send private events, such as the word, only to the artist’s connection.
- Persist important transitions and score changes in PostgreSQL.

For the first deployment, a single Scala server instance can hold active room state in memory while PostgreSQL stores durable game data. This is sufficient for development and an initial small deployment. If we later run multiple backend instances, connections from the same room must share state through a distributed coordination layer, such as a shared room-state service or realtime broker. PostgreSQL remains the durable source of truth.

Conceptually, a room behaves like this:

```text
Player A ─┐
Player B ─┼─ WebSocket connections ─> Room actor/state owner ─> Game rules
Player C ─┘                                      │
                                                 ├─ broadcast approved events
                                                 └─ persist durable changes to SQL
```

A player’s browser does not connect directly to another player. Every drawing operation, painter confirmation, and game command passes through the server, which prevents clients from changing scores or seeing another player’s secret word.

## 8. Proposed code structure

The frontend/backend choices are React with TypeScript and Scala with Play, respectively. A feature-oriented structure keeps game rules independent from transport and UI details.

```text
/
├── DESIGN.md
├── README.md
├── build.sbt
├── project/
│   └── build.properties
├── docker-compose.yml
├── apps/
│   ├── client/
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── Dockerfile
│   │   ├── public/
│   │   │   └── manifest.webmanifest  # PWA metadata
│   │   ├── src/
│   │   │   ├── app/                  # .tsx routing, providers, app bootstrap
│   │   │   ├── features/
│   │   │   │   ├── lobby/            # Room creation, join, ready state
│   │   │   │   ├── game/             # Turn UI, timer, scores, results
│   │   │   │   ├── drawing/          # Canvas input, pointer events, rendering
│   │   │   │   ├── confirmations/   # Painter selects correct player and history
│   │   │   │   └── custom-words/     # Owner word-pool management
│   │   │   ├── components/           # Shared presentational components
│   │   │   ├── realtime/             # WebSocket client and event adapters
│   │   │   ├── state/                # Client state and selectors
│   │   │   └── styles/
│   │   └── tests/
│   └── server/
│       ├── Dockerfile
│       ├── src/
│       │   ├── app/                  # Server bootstrap and configuration
│       │   ├── features/
│       │   │   ├── rooms/            # Room lifecycle and membership
│       │   │   ├── games/            # Game aggregate and state machine
│       │   │   ├── turns/            # Turn rotation, timers, completion
│       │   │   ├── confirmations/   # Painter-confirmed players and scoring
│       │   │   ├── words/            # Built-in/custom word selection
│       │   │   ├── media/            # Canvas export and object-storage uploads
│       │   │   └── players/          # Sessions, reconnects, presence
│       │   ├── realtime/             # WebSocket connection and broadcasts
│       │   ├── http/                 # REST endpoints and validation
│       │   ├── persistence/          # SQL repositories and database adapters
│       │   ├── migrations/           # Versioned PostgreSQL migrations
│       │   └── security/             # Authorization and rate limiting
│       └── tests/
├── packages/
│   ├── protocol/                     # Client/server event schemas
│   └── test-utils/                   # Fixtures, fake clocks, test clients
└── db/
    └── migrations/                  # SQL schema and seed-word migrations
```

The most important boundary is the server-side domain layer: scoring, word normalization, turn transitions, and validation should be pure Scala functions where possible. The protocol package defines the JSON contract consumed by both React and Scala. Keeping the protocol separate prevents the browser from becoming coupled to database or framework types.

## 9. Domain model

### Room

```text
Room {
  id
  code
  ownerPlayerId
  status: LOBBY | IN_GAME | COMPLETE
  settings
  playerIds[]
  createdAt
}
```

### Game

```text
Game {
  id
  roomId
  phase
  roundNumber
  turnNumber
  currentArtistId
  currentWordId
  currentDifficulty: SIMPLE | MEDIUM | HARD | IMPOSSIBLE
  turnStartedAt
  turnEndsAt
  players: [{ playerId, score, hasBeenConfirmedCurrentTurn }]
}
```

The database is the source of truth for word ownership and history. The in-memory game aggregate may cache eligible word IDs and recent selections, but it must reload them from SQL when starting or recovering a game.

### Word and scoring types

```text
WordDifficulty = SIMPLE | MEDIUM | HARD | IMPOSSIBLE
WordSource     = BUILT_IN | CUSTOM

Word {
  id
  roomId: optional
  source
  displayText
  normalizedText
  difficulty
  active
}

Turn {
  id
  gameId
  artistPlayerId
  wordId
  difficulty
  startedAt
  endsAt
}
```

The plaintext current answer should be kept server-side and delivered only through a private artist event. It should not be included in a broadcast game object.

### Drawing operation

Represent drawing as compact, ordered operations rather than transmitting full canvas images:

```text
DrawOperation {
  operationId
  turnId
  playerId
  type: STROKE | CLEAR | UNDO
  points[]
  color
  width
  sequence
}
```

The server validates that only the current artist can create drawing operations. Clients can periodically request or receive a snapshot to recover from dropped operations.

## 10. Realtime protocol

Transport can be WebSockets or a framework equivalent. Events should be schema-validated and versioned.

### Client-to-server events

- `room.create`
- `room.join`
- `room.leave`
- `room.ready`
- `room.update_settings`
- `custom_word.add`
- `custom_word.remove`
- `game.start`
- `turn.choose_difficulty`
- `turn.confirm_player`
- `turn.end`
- `drawing.operation`
- `game.rematch`

### Server-to-client events

- `room.state`
- `player.joined`
- `player.left`
- `game.phase_changed`
- `turn.artist_announced`
- `turn.difficulty_selected`
- `turn.word_assigned` — private to the artist
- `drawing.operation`
- `drawing.snapshot`
- `turn.player_confirmed`
- `turn.image_ready`
- `scores.updated`
- `turn.completed`
- `game.completed`
- `error`

Every event should include a game/room identifier, event ID, server timestamp, and protocol version. Commands should be idempotent where practical so retries do not duplicate scoring or drawing actions.

## 11. HTTP endpoints

HTTP is appropriate for non-realtime operations:

```text
POST   /api/rooms
GET    /api/rooms/{code}
POST   /api/rooms/{code}/join
GET    /api/rooms/{code}/custom-words
POST   /api/rooms/{code}/custom-words
DELETE /api/rooms/{code}/custom-words/{wordId}
POST   /api/games/{gameId}/turns/{turnId}/image
GET    /api/games/{gameId}/gallery
```

The relevant request and event payloads include difficulty:

```text
POST /api/rooms/{code}/custom-words
{
  "displayText": "volcano",
  "difficulty": "HARD"
}

turn.choose_difficulty
{
  "difficulty": "IMPOSSIBLE"
}

turn.confirm_player
{
  "playerId": "player-uuid"
}
```

The lobby and game UI should display the four difficulty options, the selected difficulty on the active turn, and the corresponding score multiplier. The server validates that the chosen difficulty has eligible active words before starting the turn.

The live game should use the realtime connection after joining. Authorization must be checked on both HTTP requests and realtime commands.

## 12. Reliability and security requirements

- The server owns all game timers; clients display server time and may animate locally.
- Validate every command against the current phase and player role.
- Rate-limit painter confirmations, custom-word writes, and drawing operations.
- Never broadcast the answer before the turn ends to players who are not the artist.
- Use a reconnect token to restore a player to the same room slot.
- On artist disconnect, pause briefly, then either reassign the turn or end it according to room settings.
- On a player disconnect, retain their score and mark them absent until reconnect or game completion.
- Persist enough state to recover an in-progress game after a server restart, or explicitly end the game with a recoverable room result in the first deployment.
- Execute word selection, turn creation, and initial turn state changes in a transaction so a selected word cannot be assigned to two active turns.
- Use parameterized SQL through the repository layer; never concatenate player input into SQL statements.
- Accept only bounded PNG uploads from the current artist for the active turn; validate content type, dimensions, and byte size on the server.
- Keep object storage credentials server-side and expose gallery images through short-lived signed URLs or an authorized backend endpoint.
- Clean up orphaned objects when an image metadata transaction fails, with a periodic reconciliation job as a fallback.

## 13. Testing strategy

### Unit tests

- Word normalization and duplicate prevention.
- Painter confirmation validation and duplicate handling.
- Score calculation.
- Turn rotation and phase transitions.
- Custom-word validation.
- Difficulty-specific word selection and score multipliers.
- SQL repository constraints for built-in versus room-owned custom words.
- Image upload, object metadata persistence, and gallery ordering.
- Permission checks for artist and room owner actions.

### Integration tests

- Create/join/start room flow.
- Private delivery of the word to the artist.
- Drawing broadcast and snapshot recovery.
- Painter-confirmed player scoring and duplicate confirmation rejection.
- A completed game returns all turn images with their revealed words in turn order.
- Reconnect during lobby and during a turn.
- Custom words included in selection.
- A selected difficulty is rendered consistently for the artist and players.

### End-to-end tests

- Two or more browser clients complete a full game.
- A player cannot read the answer from general room state.
- A game reaches a deterministic final leaderboard.
- A phone-sized viewport can join, draw, be selected as the credited player, and reconnect during a game.

Use a fake clock for game-service tests so timers can be tested quickly and deterministically.

## 14. MVP implementation order

1. Define shared domain types, including `WordDifficulty`, phases, events, and validation schemas.
2. Create PostgreSQL migrations, seed built-in words, and implement SQL repositories.
3. Add MinIO/S3-compatible object storage, image metadata migrations, and upload repositories.
4. Implement room creation, joining, lobby presence, and ready state.
5. Implement difficulty selection and the server-side game state machine.
6. Implement the canvas and realtime drawing operations.
7. Implement painter confirmations, difficulty-weighted scoring, turn image persistence, and final results.
8. Implement the rolling end-of-game gallery with revealed words.
9. Implement room custom-word management and difficulty-filtered selection.
10. Add reconnect handling, rate limits, transaction safety, orphan cleanup, and persistence recovery.
11. Add integration and browser end-to-end coverage.

## 15. Open decisions before implementation

- Choose the SQL library for the Scala persistence layer (Slick or Doobie).
- Should rooms be anonymous with a nickname, or require accounts?
- What are the minimum and maximum player counts?
- Should one game mean one turn per player or multiple rounds by default?
- How long should turns and result screens last?
- Should custom words and their difficulty labels be visible to all room members before the game starts?
- What behavior should occur when the artist disconnects?
- Is the first deployment expected to support multiple server instances? If so, a shared realtime broker and distributed room state will be needed.

