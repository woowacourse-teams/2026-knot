/**
 * 녹음 DTO
 *
 * - GET  /api/v1/workspaces/{workspaceId}/recordings/current
 * - POST /api/v1/workspaces/{workspaceId}/recordings
 * - GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/pause
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/resume
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/heartbeat
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-complete
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks/{sequence}/complete
 * - GET  /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save/retry
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcription/retry
 */

/** 녹음 세션 상태. 시작·일시정지·재개·종료 응답과 녹음 상세 조회가 공유하는 서버 값. `DISCARDED`는 서버가 버린 녹음이에요 */
export type RecordingSessionStatus =
  "RECORDING" | "PAUSED" | "ENDED" | "DISCARDED";

/**
 * 오디오 저장 방식. `FINAL_WEBM_V1`은 종료 뒤 최종 WebM 파일을 한 번에 올리는 기존 방식,
 * `PCM16_CHUNKS_V1`은 녹음 중 PCM 조각을 바로 저장하는 방식이에요. 시작한 뒤에는 바꿀 수 없어요
 */
export type RecordingAudioMode = "FINAL_WEBM_V1" | "PCM16_CHUNKS_V1";

/**
 * PCM 조각 녹음의 저장 상태. 녹음 중 `CAPTURING`, 종료 뒤 조각 확인 대기 `DRAINING`, 조립 중 `FINALIZING`,
 * 최종 WAV 채택 `READY`, 재시도 소진 `FAILED`, 폐기 `DISCARDED`예요
 */
export type RecordingAudioSaveStatus =
  "CAPTURING" | "DRAINING" | "FINALIZING" | "READY" | "FAILED" | "DISCARDED";

/** PCM 조각 하나의 위치와 내용 증명. 앱이 조각을 만들 때 정한 값 */
export interface RecordingAudioChunkDescriptorInput {
  sequence: number;
  sampleOffset: number;
  frameCount: number;
  byteLength: number;
  checksumSha256: string;
}

/** PCM 조각 descriptor. 조각 예약 요청과 종료 요청의 `audioSeal.pendingDescriptors`가 공유해요. 예약한 뒤에는 같은 조각의 값을 바꾸지 않아요 */
export class RecordingAudioChunkDescriptorDto {
  /** 조각 번호. 1부터 연속 증가하고 순서대로 예약해요 */
  sequence: number;
  /** 앞선 조각들의 `frameCount` 합. 첫 조각은 0 */
  sampleOffset: number;
  /** 이 조각의 프레임 수(1~80,000). 16kHz라 최대 5초예요 */
  frameCount: number;
  /** PCM 바이트 수. `frameCount × 2` */
  byteLength: number;
  /** 보낼 PCM 전체의 SHA-256을 패딩 있는 표준 Base64로 쓴 44자. URL-safe Base64나 hex가 아니에요 */
  checksumSha256: string;

  constructor({
    sequence,
    sampleOffset,
    frameCount,
    byteLength,
    checksumSha256,
  }: RecordingAudioChunkDescriptorInput) {
    this.sequence = sequence;
    this.sampleOffset = sampleOffset;
    this.frameCount = frameCount;
    this.byteLength = byteLength;
    this.checksumSha256 = checksumSha256;
  }
}

/** 최종 WAV의 품질. 모든 조각이 저장됐으면 `COMPLETE`, 저장되지 않은 구간을 무음으로 채웠으면 `PARTIAL`이에요 */
export type RecordingAudioQuality = "COMPLETE" | "PARTIAL";

/** 프레임 구간의 서버 응답 모양 */
export interface RecordingAudioFrameRangeRaw {
  start: number;
  endExclusive: number;
}

/** 녹음 안의 프레임 구간. 저장 상태의 확인·누락 구간과 원본 오디오 품질의 누락 구간이 공유해요 */
export class RecordingAudioFrameRangeDto {
  /** 구간 시작 프레임(포함). 16kHz라 16,000프레임이 1초예요 */
  start: number;
  /** 구간 끝 프레임(제외) */
  endExclusive: number;

  constructor(raw: RecordingAudioFrameRangeRaw) {
    this.start = raw.start;
    this.endExclusive = raw.endExclusive;
  }
}

/** 원본 오디오 품질의 서버 응답 모양 */
export interface RecordingSourceAudioRaw {
  quality: RecordingAudioQuality;
  missingRanges: RecordingAudioFrameRangeRaw[];
  tailUnknown: boolean;
}

/** PCM 조각 녹음으로 만든 최종 WAV의 품질. 녹음 상세 조회와 문서 상세 조회가 공유해요 */
export class RecordingSourceAudioDto {
  /** 최종 WAV의 품질 */
  quality: RecordingAudioQuality;
  /** 저장되지 않아 무음으로 채운 구간. 없으면 빈 배열이에요. 무음은 복구된 원본이 아니에요 */
  missingRanges: RecordingAudioFrameRangeDto[];
  /** 다른 탭 종료나 연결 만료로 종료 봉인 없이 끝나 마지막 길이를 알 수 없으면 true. 끝부분이 빠졌을 수 있어요 */
  tailUnknown: boolean;

  constructor(raw: RecordingSourceAudioRaw) {
    this.quality = raw.quality;
    this.missingRanges = raw.missingRanges.map(
      (range) => new RecordingAudioFrameRangeDto(range),
    );
    this.tailUnknown = raw.tailUnknown;
  }
}

