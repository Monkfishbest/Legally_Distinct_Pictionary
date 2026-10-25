CREATE TABLE IF NOT EXISTS rooms (
    code VARCHAR(6) PRIMARY KEY,
    owner_id UUID NOT NULL,
    turn_duration_seconds INTEGER NOT NULL,
    turns_per_player INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS room_players (
    id UUID PRIMARY KEY,
    room_code VARCHAR(6) NOT NULL REFERENCES rooms(code) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    UNIQUE (room_code, id)
);

CREATE TABLE IF NOT EXISTS games (
    id VARCHAR(100) PRIMARY KEY,
    room_code VARCHAR(6) NOT NULL REFERENCES rooms(code),
    phase VARCHAR(30) NOT NULL,
    artist_index INTEGER NOT NULL,
    turn_number INTEGER NOT NULL,
    difficulty VARCHAR(20),
    selected_word_id UUID REFERENCES words(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS turns (
    id UUID PRIMARY KEY,
    game_id VARCHAR(100) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL,
    artist_player_id UUID NOT NULL,
    word_id UUID REFERENCES words(id),
    difficulty VARCHAR(20),
    phase VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    UNIQUE (game_id, turn_number)
);

CREATE TABLE IF NOT EXISTS turn_confirmations (
    id UUID PRIMARY KEY,
    game_id VARCHAR(100) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL,
    player_id UUID NOT NULL,
    points INTEGER NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (game_id, turn_number, player_id)
);

CREATE TABLE IF NOT EXISTS drawing_operations (
    id UUID PRIMARY KEY,
    game_id VARCHAR(100) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL,
    player_id UUID NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    points_json TEXT NOT NULL,
    color VARCHAR(7) NOT NULL,
    width DOUBLE PRECISION NOT NULL,
    sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (game_id, turn_number, sequence)
);

CREATE TABLE IF NOT EXISTS turn_images (
    id UUID PRIMARY KEY,
    game_id VARCHAR(100) NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL,
    word_id UUID REFERENCES words(id),
    storage_key VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (game_id, turn_number)
);
