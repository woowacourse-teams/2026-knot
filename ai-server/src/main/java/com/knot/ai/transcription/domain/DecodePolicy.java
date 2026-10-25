package com.knot.ai.transcription.domain;

/**
 * 구간 전사의 디코딩 횟수. 녹음 중 구간은 고정 용어 프롬프트로 한 번 더 디코딩해 용어만 합치고, 종료 후 기다리는 마지막 구간은 기본
 * 디코딩만 쓴다.
 */
public enum DecodePolicy {
    DEFAULT_ONLY,
    WITH_VOCABULARY
}
