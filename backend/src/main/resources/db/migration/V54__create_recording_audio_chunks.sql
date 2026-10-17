ALTER TABLE recording_sessions
    ADD COLUMN audio_mode VARCHAR(30) NOT NULL DEFAULT 'FINAL_WEBM_V1',
    ADD CONSTRAINT chk_recording_sessions_audio_mode
        CHECK (audio_mode IN ('FINAL_WEBM_V1', 'PCM16_CHUNKS_V1'));

CREATE TABLE recording_audio_streams (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT NOT NULL,
    next_sequence INTEGER NOT NULL DEFAULT 1,
    total_frame_count BIGINT NOT NULL DEFAULT 0,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_recording_audio_streams_recording FOREIGN KEY (recording_id)
        REFERENCES recording_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT uk_recording_audio_streams_recording UNIQUE (recording_id),
    CONSTRAINT chk_recording_audio_streams_sequence CHECK (next_sequence BETWEEN 1 AND 115200001),
    CONSTRAINT chk_recording_audio_streams_frames CHECK (total_frame_count BETWEEN 0 AND 115200000),
    CONSTRAINT chk_recording_audio_streams_progress CHECK (
        (next_sequence = 1 AND total_frame_count = 0)
        OR (next_sequence > 1 AND total_frame_count BETWEEN next_sequence - 1 AND (next_sequence - 1)::BIGINT * 80000)
    )
);

CREATE TABLE recording_audio_chunks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recording_id BIGINT NOT NULL,
    sequence INTEGER NOT NULL,
    sample_offset BIGINT NOT NULL,
    frame_count INTEGER NOT NULL,
    byte_length BIGINT NOT NULL,
    checksum_sha256 VARCHAR(44) NOT NULL,
    storage_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    storage_version_id TEXT,
    verified_at TIMESTAMPTZ,
    upload_url_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_recording_audio_chunks_recording FOREIGN KEY (recording_id)
        REFERENCES recording_sessions (id) ON DELETE RESTRICT,
    CONSTRAINT uk_recording_audio_chunks_recording_sequence UNIQUE (recording_id, sequence),
    CONSTRAINT uk_recording_audio_chunks_storage_key UNIQUE (storage_key),
    CONSTRAINT chk_recording_audio_chunks_sequence CHECK (sequence BETWEEN 1 AND 115200000),
    CONSTRAINT chk_recording_audio_chunks_frames CHECK (frame_count BETWEEN 1 AND 80000),
    CONSTRAINT chk_recording_audio_chunks_bytes CHECK (byte_length = frame_count::BIGINT * 2),
    CONSTRAINT chk_recording_audio_chunks_range CHECK (
        sample_offset >= 0 AND sample_offset <= 115200000 - frame_count
    ),
    CONSTRAINT chk_recording_audio_chunks_checksum CHECK (checksum_sha256 ~ '^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$'),
    CONSTRAINT chk_recording_audio_chunks_storage_key CHECK (length(btrim(storage_key)) > 0),
    CONSTRAINT chk_recording_audio_chunks_status CHECK (status IN ('RESERVED', 'VERIFIED')),
    CONSTRAINT chk_recording_audio_chunks_verification CHECK (
        (status = 'RESERVED' AND storage_version_id IS NULL AND verified_at IS NULL)
        OR (status = 'VERIFIED' AND storage_version_id IS NOT NULL
            AND length(btrim(storage_version_id)) > 0 AND storage_version_id <> 'null' AND verified_at IS NOT NULL)
    )
);