/** PCM 조각 녹음 저장 상태의 서버 응답 모양 */
export interface RecordingAudioSaveRaw {
  audioSaveStatus: RecordingAudioSaveStatus;
  verifiedThroughSequence: number;
  verifiedThroughFrame: number;
  verifiedAudioMillis: number;
  verifiedRanges: RecordingAudioFrameRangeRaw[];
  missingRanges: RecordingAudioFrameRangeRaw[];
  tailUnknown: boolean;
  expectedLastSequence: number | null;
  acceptUntil: string | null;
  failureCode: string | null;
  canRetry: boolean;
  jobId: number | null;
  quality: RecordingAudioQuality | null;
}

/** PCM 조각 녹음의 저장 상태. 저장 상태 조회와 저장 재시도 응답이 공유해요 */
export class RecordingAudioSaveDto {
  /** 저장 상태 */
  audioSaveStatus: RecordingAudioSaveStatus;
  /** 첫 조각부터 빈틈없이 저장이 확인된 마지막 조각 번호. 확인된 조각이 없으면 0 */
  verifiedThroughSequence: number;
  /** 첫 조각부터 빈틈없이 확인된 구간의 끝 프레임(그 프레임은 제외). 확인된 조각이 없으면 0 */
  verifiedThroughFrame: number;
  /** `verifiedThroughFrame`을 ms로 바꾼 길이 */
  verifiedAudioMillis: number;
  /** 저장이 확인된 구간. 앞 조각이 빠져도 뒤쪽에서 확인된 구간은 따로 담겨요 */
  verifiedRanges: RecordingAudioFrameRangeDto[];
  /** 최종 명세를 고정하기 전에는 예약했지만 아직 확인되지 않은 구간, 고정한 뒤에는 무음으로 채운 구간이에요. 없으면 빈 배열 */
  missingRanges: RecordingAudioFrameRangeDto[];
  /** 종료 봉인 없이 끝나 마지막 길이를 알 수 없으면 true */
  tailUnknown: boolean;
  /** 종료 봉인의 마지막 조각 번호. 봉인 전이면 null */
  expectedLastSequence: number | null;
  /** 종료 뒤 남은 조각을 받아 주는 시한(ISO 8601). 종료 전이면 null */
  acceptUntil: string | null;
  /** `FAILED`일 때의 실패 이유 코드(예: `AUDIO_NO_CONTENT`). 최종화 작업이 없거나 실패가 아니면 null */
  failureCode: string | null;
  /** 저장 재시도 API로 다시 시도할 수 있으면 true. 최종화 작업이 없거나, 폐기됐거나, 종료 30일이 지났으면 false */
  canRetry: boolean;
  /** 최종화 작업 ID. 작업이 만들어지기 전이면 null. 재시도해도 같은 ID예요 */
  jobId: number | null;
  /** 최종 WAV의 품질. 최종화 작업이 품질을 정하기 전이면 null */
  quality: RecordingAudioQuality | null;

