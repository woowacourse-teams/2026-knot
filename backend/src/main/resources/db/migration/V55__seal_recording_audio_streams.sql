ALTER TABLE recording_audio_streams
    ADD COLUMN seal_kind VARCHAR(20),
    ADD COLUMN sealed_at TIMESTAMPTZ,
    ADD COLUMN accept_until TIMESTAMPTZ,
    ADD COLUMN tail_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_recording_audio_stream_seal CHECK (
        (seal_kind IS NULL AND sealed_at IS NULL AND accept_until IS NULL AND tail_unknown = FALSE)
        OR (seal_kind IS NOT NULL AND seal_kind IN ('NORMAL', 'REMOTE', 'TIMEOUT', 'LIMIT') AND sealed_at IS NOT NULL
            AND accept_until IS NOT NULL AND accept_until = sealed_at + INTERVAL '120 seconds'
            AND tail_unknown = (seal_kind <> 'NORMAL'))
    );
CREATE INDEX idx_recording_audio_streams_sealed
    ON recording_audio_streams (sealed_at, recording_id)
    WHERE sealed_at IS NOT NULL AND frozen = FALSE;

UPDATE recording_audio_streams stream
SET seal_kind = CASE session.end_reason
        WHEN 'CONNECTION_EXPIRED' THEN 'TIMEOUT'
        WHEN 'MAX_DURATION' THEN 'LIMIT'
        ELSE 'REMOTE'
    END,
    sealed_at = session.ended_at,
    accept_until = session.ended_at + INTERVAL '120 seconds',
    tail_unknown = TRUE
FROM recording_sessions session
WHERE stream.recording_id = session.id AND session.status = 'ENDED';
