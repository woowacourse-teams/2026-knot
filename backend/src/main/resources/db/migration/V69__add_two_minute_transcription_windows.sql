ALTER TABLE recording_sessions DROP CONSTRAINT chk_recording_incremental_pipeline;
ALTER TABLE recording_sessions ADD CONSTRAINT chk_recording_incremental_pipeline
    CHECK (incremental_pipeline_version IS NULL OR
        (audio_mode = 'PCM16_CHUNKS_V1'
            AND incremental_pipeline_version IN ('pcm-window-v1-30-5', 'pcm-window-v2-120-10')));

ALTER TABLE recording_transcription_windows ADD COLUMN decode_policy VARCHAR(20);
ALTER TABLE recording_transcription_windows ADD CONSTRAINT chk_transcription_window_decode_policy
    CHECK (decode_policy IS NULL OR decode_policy IN ('DEFAULT_ONLY', 'WITH_VOCABULARY'));
