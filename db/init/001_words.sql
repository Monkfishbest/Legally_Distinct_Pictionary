CREATE TABLE IF NOT EXISTS words (
    id UUID PRIMARY KEY,
    display_text VARCHAR(100) NOT NULL,
    normalized_text VARCHAR(100) NOT NULL,
    difficulty VARCHAR(20) NOT NULL CHECK (difficulty IN ('Simple', 'Medium', 'Hard', 'Impossible')),
    source VARCHAR(20) NOT NULL CHECK (source IN ('BuiltIn', 'Custom')),
    room_id VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((source = 'BuiltIn' AND room_id IS NULL) OR (source = 'Custom' AND room_id IS NOT NULL)),
    UNIQUE (room_id, normalized_text, difficulty)
);

CREATE INDEX IF NOT EXISTS words_selection_idx
    ON words (room_id, difficulty, active);

INSERT INTO words (id, display_text, normalized_text, difficulty, source)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Cat', 'cat', 'Simple', 'BuiltIn'),
    ('00000000-0000-0000-0000-000000000002', 'Bicycle', 'bicycle', 'Medium', 'BuiltIn'),
    ('00000000-0000-0000-0000-000000000003', 'Volcano Eruption', 'volcano eruption', 'Hard', 'BuiltIn'),
    ('00000000-0000-0000-0000-000000000004', 'Quantum Entanglement', 'quantum entanglement', 'Impossible', 'BuiltIn')
ON CONFLICT (id) DO NOTHING;
