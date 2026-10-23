/** 녹음 세션 상태 */
export type RecordingSessionStatus = "RECORDING" | "PAUSED" | "ENDED";

/** 현재 녹음의 화면용 진행 상태. 두 실패는 백엔드가 더하기로 한 값이에요 */
export type CurrentRecordingStatus =
  | "RECORDING"
  | "PAUSED"
  | "PROCESSING"
  | "TRANSCRIPTION_FAILED"
  | "DOCUMENT_FAILED";

export interface CurrentRecordingResponse {
  recordingId: number;
  status: CurrentRecordingStatus;
  /** ISO 8601 */
  startedAt: string;
  /** 조회 시점까지 누적 녹음 시간(ms) */
  elapsedMillis: number;
  /** 실패한 녹음을 다시 시도할 수 있는지. 실패 상태와 함께 더해진 값이라 그 전의 서버는 주지 않음 */
  retryable?: boolean;
}

export interface RecordingStartResponse {
  recordingId: number;
  status: RecordingSessionStatus;
  /** ISO 8601 */
  startedAt: string;
}

/** 전사 상태 */
export type RecordingTranscriptionStatus =
  "PROCESSING" | "SUCCEEDED" | "FAILED";

/** 주제 분류와 주제별 문서 생성을 합친 상태 */
export type RecordingDocumentGenerationStatus =
  "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "NO_CONTENT";

export interface RecordingDocumentGenerationResponse {
  status: RecordingDocumentGenerationStatus;
  /** 생성에 성공한 문서 수 */
  succeededCount: number;
  /** 보존 기한 안의 실패 작업 ID. 없으면 빈 배열 */
  failedJobIds: number[];
}

export interface RecordingDetailResponse {
  /** 세션 상태. `DISCARDED`는 서버가 버린 녹음 */
  status: RecordingSessionStatus | "DISCARDED";
  /** 일시정지를 뺀 서버 기준 녹음 시간(ms) */
  elapsedMillis: number;
  /** ISO 8601. 진행 중이면 null */
  endedAt: string | null;
  /** 진행 중이면 null */
  endReason: "USER_ENDED" | "CONNECTION_EXPIRED" | "MAX_DURATION" | null;
  /** 업로드를 예약하기 전이면 null */
  audioUploadStatus: RecordingAudioUploadStatus | null;
  /** 전사를 시도하기 전이면 null */
  transcriptionAttemptId: string | null;
  /** 전사를 시도하기 전이면 null */
  transcriptionStatus: RecordingTranscriptionStatus | null;
  /** 전사가 실패하지 않았으면 null */
  transcriptionFailureCode: string | null;
  /** 문서 생성이 접수되기 전이면 null */
  documentGeneration: RecordingDocumentGenerationResponse | null;
  /** WebM 녹음이면 null. PCM 조각 저장 전의 서버는 주지 않음 */
  sourceAudio?: RecordingSourceAudio | null;
}

export interface RecordingTranscriptionRetryResponse {
  recordingId: number;
  /** 접수한 새 전사 시도의 UUID */
  attemptId: string;
  /** 다른 전사가 실행 중이면 QUEUED로 기다림 */
  transcriptionStatus: RecordingTranscriptionStatus | "QUEUED";
}

/** 녹음 상세 mock 하나. 서버 응답에는 녹음 ID가 없어서, 어느 녹음의 응답인지 함께 적어요 */
export interface RecordingDetailEntry {
  recordingId: number;
  detail: RecordingDetailResponse;
}

export interface RecordingPauseResponse {
  recordingId: number;
  status: RecordingSessionStatus;
  /** ISO 8601 */
  pausedAt: string;
  /** 일시정지 시점까지 누적 녹음 시간(ms) */
  elapsedMillis: number;
}

export interface RecordingResumeResponse {
  recordingId: number;
  status: RecordingSessionStatus;
  /** ISO 8601 */
  resumedAt: string;
  /** 재개 직전까지 누적 녹음 시간(ms) */
  elapsedMillis: number;
}

/** PCM 조각 녹음의 종료 봉인 요약 */
export interface RecordingEndAudioSave {
  audioSaveStatus: RecordingAudioSaveStatus;
  expectedLastSequence: number;
  totalFrameCount: number;
  tailUnknown: boolean;
  /** ISO 8601 */
  acceptUntil: string;
}