  constructor(raw: RecordingAudioSaveRaw) {
    this.audioSaveStatus = raw.audioSaveStatus;
    this.verifiedThroughSequence = raw.verifiedThroughSequence;
    this.verifiedThroughFrame = raw.verifiedThroughFrame;
    this.verifiedAudioMillis = raw.verifiedAudioMillis;
    this.verifiedRanges = raw.verifiedRanges.map(
      (range) => new RecordingAudioFrameRangeDto(range),
    );
    this.missingRanges = raw.missingRanges.map(
      (range) => new RecordingAudioFrameRangeDto(range),
    );
    this.tailUnknown = raw.tailUnknown;
    this.expectedLastSequence = raw.expectedLastSequence;
    this.acceptUntil = raw.acceptUntil;
    this.failureCode = raw.failureCode;
    this.canRetry = raw.canRetry;
    this.jobId = raw.jobId;
    this.quality = raw.quality;
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/current

/**
 * 현재 녹음의 화면용 진행 상태. `PROCESSING`은 녹음을 끝낸 뒤 오디오 준비 · 전사 · 문서 생성을 기다리거나 하는 중이에요.
 * `TRANSCRIPTION_FAILED`는 전사가, `DOCUMENT_FAILED`는 문서 생성이 끝내 실패한 녹음이에요.
 * 두 실패 값은 백엔드 제안을 앞서 반영한 값이에요(#926). 지금 서버는 ADR 867대로 두 실패도 204로 줘요.
 * 정리가 끝났거나(완료 · 내용 없음) 폐기된 녹음은 상태 대신 204로 와요(ADR 867)
 */
export type CurrentRecordingStatus =
  | "RECORDING"
  | "PAUSED"
  | "PROCESSING"
  | "TRANSCRIPTION_FAILED"
  | "DOCUMENT_FAILED";

/**
 * 내 현재 녹음 조회의 서버 응답 모양. 워크스페이스에서 내가 가장 최근에 시작한 녹음 한 건이에요.
 * `retryable`은 실패 상태와 함께 더해진 값이라, 그 전의 서버는 필드 자체를 주지 않아요
 */
export interface GetCurrentRecordingResponseRaw {
  recordingId: number;
  status: CurrentRecordingStatus;
  startedAt: string;
  elapsedMillis: number;
  retryable?: boolean;
}

/** 내 현재 녹음 조회 응답. 표시할 녹음이 없으면 서버가 본문 없이 204로 답하고 요청 함수가 `null`을 돌려줘요 */
export class GetCurrentRecordingResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 화면용 진행 상태. 가장 최근 녹음이 녹음 중 · 일시정지 · 문서 정리 중 · 전사 실패 · 문서 생성 실패일 때만 와요 */
  status: CurrentRecordingStatus;
  /** 녹음 시작 시각(ISO 8601) */
  startedAt: string;
  /** 조회 시점까지 누적 녹음 시간(ms). 일시정지 구간은 빠져요 */
  elapsedMillis: number;
  /**
   * 실패한 녹음을 다시 시도할 수 있으면 true. 실패 상태(`TRANSCRIPTION_FAILED` · `DOCUMENT_FAILED`)에서만 뜻이 있어요.
   * 서버 필드 `retryable`을 이름만 바꿨고, 서버가 주지 않으면 false예요
   */
  isRetryable: boolean;

  constructor(raw: GetCurrentRecordingResponseRaw) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.startedAt = raw.startedAt;
    this.elapsedMillis = raw.elapsedMillis;
    this.isRetryable = raw.retryable ?? false;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings

/** 녹음 시작 시 앱이 넘기는 값. 모두 이 탭이 만들어 sessionStorage에 보관한 값 */
export interface PostRecordingRequestInput {
  requestId: string;
  tabId: string;
  controlToken: string;
  audioMode?: RecordingAudioMode;
}

/** 녹음 시작 요청 본문 */
export class PostRecordingRequestDto {
  /** 같은 시작 요청을 다시 보낼 때 유지하는 UUID. 같은 값이면 서버가 새 세션을 만들지 않고 기존 세션을 돌려줘요 */
  requestId: string;
  /** 녹음을 시작한 최초 탭의 UUID. 새로고침해도 유지해요 */
  tabId: string;
  /** 32바이트 난수를 패딩 없는 Base64URL로 쓴 43자 제어 증명. 일시정지·재개에 그대로 다시 보내요 */
  controlToken: string;
  /** 오디오 저장 방식. 생략하면 본문에 실리지 않아 서버가 `FINAL_WEBM_V1`로 봐요. 같은 시작 요청의 재시도에도 같은 값을 보내요 */
  audioMode?: RecordingAudioMode;

  constructor({
    requestId,
    tabId,
    controlToken,
    audioMode,
  }: PostRecordingRequestInput) {
    this.requestId = requestId;
    this.tabId = tabId;
    this.controlToken = controlToken;
    this.audioMode = audioMode;
  }
}

/** 녹음 시작의 서버 응답 모양 */
export interface PostRecordingResponseRaw {
  recordingId: number;
  status: RecordingSessionStatus;
  startedAt: string;
}

/** 녹음 시작 응답. 새 세션은 201, 같은 requestId 재시도는 기존 세션을 200으로 같은 모양에 담아요 */
export class PostRecordingResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 새 요청은 `RECORDING`, 재시도는 기존 세션의 현재 상태 */
  status: RecordingSessionStatus;
  /** 서버가 확정한 녹음 시작 시각(ISO 8601) */
  startedAt: string;
  /** 이 요청으로 새로 만든 녹음이면 `true`. 본문에는 없어 HTTP 상태(201)에서 읽어요 */
  isCreated: boolean;

  constructor(raw: PostRecordingResponseRaw, httpStatus: number) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.startedAt = raw.startedAt;
    this.isCreated = httpStatus === 201;
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}

/** 전사 상태. 전사 시도가 접수된 뒤의 값이에요 */
export type RecordingTranscriptionStatus =
  "PROCESSING" | "SUCCEEDED" | "FAILED";

/**
 * 주제 분류와 주제별 문서 생성을 합친 상태.
 *
 * - `QUEUED` · `RUNNING`: 대기하거나 진행 중인 작업이 있음. 문서가 일부 만들어졌어도 남은 작업이 있으면 이 값이에요
 * - `SUCCEEDED`: 모든 주제의 문서가 만들어짐
 * - `NO_CONTENT`: 전사 결과에 문서로 정리할 내용이 없음
 * - `FAILED`: 진행 중인 작업 없이 실패가 남음
 */
export type RecordingDocumentGenerationStatus =
  "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "NO_CONTENT";

/** 녹음 상세 조회가 주는 문서 생성 결과의 서버 응답 모양 */
export interface RecordingDocumentGenerationRaw {
  status: RecordingDocumentGenerationStatus;
  succeededCount: number;
  failedJobIds: number[];
}

/** 녹음 하나의 문서 생성 결과. 녹음 상세 조회 응답 안에 들어 있어요 */
export class RecordingDocumentGenerationDto {
  /** 주제 분류와 주제별 문서 생성을 합친 상태 */
  status: RecordingDocumentGenerationStatus;
  /** 생성에 성공한 문서 수. `FAILED`일 때 0이면 전체 실패, 1 이상이면 일부만 만들어진 실패예요 */
  succeededCount: number;
  /**
   * 보존 기한 안에 남아 있는 실패 작업의 ID. 문서 만들기 재시도에 넘겨요.
   * 주제 분류 단계에서 실패한 작업과 다시 시도 횟수를 다 쓴 작업도 들어 있어요.
   * 다시 시도 횟수를 다 쓴 작업은 재시도를 보내면 서버가 409로 거절해요.
   * 실패 작업이 없거나 보존 기한이 지나 지워졌으면 빈 배열이에요
   */
  failedJobIds: number[];

