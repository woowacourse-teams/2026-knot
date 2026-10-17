package com.knot.backend.recording.domain;

import com.knot.backend.global.exception.ErrorCategory;
import com.knot.backend.global.exception.ErrorCode;
import lombok.Getter;

@Getter
public enum RecordingErrorCode implements ErrorCode {
    AI_OUTCOME_UNKNOWN(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_OUTCOME_UNKNOWN",
            "AI 실행 결과를 확인하지 못했습니다"
    ),
    INVALID_AUDIO_SEAL(
            ErrorCategory.INVALID_INPUT,
            "INVALID_AUDIO_SEAL",
            "오디오 종료 명세가 올바르지 않습니다"
    ),
    AUDIO_SEAL_CONFLICT(
            ErrorCategory.CONFLICT,
            "AUDIO_SEAL_CONFLICT",
            "이미 확정한 오디오 종료 명세와 다릅니다"
    ),
    AUDIO_ACCEPTANCE_CLOSED(
            ErrorCategory.CONFLICT,
            "AUDIO_ACCEPTANCE_CLOSED",
            "오디오 저장 확인 유예 시간이 끝났습니다"
    ),
    AUDIO_MODE_DISABLED(
            ErrorCategory.CONFLICT,
            "AUDIO_MODE_DISABLED",
            "녹음 중 오디오 저장 기능이 활성화되지 않았습니다"
    ),

    AUDIO_MODE_CONFLICT(
            ErrorCategory.CONFLICT,
            "AUDIO_MODE_CONFLICT",
            "녹음 오디오 모드가 기존 요청과 다릅니다"
    ),

    INVALID_AUDIO_CHUNK(
            ErrorCategory.INVALID_INPUT,
            "INVALID_AUDIO_CHUNK",
            "오디오 조각의 형식이나 크기가 올바르지 않습니다"
    ),

    AUDIO_CHUNK_CONFLICT(
            ErrorCategory.CONFLICT,
            "AUDIO_CHUNK_CONFLICT",
            "오디오 조각이 기존 예약과 다릅니다"
    ),

    AUDIO_SEQUENCE_GAP(
            ErrorCategory.CONFLICT,
            "AUDIO_SEQUENCE_GAP",
            "다음 오디오 조각 번호가 올바르지 않습니다"
    ),

