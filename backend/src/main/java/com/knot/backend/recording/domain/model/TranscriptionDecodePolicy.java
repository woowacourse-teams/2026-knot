package com.knot.backend.recording.domain.model;

/**
 * AI 구간 전사의 디코딩 횟수. 녹음 중 구간은 고정 용어 디코딩을 한 번 더 해 용어만 합치고, 종료 후 사용자가 기다리는 마지막 구간은
 * 기본 디코딩만 쓴다.
 */
public enum TranscriptionDecodePolicy {
    DEFAULT_ONLY,
    WITH_VOCABULARY
}