  constructor(raw: RecordingDocumentGenerationRaw) {
    this.status = raw.status;
    this.succeededCount = raw.succeededCount;
    this.failedJobIds = raw.failedJobIds;
  }
}

/**
 * 녹음 상세 조회의 서버 응답 모양.
 *
 * 전사와 문서 생성 필드는 서버에 나중에 더해진 값이라, 그 전의 서버는 필드 자체를 주지 않아요.
 */
export interface GetRecordingResponseRaw {
  /** 녹음 세션 상태. 전사·문서 생성 상태가 아니에요 */
  status: RecordingSessionStatus;
  elapsedMillis: number;
  endedAt: string | null;
  endReason: RecordingEndReason | null;
  audioUploadStatus: RecordingAudioUploadStatus | null;
  transcriptionAttemptId?: string | null;
  transcriptionStatus?: RecordingTranscriptionStatus | null;
  transcriptionFailureCode?: string | null;
  documentGeneration?: RecordingDocumentGenerationRaw | null;
  sourceAudio?: RecordingSourceAudioRaw | null;
}

/** 녹음 상세 조회 응답. 녹음을 끝낸 뒤 이 응답을 다시 조회해 문서 정리가 어디까지 됐는지 확인해요 */
export class GetRecordingResponseDto {
  /** 녹음 세션 상태. 서버 필드 `status`를 전사·문서 생성 상태와 구별되게 이름을 바꿨어요. 전사·문서 생성이 실패해도 `ENDED`로 남아요 */
  sessionStatus: RecordingSessionStatus;
  /** 일시정지를 뺀 서버 기준 녹음 시간(ms). 진행 중이면 조회 시점까지 센 값이에요 */
  elapsedMillis: number;
  /** 녹음 종료 시각(ISO 8601). 끝나기 전이면 null */
  endedAt: string | null;
  /** 녹음이 끝난 사유. 끝나기 전이면 null */
  endReason: RecordingEndReason | null;
  /** 최종 오디오 업로드 상태. 업로드를 예약하기 전이면 null, 예약하면 `RESERVED`, 서버가 저장을 확인하면 `COMPLETED` */
  audioUploadStatus: RecordingAudioUploadStatus | null;
  /** 지금 전사 시도의 UUID. 전사를 시도하기 전이면 null. 서버가 주지 않으면 null */
  transcriptionAttemptId: string | null;
  /** 전사 상태. 전사를 시도하기 전이면 null. 서버가 주지 않으면 null */
  transcriptionStatus: RecordingTranscriptionStatus | null;
  /** 전사 실패 이유 코드(예: `AI_TIMEOUT`). 실패가 아니면 null. 서버가 주지 않으면 null */
  transcriptionFailureCode: string | null;
  /** 문서 생성 결과. 문서 생성이 접수되기 전이면 null. 서버가 주지 않으면 null */
  documentGeneration: RecordingDocumentGenerationDto | null;
  /** PCM 조각 녹음으로 만든 최종 WAV의 품질. WebM 녹음이거나 최종 WAV가 준비되기 전이면 null. 서버가 주지 않으면 null */
  sourceAudio: RecordingSourceAudioDto | null;

  constructor(raw: GetRecordingResponseRaw) {
    this.sessionStatus = raw.status;
    this.elapsedMillis = raw.elapsedMillis;
    this.endedAt = raw.endedAt;
    this.endReason = raw.endReason;
    this.audioUploadStatus = raw.audioUploadStatus;
    this.transcriptionAttemptId = raw.transcriptionAttemptId ?? null;
    this.transcriptionStatus = raw.transcriptionStatus ?? null;
    this.transcriptionFailureCode = raw.transcriptionFailureCode ?? null;
    // 전사·문서 생성 필드를 주지 않는 서버의 응답(undefined)도 값이 없는 것(null)으로 맞춰요
    const documentGeneration = raw.documentGeneration ?? null;

    this.documentGeneration =
      documentGeneration === null
        ? null
        : new RecordingDocumentGenerationDto(documentGeneration);
    this.sourceAudio = raw.sourceAudio
      ? new RecordingSourceAudioDto(raw.sourceAudio)
      : null;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/pause

/** 녹음 일시정지 시 앱이 넘기는 값. 시작 때 보낸 증명 그대로 */
export interface PostRecordingPauseRequestInput {
  tabId: string;
  controlToken: string;
}

/** 녹음 일시정지 요청 본문. 최초 녹음 탭임을 증명해요 */
export class PostRecordingPauseRequestDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;

  constructor({ tabId, controlToken }: PostRecordingPauseRequestInput) {
    this.tabId = tabId;
    this.controlToken = controlToken;
  }
}

/** 녹음 일시정지의 서버 응답 모양 */
export interface PostRecordingPauseResponseRaw {
  recordingId: number;
  status: RecordingSessionStatus;
  pausedAt: string;
  elapsedMillis: number;
}

/** 녹음 일시정지 응답. 이미 일시정지였어도 같은 모양으로 현재 상태를 돌려줘요 */
export class PostRecordingPauseResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 항상 `PAUSED` */
  status: RecordingSessionStatus;
  /** 일시정지 시각(ISO 8601) */
  pausedAt: string;
  /** 일시정지 시점까지 누적 녹음 시간(ms). 일시정지 구간은 빠져요 */
  elapsedMillis: number;

