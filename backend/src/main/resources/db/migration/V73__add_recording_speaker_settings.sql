ALTER TABLE recording_sessions ADD COLUMN speaker_count INTEGER
    CHECK (speaker_count BETWEEN 1 AND 10);

CREATE TABLE recording_speaker_names (
    recording_id BIGINT NOT NULL REFERENCES recording_sessions(id) ON DELETE CASCADE,
    speaker_number INTEGER NOT NULL CHECK (speaker_number > 0),
    display_name VARCHAR(200) NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 50),
    PRIMARY KEY (recording_id, speaker_number)
);