export interface RecordingEndResponse {
  recordingId: number;
  status: RecordingSessionStatus;
  /** ISO 8601 */
  endedAt: string;
  /** WebM 녹음이면 키가 없음 */
  audioSave?: RecordingEndAudioSave;
}

export interface RecordingHeartbeatResponse {
  recordingId: number;
  /** `DISCARDED`는 서버가 버린 녹음 */
  status: RecordingSessionStatus | "DISCARDED";
  /** 일시정지를 뺀 서버 기준 누적 녹음 시간(ms) */
  elapsedMillis: number;
  /** ISO 8601 */
  lastSeenAt: string;
  /** ISO 8601. 끝난 녹음이면 null */
  expiresAt: string | null;
  /** ISO 8601. 진행 중이면 null */
  endedAt: string | null;
  /** 진행 중이면 null */
  endReason: "USER_ENDED" | "CONNECTION_EXPIRED" | "MAX_DURATION" | null;
  /** ISO 8601 */
  serverNow: string;
}

export interface RecordingAudioUploadUrlResponse {
  uploadId: number;
  /** 객체 저장소에 직접 PUT할 절대 URL */
  uploadUrl: string;
  /** ISO 8601 */
  expiresAt: string;
}

/** 최종 오디오 업로드 처리 상태 */
export type RecordingAudioUploadStatus = "RESERVED" | "COMPLETED";

export interface RecordingAudioUploadCompleteResponse {
  recordingId: number;
  uploadId: number;
  uploadStatus: RecordingAudioUploadStatus;
  /** ISO 8601 */
  completedAt: string;
}

/** PCM 조각 상태 */
export type RecordingAudioChunkStatus = "RESERVED" | "VERIFIED";

/** 조각 예약 요청에서 mock이 읽는 필드. 요청한 번호를 응답에 되돌려 줘요 */
export interface RecordingAudioChunkReserveRequest {
  sequence: number;
}

export interface RecordingAudioChunkReserveResponse {
  sequence: number;
  status: RecordingAudioChunkStatus;
  /** 객체 저장소에 직접 PUT할 절대 URL. VERIFIED면 null */
  uploadUrl: string | null;
  /** ISO 8601. VERIFIED면 null */
  expiresAt: string | null;
  /** PUT에 그대로 실을 헤더. VERIFIED면 빈 객체 */
  requiredHeaders: Record<string, string>;
}

export interface RecordingAudioChunkCompleteResponse {
  sequence: number;
  status: RecordingAudioChunkStatus;
  /** 첫 조각부터 빈틈없이 확인된 마지막 조각 번호 */
  verifiedThroughSequence: number;
  /** 첫 조각부터 빈틈없이 확인된 끝 프레임(제외) */
  verifiedThroughFrame: number;
  /** verifiedThroughFrame을 ms로 바꾼 값 */
  verifiedAudioMillis: number;
  /** ISO 8601 */
  confirmedAt: string;
}

/** PCM 조각 녹음의 저장 상태 */
export type RecordingAudioSaveStatus =
  "CAPTURING" | "DRAINING" | "FINALIZING" | "READY" | "FAILED" | "DISCARDED";

/** 프레임 구간. end는 포함하지 않음 */
export interface RecordingAudioFrameRange {
  start: number;
  endExclusive: number;
}

/** 최종 WAV의 품질. 누락 구간이 있으면 PARTIAL */
export type RecordingAudioQuality = "COMPLETE" | "PARTIAL";

export interface RecordingAudioSaveResponse {
  audioSaveStatus: RecordingAudioSaveStatus;
  verifiedThroughSequence: number;
  verifiedThroughFrame: number;
  verifiedAudioMillis: number;
  verifiedRanges: RecordingAudioFrameRange[];
  missingRanges: RecordingAudioFrameRange[];
  tailUnknown: boolean;
  /** 종료 봉인 전이면 null */
  expectedLastSequence: number | null;
  /** ISO 8601. 종료 전이면 null */
  acceptUntil: string | null;
  /** FAILED가 아니면 null */
  failureCode: string | null;
  canRetry: boolean;
  /** 최종화 작업이 없으면 null */
  jobId: number | null;
  /** 최종화 전이면 null */
  quality: RecordingAudioQuality | null;
}

/** 녹음 상세·문서 상세의 원본 오디오 품질. PCM 조각 녹음에만 있어요 */
export interface RecordingSourceAudio {
  quality: RecordingAudioQuality;
  missingRanges: RecordingAudioFrameRange[];
  tailUnknown: boolean;
}