  constructor(raw: PostRecordingPauseResponseRaw) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.pausedAt = raw.pausedAt;
    this.elapsedMillis = raw.elapsedMillis;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/resume

/** 녹음 재개 시 앱이 넘기는 값. 시작 때 보낸 증명 그대로 */
export interface PostRecordingResumeRequestInput {
  tabId: string;
  controlToken: string;
}

/** 녹음 재개 요청 본문. 최초 녹음 탭임을 증명해요 */
export class PostRecordingResumeRequestDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;

  constructor({ tabId, controlToken }: PostRecordingResumeRequestInput) {
    this.tabId = tabId;
    this.controlToken = controlToken;
  }
}

/** 녹음 재개의 서버 응답 모양 */
export interface PostRecordingResumeResponseRaw {
  recordingId: number;
  status: RecordingSessionStatus;
  resumedAt: string;
  elapsedMillis: number;
}

/** 녹음 재개 응답. 이미 녹음 중이었어도 같은 모양으로 현재 상태를 돌려줘요 */
export class PostRecordingResumeResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 항상 `RECORDING` */
  status: RecordingSessionStatus;
  /** 재개 시각(ISO 8601) */
  resumedAt: string;
  /** 재개 직전까지 누적 녹음 시간(ms) */
  elapsedMillis: number;

  constructor(raw: PostRecordingResumeResponseRaw) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.resumedAt = raw.resumedAt;
    this.elapsedMillis = raw.elapsedMillis;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end

/** PCM 조각 녹음을 정상 종료할 때 앱이 넘기는 마지막 봉인 값 */
export interface RecordingAudioSealInput {
  lastSequence: number;
  totalFrameCount: number;
  pendingDescriptors: RecordingAudioChunkDescriptorInput[];
}

/** PCM 조각 녹음의 종료 봉인. 종료 요청 본문의 `audioSeal`로 나가요 */
export class RecordingAudioSealDto {
  /** 마지막 조각 번호. 음성이 없으면 0 */
  lastSequence: number;
  /** 전체 프레임 수. 일시정지 시간은 더하지 않고, 음성이 없으면 0 */
  totalFrameCount: number;
  /** 아직 예약하지 않은 마지막 조각들. 번호와 offset이 이미 예약한 조각에 이어져야 하고, 없으면 빈 배열 */
  pendingDescriptors: RecordingAudioChunkDescriptorDto[];

  constructor({
    lastSequence,
    totalFrameCount,
    pendingDescriptors,
  }: RecordingAudioSealInput) {
    this.lastSequence = lastSequence;
    this.totalFrameCount = totalFrameCount;
    this.pendingDescriptors = pendingDescriptors.map(
      (descriptor) => new RecordingAudioChunkDescriptorDto(descriptor),
    );
  }
}

/** 녹음 종료 시 앱이 넘기는 값. 시작 때 보낸 증명 그대로. PCM 조각 녹음은 `audioSeal`도 넘겨요 */
export interface PostRecordingEndRequestInput {
  tabId: string;
  controlToken: string;
  audioSeal?: RecordingAudioSealInput;
}

/** 녹음 종료 요청 본문. 최초 녹음 탭임을 증명해요 */
export class PostRecordingEndRequestDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;
  /** PCM 조각 녹음의 종료 봉인. WebM 녹음은 생략하고, 생략하면 본문에 키가 실리지 않아 지금과 같은 요청이 나가요 */
  audioSeal?: RecordingAudioSealDto;

  constructor({
    tabId,
    controlToken,
    audioSeal,
  }: PostRecordingEndRequestInput) {
    this.tabId = tabId;
    this.controlToken = controlToken;
    this.audioSeal = audioSeal && new RecordingAudioSealDto(audioSeal);
  }
}

/** 종료 응답에 담긴 PCM 종료 봉인 요약의 서버 응답 모양 */
export interface RecordingEndAudioSaveRaw {
  audioSaveStatus: RecordingAudioSaveStatus;
  expectedLastSequence: number;
  totalFrameCount: number;
  tailUnknown: boolean;
  acceptUntil: string;
}

/** 종료 직후의 PCM 종료 봉인 요약. 종료 응답 안에 들어 있어요 */
export class RecordingEndAudioSaveDto {
  /** 저장 상태. 정상 종료 직후는 보통 `DRAINING`이고, 조각이 모두 확인됐으면 바로 `FINALIZING` 이후예요 */
  audioSaveStatus: RecordingAudioSaveStatus;
  /** 서버가 확정한 마지막 조각 번호. 음성이 없으면 0 */
  expectedLastSequence: number;
  /** 서버가 확정한 전체 프레임 수. 일시정지 시간은 더하지 않아요 */
  totalFrameCount: number;
  /** 봉인 없이 끝나 마지막 길이를 알 수 없으면 true. 최초 탭이 `audioSeal`을 보내 끝냈으면 false */
  tailUnknown: boolean;
  /** 남은 조각의 PUT과 저장 확인을 받아 주는 시한(ISO 8601, 종료 시각 + 120초). 반복 종료에도 바뀌지 않아요 */
  acceptUntil: string;

  constructor(raw: RecordingEndAudioSaveRaw) {
    this.audioSaveStatus = raw.audioSaveStatus;
    this.expectedLastSequence = raw.expectedLastSequence;
    this.totalFrameCount = raw.totalFrameCount;
    this.tailUnknown = raw.tailUnknown;
    this.acceptUntil = raw.acceptUntil;
  }
}