    AUDIO_CHUNK_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "AUDIO_CHUNK_NOT_FOUND",
            "오디오 조각 예약을 찾을 수 없습니다"
    ),

    AUDIO_CHUNK_NOT_STORED(
            ErrorCategory.CONFLICT,
            "AUDIO_CHUNK_NOT_STORED",
            "오디오 조각이 저장소에 올라가지 않았습니다"
    ),

    AUDIO_CHUNK_MISMATCH(
            ErrorCategory.CONFLICT,
            "AUDIO_CHUNK_MISMATCH",
            "저장된 오디오 조각의 무결성을 확인할 수 없습니다"
    ),

    AUDIO_SAVE_FROZEN(
            ErrorCategory.CONFLICT,
            "AUDIO_SAVE_FROZEN",
            "오디오 저장 범위가 이미 확정되었습니다"
    ),

    TRANSCRIPTION_DISABLED(
            ErrorCategory.CONFLICT,
            "TRANSCRIPTION_DISABLED",
            "전사 기능이 활성화되지 않았습니다"
    ),

    TRANSCRIPTION_RETRY_NOT_ALLOWED(
            ErrorCategory.CONFLICT,
            "TRANSCRIPTION_RETRY_NOT_ALLOWED",
            "실패한 전사만 다시 요청할 수 있습니다"
    ),

    TRANSCRIPTION_BUSY(
            ErrorCategory.CONFLICT,
            "TRANSCRIPTION_BUSY",
            "다른 녹음의 전사가 진행 중입니다"
    ),

    AUDIO_DELETION_CONFLICT(
            ErrorCategory.CONFLICT,
            "AUDIO_DELETION_CONFLICT",
            "오디오 삭제 작업을 실행할 수 없습니다"
    ),

    INVALID_RECORDING_DATA(
            ErrorCategory.INVALID_INPUT,
            "INVALID_RECORDING_DATA",
            "녹음 요청 값이 올바르지 않습니다"
    ),

    INVALID_RECORDING_TIME(
            ErrorCategory.INVALID_INPUT,
            "INVALID_RECORDING_TIME",
            "녹음 시각이 올바르지 않습니다"
    ),

    RECORDING_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "RECORDING_NOT_FOUND",
            "녹음을 찾을 수 없습니다"
    ),

    RECORDING_CONTROL_DENIED(
            ErrorCategory.FORBIDDEN,
            "RECORDING_CONTROL_DENIED",
            "녹음을 제어할 권한이 없습니다"
    ),

    RECORDING_ALREADY_ENDED(
            ErrorCategory.CONFLICT,
            "RECORDING_ALREADY_ENDED",
            "이미 종료된 녹음입니다"
    ),

    RECORDING_ALREADY_DISCARDED(
            ErrorCategory.CONFLICT,
            "RECORDING_ALREADY_DISCARDED",
            "이미 폐기된 녹음입니다"
    ),

    RECORDING_NOT_ENDED(
            ErrorCategory.CONFLICT,
            "RECORDING_NOT_ENDED",
            "종료된 녹음만 오디오를 업로드할 수 있습니다"
    ),

    INVALID_AUDIO_UPLOAD(
            ErrorCategory.INVALID_INPUT,
            "INVALID_AUDIO_UPLOAD",
            "업로드할 오디오의 형식이나 크기가 올바르지 않습니다"
    ),

    AUDIO_UPLOAD_ALREADY_COMPLETED(
            ErrorCategory.CONFLICT,
            "AUDIO_UPLOAD_ALREADY_COMPLETED",
            "이미 오디오 업로드가 완료된 녹음입니다"
    ),

    AUDIO_UPLOAD_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "AUDIO_UPLOAD_NOT_FOUND",
            "오디오 업로드 예약을 찾을 수 없습니다"
    ),

    AUDIO_UPLOAD_NOT_COMPLETED(
            ErrorCategory.CONFLICT,
            "AUDIO_UPLOAD_NOT_COMPLETED",
            "예약한 오디오 파일이 저장소에 올라가지 않았습니다"
    ),

    AUDIO_STORAGE_UNAVAILABLE(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AUDIO_STORAGE_UNAVAILABLE",
            "오디오 저장소를 사용할 수 없습니다"
    ),

    ACTIVE_RECORDING_ALREADY_EXISTS(
            ErrorCategory.CONFLICT,
            "ACTIVE_RECORDING_ALREADY_EXISTS",
            "이미 진행 중인 녹음이 있습니다"
    ),

    RECORDING_START_REQUEST_CONFLICT(
            ErrorCategory.CONFLICT,
            "RECORDING_START_REQUEST_CONFLICT",
            "녹음 시작 요청이 기존 요청과 다릅니다"
    ),

    RECORDING_MEMBER_NOT_FOUND(
            ErrorCategory.UNAUTHORIZED,
            "RECORDING_MEMBER_NOT_FOUND",
            "인증된 멤버를 찾을 수 없습니다"
    ),

    RECORDING_CONTROL_HASH_FAILED(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "RECORDING_CONTROL_HASH_FAILED",
            "녹음 제어 증명을 처리할 수 없습니다"
    ),

    AI_BUSY(
            ErrorCategory.CONFLICT,
            "AI_BUSY",
            "다른 전사 작업이 실행 중입니다"
    ),
    AI_NOT_READY(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_NOT_READY",
            "전사 서버가 준비되지 않았습니다"
    ),
    AI_JOB_NOT_FOUND(
            ErrorCategory.NOT_FOUND,
            "AI_JOB_NOT_FOUND",
            "전사 작업을 찾을 수 없습니다"
    ),
    AI_AUTHENTICATION_FAILED(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_AUTHENTICATION_FAILED",
            "전사 서버 인증에 실패했습니다"
    ),
    AI_TIMEOUT(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_TIMEOUT",
            "전사 서버 응답 시간이 초과되었습니다"
    ),
    AI_UNAVAILABLE(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_UNAVAILABLE",
            "전사 서버를 사용할 수 없습니다"
    ),
    AI_INVALID_RESPONSE(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_INVALID_RESPONSE",
            "전사 서버 응답이 올바르지 않습니다"
    ),
    AI_INTERRUPTED(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_INTERRUPTED",
            "전사 서버 호출이 중단되었습니다"
    ),
    AI_INVALID_CONFIGURATION(
            ErrorCategory.INTERNAL_SERVER_ERROR,
            "AI_INVALID_CONFIGURATION",
            "전사 서버 설정이 올바르지 않습니다"
    );

    private final ErrorCategory category;
    private final String code;
    private final String message;

    RecordingErrorCode(
            ErrorCategory category,
            String code,
            String message
    ) {
        this.category = category;
        this.code = code;
        this.message = message;
    }
}
