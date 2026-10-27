import {
  DocumentTranscriptSegmentDto,
  type DocumentTranscriptSegmentRaw,
} from "@api/dto/document";

/**
 * 녹음 문서 DTO. 녹음 하나의 제목 · 회의 전체 요약 · 전사 · 음성을 한 화면에서 다시 보는 녹음 문서(STT-R36)가 쓰는 값이에요.
 *
 * - GET  /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/document
 * - GET  /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript
 * - GET  /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback
 * - POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/meeting-summary/retry
 */

/**
 * 회의 전체 요약 상태. `QUEUED` · `RUNNING`은 화면의 「만드는 중」, `SUCCEEDED`는 요약 완료, `FAILED`는 요약 실패예요
 */
export type MeetingSummaryStatus =
  "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

/** 회의 전체 요약의 서버 응답 모양 */
export interface RecordingMeetingSummaryRaw {
  status: MeetingSummaryStatus;
  content: string | null;
  retryable: boolean;
}

/** 녹음 하나의 회의 전체 요약. 녹음 문서 조회 응답 안에 들어 있어요 */
export class RecordingMeetingSummaryDto {
  /** 요약 상태 */
  status: MeetingSummaryStatus;
  /** 읽기 전용 요약 본문(마크다운). 첫 줄은 `## 한눈에 보기`, 마지막 줄은 `회의 핵심 결론:`으로 시작해요. `SUCCEEDED`일 때만 값이 있고 나머지는 null */
  content: string | null;
  /** 실패한 요약을 지금 다시 접수할 수 있으면 true. `FAILED`일 때만 뜻이 있어요. 서버 필드 `retryable`을 이름만 바꿨어요 */
  isRetryable: boolean;