/** 녹음 종료의 서버 응답 모양. `audioSave`는 PCM 조각 녹음에만 있고 그 전의 서버는 필드 자체를 주지 않아요 */
export interface PostRecordingEndResponseRaw {
  recordingId: number;
  status: RecordingSessionStatus;
  endedAt: string;
  audioSave?: RecordingEndAudioSaveRaw | null;
}

/** 녹음 종료 응답. 이미 종료된 녹음이면 처음 확정한 결과를 그대로 돌려줘요 */
export class PostRecordingEndResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 항상 `ENDED`. 업로드·전사 처리 상태와는 별개예요 */
  status: RecordingSessionStatus;
  /** 서버가 확정한 종료 시각(ISO 8601) */
  endedAt: string;
  /** PCM 조각 녹음의 저장 상태. WebM 녹음이거나 서버가 주지 않으면 null */
  audioSave: RecordingEndAudioSaveDto | null;

  constructor(raw: PostRecordingEndResponseRaw) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.endedAt = raw.endedAt;
    this.audioSave = raw.audioSave
      ? new RecordingEndAudioSaveDto(raw.audioSave)
      : null;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/heartbeat

/**
 * 녹음이 끝난 사유. `USER_ENDED`는 사용자가 직접 끝냄, `CONNECTION_EXPIRED`는 생존 신호가 30초 동안 없어 서버가 끝냄,
 * `MAX_DURATION`은 누적 녹음 시간이 2시간에 닿아 서버가 끝냄
 */
export type RecordingEndReason =
  "USER_ENDED" | "CONNECTION_EXPIRED" | "MAX_DURATION";

/** 녹음 생존 신호를 보낼 때 앱이 넘기는 값. 시작 때 보낸 증명 그대로 */
export interface PostRecordingHeartbeatRequestInput {
  tabId: string;
  controlToken: string;
}

/** 녹음 생존 신호 요청 본문. 최초 녹음 탭임을 증명해요 */
export class PostRecordingHeartbeatRequestDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;

  constructor({ tabId, controlToken }: PostRecordingHeartbeatRequestInput) {
    this.tabId = tabId;
    this.controlToken = controlToken;
  }
}

/** 녹음 생존 신호의 서버 응답 모양 */
export interface PostRecordingHeartbeatResponseRaw {
  recordingId: number;
  status: RecordingSessionStatus;
  elapsedMillis: number;
  lastSeenAt: string;
  expiresAt: string | null;
  endedAt: string | null;
  endReason: RecordingEndReason | null;
  serverNow: string;
}

