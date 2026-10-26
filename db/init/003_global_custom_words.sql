-- Custom words are global and no longer belong to a room.
-- Keep room_id for compatibility with existing game/history rows, but stop
-- using it to select or create words.
ALTER TABLE words DROP CONSTRAINT IF EXISTS words_check;
UPDATE words SET room_id = NULL WHERE source = 'Custom';

CREATE INDEX IF NOT EXISTS words_global_selection_idx
    ON words (difficulty, active, source);