  constructor(raw: RecordingMeetingSummaryRaw) {
    this.status = raw.status;
    this.content = raw.content;
    this.isRetryable = raw.retryable;
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/document

/**
 * 녹음 문서의 처리 상태.
 *
 * - `PROCESSING`: 음성 최종화 · 전사 · 주제별 문서 작업이 대기하거나 진행 중이에요. 실패가 남아 있어도 진행 중인 작업이 있으면 이 값이에요
 * - `TRANSCRIPTION_FAILED`: 전사가 실패했어요
 * - `DOCUMENT_FAILED`: 주제 분류나 주제별 문서 생성이 실패했어요. 둘 중 무엇인지는 이 값만으로 알 수 없어요
 * - `NO_CONTENT`: 전사 결과에 문서로 정리할 내용이 없어요
 * - `COMPLETED`: 모든 주제의 문서를 만들었어요
 *
 * 회의 전체 요약의 진행은 이 값에 넣지 않아요(`meetingSummary`)
 */
export type RecordingDocumentProcessingStatus =
  | "PROCESSING"
  | "TRANSCRIPTION_FAILED"
  | "DOCUMENT_FAILED"
  | "NO_CONTENT"
  | "COMPLETED";

/** 녹음 문서 조회의 서버 응답 모양 */
export interface GetRecordingDocumentResponseRaw {
  recordingId: number;
  title: string;
  startedAt: string;
  recordingDurationSeconds: number;
  processingStatus: RecordingDocumentProcessingStatus;
  retryable: boolean;
  retryableJobIds: number[];
  meetingSummary: RecordingMeetingSummaryRaw | null;
}

/** 녹음 문서 조회 응답. 현재 워크스페이스 멤버는 누구의 녹음이든 볼 수 있어요 */
export class GetRecordingDocumentResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 표시 제목. AI 녹음 제목이 없으면 서버가 「{녹음을 시작한 멤버 닉네임} 님의 녹음」으로 채워 줘요(STT-R37) */
  title: string;
  /** 녹음 시작 시각(ISO 8601, UTC) */
  startedAt: string;
  /** 일시정지를 뺀 녹음 길이(초). 소수 초는 버려요 */
  recordingDurationSeconds: number;
  /** 처리 상태 */
  processingStatus: RecordingDocumentProcessingStatus;
  /**
   * 처리 실패를 지금 다시 접수할 수 있으면 true. `TRANSCRIPTION_FAILED`는 전사 다시 시도, `DOCUMENT_FAILED`는 `retryableJobIds`의 문서 생성 작업 다시 시도를 뜻해요.
   * 다른 상태는 false예요. 서버 필드 `retryable`을 이름만 바꿨어요
   */
  isRetryable: boolean;
  /** `DOCUMENT_FAILED`에서 지금 다시 접수할 수 있는 실패한 문서 생성 작업 ID. 다른 상태는 빈 배열이에요 */
  retryableJobIds: number[];
  /**
   * 회의 전체 요약. 전사가 성공하고 정리할 내용이 있는 녹음에서 요약이 접수됐을 때만 값이 있어요.
   * 전사 전 · 전사 실패 · 주제 분류 전이나 실패 · 정리할 내용 없음, 그리고 요약 기능을 켜기 전에 끝난 녹음은 null이에요
   */
  meetingSummary: RecordingMeetingSummaryDto | null;

  constructor(raw: GetRecordingDocumentResponseRaw) {
    this.recordingId = raw.recordingId;
    this.title = raw.title;
    this.startedAt = raw.startedAt;
    this.recordingDurationSeconds = raw.recordingDurationSeconds;
    this.processingStatus = raw.processingStatus;
    this.isRetryable = raw.retryable;
    this.retryableJobIds = raw.retryableJobIds;
    this.meetingSummary =
      raw.meetingSummary === null
        ? null
        : new RecordingMeetingSummaryDto(raw.meetingSummary);
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/transcript

/** 녹음 전사 조회의 서버 응답 모양 */
export interface GetRecordingTranscriptResponseRaw {
  recordingId: number;
  transcriptId: number;
  transcriptText: string;
  segments: DocumentTranscriptSegmentRaw[];
}

/**
 * 녹음 전사 조회 응답. 문서 원문 조회와 같은 데이터 · 구조 · 정렬이에요.
 * 전사 전 · 전사 실패 · 주제 분류 전이나 실패 · 정리할 내용 없음이면 404 `RECORDING_TRANSCRIPT_NOT_FOUND`로 답해요
 */
export class GetRecordingTranscriptResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 저장된 전사 원문 ID. 같은 녹음에서 나온 문서의 원문 ID와 같아요 */
  transcriptId: number;
  /** 발화 구간. 시작 시각 오름차순(같으면 저장된 순서)으로 와요 */
  segments: DocumentTranscriptSegmentDto[];

  // 화면은 구간만 그리므로 전체 원문(transcriptText)은 옮기지 않아요
  constructor(raw: GetRecordingTranscriptResponseRaw) {
    this.recordingId = raw.recordingId;
    this.transcriptId = raw.transcriptId;
    this.segments = raw.segments.map(
      (segment) => new DocumentTranscriptSegmentDto(segment),
    );
  }
}

// GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-playback

/**
 * 녹음 음성 상태. `AVAILABLE`은 들을 수 있음, `PREPARING`은 조각 녹음의 음성을 아직 합치는 중,
 * `NO_AUDIO`는 지난 정리 정책으로 음성 파일이 지워졌거나 저장 기록이 없음이에요. 녹음 문서의 처리 상태와 따로 계산해요
 */
export type RecordingAudioPlaybackStatus =
  "AVAILABLE" | "PREPARING" | "NO_AUDIO";

/** 녹음 음성 재생 정보 조회의 서버 응답 모양 */
export interface GetRecordingAudioPlaybackResponseRaw {
  recordingId: number;
  audioStatus: RecordingAudioPlaybackStatus;
  playbackUrl: string | null;
  expiresAt: string | null;
  contentType: string | null;
  recordingDurationMillis: number;
}

/** 녹음 음성 재생 정보 조회 응답. 저장소를 확인하지 못하면 500 `AUDIO_STORAGE_UNAVAILABLE`로 답해요 */
export class GetRecordingAudioPlaybackResponseDto {
  /** 녹음 세션 ID */
  recordingId: number;
  /** 음성 상태 */
  audioStatus: RecordingAudioPlaybackStatus;
  /** 음성 파일의 서명 다운로드 URL(절대 URL). `<audio src>`에 crossorigin 없이 넣어요. `AVAILABLE`일 때만 값이 있어요 */
  playbackUrl: string | null;
  /** `playbackUrl` 만료 시각(ISO 8601). 지나면 같은 요청으로 새 URL을 받아요. `AVAILABLE`일 때만 값이 있어요 */
  expiresAt: string | null;
  /** 저장된 원본 형식. `audio/webm` 또는 `audio/wav`. `AVAILABLE`일 때만 값이 있어요 */
  contentType: string | null;
  /** 일시정지를 뺀 녹음 길이(ms). 재생 위치 0은 전사 구간 `startMillis`의 0과 같아요 */
  recordingDurationMillis: number;

  constructor(raw: GetRecordingAudioPlaybackResponseRaw) {
    this.recordingId = raw.recordingId;
    this.audioStatus = raw.audioStatus;
    this.playbackUrl = raw.playbackUrl;
    this.expiresAt = raw.expiresAt;
    this.contentType = raw.contentType;
    this.recordingDurationMillis = raw.recordingDurationMillis;
  }
}

// POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/meeting-summary/retry

/** 회의 전체 요약 다시 시도의 서버 응답 모양 */
export interface PostMeetingSummaryRetryResponseRaw {
  recordingId: number;
  meetingSummaryStatus: MeetingSummaryStatus;
}

/**
 * 회의 전체 요약 다시 시도 응답(202). 요청 본문은 없어요.
 * 요약을 다시 접수했다는 뜻이고 요약이 만들어졌다는 뜻은 아니에요. 결과는 녹음 문서 조회로 확인해요.
 * 다시 시도할 수 없으면 409 `MEETING_SUMMARY_RETRY_NOT_ALLOWED`예요
 */
export class PostMeetingSummaryRetryResponseDto {
  /** 녹음 세션 ID. 요청한 녹음과 같아요 */
  recordingId: number;
  /** 접수 당시 요약 상태. 항상 `QUEUED` */
  meetingSummaryStatus: MeetingSummaryStatus;

  constructor(raw: PostMeetingSummaryRetryResponseRaw) {
    this.recordingId = raw.recordingId;
    this.meetingSummaryStatus = raw.meetingSummaryStatus;
  }
}