/** 녹음 생존 신호 응답. 이미 끝난 녹음에 보내도 오류가 아니라 200으로 끝난 상태를 그대로 돌려줘요 */
export class PostRecordingHeartbeatResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 지금의 녹음 세션 상태. `ENDED`나 `DISCARDED`면 서버가 이미 끝냈거나 버린 녹음이에요 */
  status: RecordingSessionStatus;
  /** 서버 기준 누적 녹음 시간(ms). 일시정지 구간은 빠지고 최대 2시간(7200000)이에요 */
  elapsedMillis: number;
  /** 서버가 받은 마지막 유효 신호 시각(ISO 8601). 끝난 녹음에 보낸 신호로는 바뀌지 않아요 */
  lastSeenAt: string;
  /** 이 시각(ISO 8601)까지 다음 신호가 없으면 서버가 녹음을 끝내요. 끝난 녹음이면 null */
  expiresAt: string | null;
  /** 녹음 종료 시각(ISO 8601). 진행 중이면 null */
  endedAt: string | null;
  /** 종료 사유. 진행 중이거나 이 값을 기록하기 전에 끝난 녹음이면 null */
  endReason: RecordingEndReason | null;
  /** 응답을 만든 서버 시각(ISO 8601) */
  serverNow: string;

  constructor(raw: PostRecordingHeartbeatResponseRaw) {
    this.recordingId = raw.recordingId;
    this.status = raw.status;
    this.elapsedMillis = raw.elapsedMillis;
    this.lastSeenAt = raw.lastSeenAt;
    this.expiresAt = raw.expiresAt;
    this.endedAt = raw.endedAt;
    this.endReason = raw.endReason;
    this.serverNow = raw.serverNow;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-url

/** 최종 오디오 업로드 URL을 받을 때 앱이 넘기는 값 */
export interface PostRecordingAudioUploadUrlRequestInput {
  contentType: string;
  contentLength: number;
}

/** 최종 오디오 업로드 URL 발급 요청 본문 */
export class PostRecordingAudioUploadUrlRequestDto {
  /** 최종 오디오 파일의 Content-Type. 예: `audio/webm`. PUT 요청에도 같은 값을 보내야 해요 */
  contentType: string;
  /** 최종 오디오 파일 크기(byte, 1 이상). PUT 요청의 Content-Length와 같아야 해요 */
  contentLength: number;

  constructor({
    contentType,
    contentLength,
  }: PostRecordingAudioUploadUrlRequestInput) {
    this.contentType = contentType;
    this.contentLength = contentLength;
  }
}

/** 최종 오디오 업로드 URL 발급의 서버 응답 모양 */
export interface PostRecordingAudioUploadUrlResponseRaw {
  uploadId: number;
  uploadUrl: string;
  expiresAt: string;
}

/** 최종 오디오 업로드 URL 발급 응답. 새 예약은 201, 아직 쓰지 않은 같은 예약의 재발급은 200으로 같은 모양 */
export class PostRecordingAudioUploadUrlResponseDto {
  /** 업로드 예약 ID. 업로드 완료 확인 API에 넘겨요 */
  uploadId: number;
  /** 객체 저장소에 직접 PUT할 Presigned URL(절대 URL) */
  uploadUrl: string;
  /** URL 만료 시각(ISO 8601). 지나면 같은 요청으로 다시 받아요 */
  expiresAt: string;

  constructor(raw: PostRecordingAudioUploadUrlResponseRaw) {
    this.uploadId = raw.uploadId;
    this.uploadUrl = raw.uploadUrl;
    this.expiresAt = raw.expiresAt;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-upload-complete

/** 최종 오디오 업로드 처리 상태. 완료 확인 응답은 항상 `COMPLETED`예요 */
export type RecordingAudioUploadStatus = "RESERVED" | "COMPLETED";

/** 최종 오디오 업로드 완료 확인 시 앱이 넘기는 값 */
export interface PostRecordingAudioUploadCompleteRequestInput {
  uploadId: number;
}

/** 최종 오디오 업로드 완료 확인 요청 본문 */
export class PostRecordingAudioUploadCompleteRequestDto {
  /** 업로드 URL 발급 응답의 업로드 예약 ID */
  uploadId: number;

  constructor({ uploadId }: PostRecordingAudioUploadCompleteRequestInput) {
    this.uploadId = uploadId;
  }
}

/** 최종 오디오 업로드 완료 확인의 서버 응답 모양 */
export interface PostRecordingAudioUploadCompleteResponseRaw {
  recordingId: number;
  uploadId: number;
  uploadStatus: RecordingAudioUploadStatus;
  completedAt: string;
}

/** 최종 오디오 업로드 완료 확인 응답. 같은 uploadId로 다시 불러도 처음 확정한 결과를 그대로 돌려줘요 */
export class PostRecordingAudioUploadCompleteResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 업로드 예약 ID */
  uploadId: number;
  /** 항상 `COMPLETED`. 전사 처리 상태와는 별개예요 */
  uploadStatus: RecordingAudioUploadStatus;
  /** 서버가 업로드 완료를 확정한 시각(ISO 8601). 다시 불러도 바뀌지 않아요 */
  completedAt: string;

  constructor(raw: PostRecordingAudioUploadCompleteResponseRaw) {
    this.recordingId = raw.recordingId;
    this.uploadId = raw.uploadId;
    this.uploadStatus = raw.uploadStatus;
    this.completedAt = raw.completedAt;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks

/** PCM 조각 상태. `RESERVED`는 PUT과 저장 확인 전, `VERIFIED`는 서버가 저장을 확인한 뒤예요 */
export type RecordingAudioChunkStatus = "RESERVED" | "VERIFIED";

/** 조각 예약 시 앱이 넘기는 값. 최초 탭 증명과 조각 descriptor */
export interface PostRecordingAudioChunkRequestInput extends RecordingAudioChunkDescriptorInput {
  tabId: string;
  controlToken: string;
}

/** 조각 예약 요청 본문. 증명과 descriptor 필드를 한 단계에 평평하게 담아요 */
export class PostRecordingAudioChunkRequestDto extends RecordingAudioChunkDescriptorDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;

  constructor({
    tabId,
    controlToken,
    ...descriptor
  }: PostRecordingAudioChunkRequestInput) {
    super(descriptor);
    this.tabId = tabId;
    this.controlToken = controlToken;
  }
}

/** 조각 예약의 서버 응답 모양 */
export interface PostRecordingAudioChunkResponseRaw {
  sequence: number;
  status: RecordingAudioChunkStatus;
  uploadUrl: string | null;
  expiresAt: string | null;
  requiredHeaders: Record<string, string>;
}

/** 조각 예약 응답. 새 예약은 201, 같은 descriptor의 재요청은 200으로 같은 모양이고 URL을 다시 발급해요 */
export class PostRecordingAudioChunkResponseDto {
  /** 예약한 조각 번호 */
  sequence: number;
  /** `RESERVED`면 `uploadUrl`로 PUT한 뒤 저장 확인을 보내고, `VERIFIED`면 이미 저장돼 할 일이 없어요 */
  status: RecordingAudioChunkStatus;
  /** PCM을 PUT할 서명 URL(절대 URL). `VERIFIED`면 null. 로그에 남기지 않아요 */
  uploadUrl: string | null;
  /** 서명 URL 만료 시각(ISO 8601). 종료 뒤에는 `acceptUntil`을 넘지 않아요. `VERIFIED`면 null */
  expiresAt: string | null;
  /** PUT에 그대로 실을 헤더(`content-type`, `if-none-match`, `x-amz-checksum-sha256`). `VERIFIED`면 빈 객체 */
  requiredHeaders: Record<string, string>;

  constructor(raw: PostRecordingAudioChunkResponseRaw) {
    this.sequence = raw.sequence;
    this.status = raw.status;
    this.uploadUrl = raw.uploadUrl;
    this.expiresAt = raw.expiresAt;
    this.requiredHeaders = raw.requiredHeaders;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-chunks/{sequence}/complete

/** 조각 저장 확인 시 앱이 넘기는 값. 시작 때 보낸 증명 그대로 */
export interface PostRecordingAudioChunkCompleteRequestInput {
  tabId: string;
  controlToken: string;
}

/** 조각 저장 확인 요청 본문. 최초 녹음 탭임을 증명해요 */
export class PostRecordingAudioChunkCompleteRequestDto {
  /** 녹음을 시작한 최초 탭의 UUID */
  tabId: string;
  /** 녹음 시작 때 보낸 제어 증명(43자 Base64URL) */
  controlToken: string;

  constructor({
    tabId,
    controlToken,
  }: PostRecordingAudioChunkCompleteRequestInput) {
    this.tabId = tabId;
    this.controlToken = controlToken;
  }
}

/** 조각 저장 확인의 서버 응답 모양 */
export interface PostRecordingAudioChunkCompleteResponseRaw {
  sequence: number;
  status: RecordingAudioChunkStatus;
  verifiedThroughSequence: number;
  verifiedThroughFrame: number;
  verifiedAudioMillis: number;
  confirmedAt: string;
}

/** 조각 저장 확인 응답. 이미 확인된 조각을 다시 확인해도 200으로 같은 결과를 돌려줘요. 저장소를 확인하지 못하면 500 `AUDIO_STORAGE_UNAVAILABLE`로 실패해요 */
export class PostRecordingAudioChunkCompleteResponseDto {
  /** 확인한 조각 번호 */
  sequence: number;
  /** 항상 `VERIFIED` */
  status: RecordingAudioChunkStatus;
  /** 첫 조각부터 빈틈없이 확인된 마지막 조각 번호. 앞 조각이 아직 확인 전이면 0이에요 */
  verifiedThroughSequence: number;
  /** 첫 조각부터 빈틈없이 확인된 구간의 끝 프레임(그 프레임은 제외). 앞 조각이 아직 확인 전이면 0이고, 재요청 때 늘어날 수 있어요 */
  verifiedThroughFrame: number;
  /** `verifiedThroughFrame`을 ms로 바꾼 길이 */
  verifiedAudioMillis: number;
  /** 서버가 이 조각의 저장을 확인한 시각(ISO 8601). 재요청해도 처음 확인한 시각 그대로예요 */
  confirmedAt: string;

  constructor(raw: PostRecordingAudioChunkCompleteResponseRaw) {
    this.sequence = raw.sequence;
    this.status = raw.status;
    this.verifiedThroughSequence = raw.verifiedThroughSequence;
    this.verifiedThroughFrame = raw.verifiedThroughFrame;
    this.verifiedAudioMillis = raw.verifiedAudioMillis;
    this.confirmedAt = raw.confirmedAt;
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save

/** 저장 상태 조회의 서버 응답 모양 */
export type GetRecordingAudioSaveResponseRaw = RecordingAudioSaveRaw;

/** PCM 조각 녹음의 저장 상태 조회 응답. 조회해도 생존 신호는 갱신되지 않아요 */
export class GetRecordingAudioSaveResponseDto extends RecordingAudioSaveDto {}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save/retry

/** 저장 재시도의 서버 응답 모양 */
export type PostRecordingAudioSaveRetryResponseRaw = RecordingAudioSaveRaw;

/**
 * 저장 재시도 응답. 다시 접수하면 202, 같은 작업이 이미 대기 또는 실행 중이면 200으로 같은 모양이에요.
 * 접수 뒤의 저장 상태라 최종 WAV가 준비됐다는 뜻은 아니에요. 다시 시도할 수 없으면 409 `AUDIO_SAVE_RETRY_NOT_ALLOWED`예요
 */
export class PostRecordingAudioSaveRetryResponseDto extends RecordingAudioSaveDto {}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcription/retry

/** 전사 재시도의 서버 응답 모양 */
export interface PostRecordingTranscriptionRetryResponseRaw {
  recordingId: number;
  attemptId: string;
  transcriptionStatus: RecordingTranscriptionStatus | "QUEUED";
}

/**
 * 전사 재시도 응답(202). 요청 본문은 없어요.
 * 실패한 전사를 새 시도 ID로 다시 접수했다는 뜻이고 전사가 성공했다는 뜻은 아니에요. 결과는 녹음 상세 조회로 확인해요.
 * 다시 시도할 수 없으면 409(`TRANSCRIPTION_RETRY_NOT_ALLOWED` · `TRANSCRIPTION_BUSY` · `TRANSCRIPTION_DISABLED` · `AUDIO_UPLOAD_NOT_COMPLETED`)예요
 */
export class PostRecordingTranscriptionRetryResponseDto {
  /** 녹음 세션 ID. 요청한 녹음과 같아요 */
  recordingId: number;
  /** 접수한 새 전사 시도의 UUID. 녹음 상세의 `transcriptionAttemptId`가 이 값으로 바뀌어요 */
  attemptId: string;
  /** 접수한 뒤의 전사 상태. 다른 전사가 실행 중이면 `QUEUED`로 기다리고, 실패가 아니에요 */
  transcriptionStatus: RecordingTranscriptionStatus | "QUEUED";

  constructor(raw: PostRecordingTranscriptionRetryResponseRaw) {
    this.recordingId = raw.recordingId;
    this.attemptId = raw.attemptId;
    this.transcriptionStatus = raw.transcriptionStatus;
  }
}
