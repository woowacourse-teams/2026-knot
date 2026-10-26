import {
  RecordingSourceAudioDto,
  type RecordingSourceAudioRaw,
} from "@api/dto/recording";

/**
 * 문서 DTO
 *
 * - GET /api/v1/workspaces/{workspaceId}/documents
 * - GET /api/v1/workspaces/{workspaceId}/documents/{documentId}
 * - GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations
 * - PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations/me
 * - GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript
 * - GET /api/v1/workspaces/{workspaceId}/document-recordings
 */

/** 문서 상태. 확인 대상이 모두 확인하거나 제외되면 ARCHIVED로 바뀌어요 */
export type DocumentStatus = "DRAFT" | "ARCHIVED";

/** 내 확인 상태. 상세·목록 응답이 같은 기준을 써요 */
export type MyConfirmationState = "PENDING" | "CONFIRMED" | "NOT_REQUIRED";

/** 확인 집계의 서버 응답 모양 */
export interface ConfirmationSummaryRaw {
  confirmedCount: number;
  pendingCount: number;
  excludedCount: number;
}

/** 문서 확인 집계. 문서 목록 · 문서 상세 · 확인 대상 조회 · 내 확인 응답이 담아요 */
export class ConfirmationSummaryDto {
  /** 확인을 마친 대상 수. 확인한 뒤 워크스페이스를 나가도 포함해요 */
  confirmedCount: number;
  /** 아직 확인하지 않은, 현재 활성 멤버인 대상 수 */
  pendingCount: number;
  /** 확인하지 않은 채 워크스페이스를 나가 보관 조건에서 빠진 대상 수 */
  excludedCount: number;

  constructor(raw: ConfirmationSummaryRaw) {
    this.confirmedCount = raw.confirmedCount;
    this.pendingCount = raw.pendingCount;
    this.excludedCount = raw.excludedCount;
  }
}

// GET /api/v1/workspaces/{workspaceId}/documents

/** 주제 폴더 하나의 서버 응답 모양 */
export interface DocumentTopicRaw {
  topic: string;
  documentCount: number;
}

/** 문서가 있는 주제 폴더 하나. 문서 목록 조회 응답의 `topics`가 담아요 */
export class DocumentTopicDto {
  /** 폴더 이름. AI가 분류한 주제 (예: "회원") */
  topic: string;
  /**
   * 조회 조건(녹음 ID 등)을 만족하는 이 주제의 문서 수.
   * 한 응답에 담긴 문서 수가 아니라 cursor · size와 관계없이 센 값이에요. 조건이 없으면 워크스페이스 전체를 센 값이에요
   */
  documentCount: number;

  constructor(raw: DocumentTopicRaw) {
    this.topic = raw.topic;
    this.documentCount = raw.documentCount;
  }
}

/** 문서 목록 항목의 서버 응답 모양 */
export interface DocumentListItemRaw {
  id: number;
  recordingSessionId: number;
  topic: string;
  title: string;
  summary: string | null;
  status: DocumentStatus;
  createdAt: string;
  recordingDurationSeconds: number;
  myConfirmationState: MyConfirmationState;
  confirmationSummary: ConfirmationSummaryRaw;
}

/** 문서 목록의 문서 하나. 문서 상세에서 본문 · 보관 시각 · 원문 ID가 빠진 모양이에요 */
export class DocumentListItemDto {
  /** 문서 ID */
  id: number;
  /** 이 문서가 만들어진 녹음 ID. 녹음 API의 `recordingId`와 같은 녹음이에요 */
  recordingSessionId: number;
  /** 폴더 이름. 응답의 `topics` 중 하나와 같아요 */
  topic: string;
  /** 읽기 전용 제목 */
  title: string;
  /** 한 줄 요약. 없으면 null */
  summary: string | null;
  /** DRAFT 또는 ARCHIVED. 화면에는 쓰지 않아요 (DOC-R10) */
  status: DocumentStatus;
  /** 문서 생성 시각(ISO 8601, UTC) */
  createdAt: string;
  /** 일시정지를 뺀 원본 녹음 길이(초). 서버가 소수 초를 버린 정수로 보내요 */
  recordingDurationSeconds: number;
  /** 내 확인 상태. 확인 대상이 아니면 NOT_REQUIRED */
  myConfirmationState: MyConfirmationState;
  /** 확인 집계 */
  confirmationSummary: ConfirmationSummaryDto;

  constructor(raw: DocumentListItemRaw) {
    this.id = raw.id;
    this.recordingSessionId = raw.recordingSessionId;
    this.topic = raw.topic;
    this.title = raw.title;
    this.summary = raw.summary;
    this.status = raw.status;
    this.createdAt = raw.createdAt;
    this.recordingDurationSeconds = raw.recordingDurationSeconds;
    this.myConfirmationState = raw.myConfirmationState;
    this.confirmationSummary = new ConfirmationSummaryDto(
      raw.confirmationSummary,
    );
  }
}

/** 문서 목록 조회의 서버 응답 모양 */
export interface GetDocumentsResponseRaw {
  topics: DocumentTopicRaw[];
  items: DocumentListItemRaw[];
  nextCursor: string | null;
}

/**
 * 문서 목록 조회 응답.
 *
 * 문서(`items`)만 한 페이지씩 나눠서 오고, 주제 폴더(`topics`)는 어느 페이지에서나 전체가 와요.
 * 녹음 ID 같은 조회 조건은 `items`와 `topics` 모두에 적용돼요.
 */
export class GetDocumentsResponseDto {
  /** 조회 조건을 만족하는 문서가 있는 주제 폴더 전체. 조건에 맞는 문서가 없는 주제는 들어 있지 않아요. 순서는 명세에 없어요 */
  topics: DocumentTopicDto[];
  /** 문서 한 페이지. 최신순(생성 시각 내림차순, 같으면 ID 내림차순)이에요 */
  items: DocumentListItemDto[];
  /** 다음 페이지 커서. 마지막 페이지면 null */
  nextCursor: string | null;

  constructor(raw: GetDocumentsResponseRaw) {
    this.topics = raw.topics.map((topic) => new DocumentTopicDto(topic));
    this.items = raw.items.map((item) => new DocumentListItemDto(item));
    this.nextCursor = raw.nextCursor;
  }
}

// GET /api/v1/workspaces/{workspaceId}/documents/{documentId}

/** 문서 상세 조회의 서버 응답 모양 */
export interface GetDocumentResponseRaw {
  id: number;
  recordingSessionId: number;
  topic: string;
  title: string;
  summary: string | null;
  content: string;
  status: DocumentStatus;
  createdAt: string;
  archivedAt: string | null;
  recordingDurationSeconds: number;
  sourceTranscriptId: number;
  myConfirmationState: MyConfirmationState;
  confirmationSummary: ConfirmationSummaryRaw;
  /** PCM 조각 저장 전의 서버는 필드 자체를 주지 않아요 */
  sourceAudio?: RecordingSourceAudioRaw | null;
}

/** 문서 상세 조회 응답 */
export class GetDocumentResponseDto {
  /** 문서 ID */
  id: number;
  /** 이 문서가 만들어진 녹음 ID. 녹음 API의 `recordingId`와 같은 녹음이에요 */
  recordingSessionId: number;
  /** 폴더 이름. AI가 분류한 주제 (예: "회원") */
  topic: string;
  /** 읽기 전용 제목 */
  title: string;
  /** 한 줄 요약. 없으면 null */
  summary: string | null;
  /** 읽기 전용 Markdown 본문. 정한 문법은 `#` · `##` · `###` · `-` · `**굵게**` */
  content: string;
  /** DRAFT 또는 ARCHIVED. 화면에는 쓰지 않아요 (DOC-R10) */
  status: DocumentStatus;
  /** 문서 생성 시각(ISO 8601, UTC) */
  createdAt: string;
  /** 보관 전환 시각(ISO 8601, UTC). DRAFT면 null */
  archivedAt: string | null;
  /** 일시정지를 뺀 원본 녹음 길이(초). 서버가 소수 초를 버린 정수로 보내요 */
  recordingDurationSeconds: number;
  /** 이 문서를 만든 원문(Transcript) ID */
  sourceTranscriptId: number;
  /** 내 확인 상태. 확인 대상이 아니면 NOT_REQUIRED */
  myConfirmationState: MyConfirmationState;
  /** 확인 집계 */
  confirmationSummary: ConfirmationSummaryDto;
  /** 문서를 만든 녹음의 원본 오디오 품질. `PARTIAL`이면 원본에 빠진 구간이 있어요. WebM 녹음이면 null. 서버가 주지 않으면 null */
  sourceAudio: RecordingSourceAudioDto | null;

  constructor(raw: GetDocumentResponseRaw) {
    this.id = raw.id;
    this.recordingSessionId = raw.recordingSessionId;
    this.topic = raw.topic;
    this.title = raw.title;
    this.summary = raw.summary;
    this.content = raw.content;
    this.status = raw.status;
    this.createdAt = raw.createdAt;
    this.archivedAt = raw.archivedAt;
    this.recordingDurationSeconds = raw.recordingDurationSeconds;
    this.sourceTranscriptId = raw.sourceTranscriptId;
    this.myConfirmationState = raw.myConfirmationState;
    this.confirmationSummary = new ConfirmationSummaryDto(
      raw.confirmationSummary,
    );
    this.sourceAudio = raw.sourceAudio
      ? new RecordingSourceAudioDto(raw.sourceAudio)
      : null;
  }
}

// GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations

/** 확인 대상 한 명의 상태. EXCLUDED는 확인하지 않은 채 워크스페이스를 나간 대상이에요 */
export type DocumentConfirmationState = "CONFIRMED" | "PENDING" | "EXCLUDED";

/** 확인 대상 한 명의 서버 응답 모양 */
export interface DocumentConfirmationItemRaw {
  memberId: number;
  nickname: string;
  profileImageUrl: string | null;
  confirmedAt: string | null;
  state: DocumentConfirmationState;
}

/** 문서의 확인 대상 한 명. 확인 대상 조회 응답의 `items`가 담아요 */
export class DocumentConfirmationItemDto {
  /** 대상 멤버 ID */
  memberId: number;
  /** 대상의 지금 닉네임 */
  nickname: string;
  /** 대상의 지금 프로필 이미지(절대 URL). 없으면 null */
  profileImageUrl: string | null;
  /** 처음 확인한 시각(ISO 8601, UTC). 확인하지 않았으면 null */
  confirmedAt: string | null;
  /** CONFIRMED · PENDING · EXCLUDED. 확인한 뒤 나간 대상은 CONFIRMED로 남고, EXCLUDED는 확인하지 않은 채 나간 대상이에요 */
  state: DocumentConfirmationState;

  constructor(raw: DocumentConfirmationItemRaw) {
    this.memberId = raw.memberId;
    this.nickname = raw.nickname;
    this.profileImageUrl = raw.profileImageUrl;
    this.confirmedAt = raw.confirmedAt;
    this.state = raw.state;
  }
}

/**
 * 확인 대상 조회의 서버 응답 모양.
 *
 * `confirmedByMe`는 확인 대상이 아닐 때와 아직 확인하지 않았을 때가 모두 false라 둘을 구분하지 못해요.
 */
export interface GetDocumentConfirmationsResponseRaw {
  documentId: number;
  confirmedCount: number;
  pendingCount: number;
  excludedCount: number;
  confirmedByMe: boolean;
  items: DocumentConfirmationItemRaw[];
  nextCursor: string | null;
}

/** 문서의 확인 대상과 진행 현황 조회 응답 */
export class GetDocumentConfirmationsResponseDto {
  /** 문서 ID */
  documentId: number;
  /** 확인 집계. 서버가 따로 준 세 값(confirmedCount · pendingCount · excludedCount)을 문서 상세와 같은 모양으로 묶었어요 */
  confirmationSummary: ConfirmationSummaryDto;
  /** 문서가 만들어질 때 정해진 확인 대상. 한 번에 최대 100명 */
  items: DocumentConfirmationItemDto[];
  /** 다음 페이지 커서. 마지막 페이지면 null */
  nextCursor: string | null;

  // 내 확인 상태는 문서 상세의 myConfirmationState를 쓰므로 confirmedByMe는 옮기지 않아요
  constructor(raw: GetDocumentConfirmationsResponseRaw) {
    this.documentId = raw.documentId;
    this.confirmationSummary = new ConfirmationSummaryDto(raw);
    this.items = raw.items.map((item) => new DocumentConfirmationItemDto(item));
    this.nextCursor = raw.nextCursor;
  }
}

// PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/confirmations/me

/** 내 문서 확인의 서버 응답 모양 */
export interface PutDocumentConfirmationResponseRaw {
  documentId: number;
  confirmedAt: string;
  documentStatus: DocumentStatus;
  archivedAt: string | null;
  confirmationSummary: ConfirmationSummaryRaw;
}

/**
 * 내 문서 확인 응답. 요청 본문은 없고, 로그인한 멤버가 확인한 것으로 기록돼요.
 *
 * 이미 확인한 문서에 다시 요청해도 같은 결과(200)를 돌려줘요. 확인은 취소할 수 없어요.
 */
export class PutDocumentConfirmationResponseDto {
  /** 확인한 문서 ID */
  documentId: number;
  /** 처음 확인한 시각(ISO 8601, UTC). 다시 요청해도 바뀌지 않아요 */
  confirmedAt: string;
  /** 확인 뒤의 문서 상태. 이 확인으로 남은 대상이 없어지면 ARCHIVED */
  documentStatus: DocumentStatus;
  /** 보관 전환 시각(ISO 8601, UTC). DRAFT면 null */
  archivedAt: string | null;
  /** 확인 뒤의 확인 집계. 내 확인 상태(myConfirmationState)는 들어 있지 않아요 */
  confirmationSummary: ConfirmationSummaryDto;

  constructor(raw: PutDocumentConfirmationResponseRaw) {
    this.documentId = raw.documentId;
    this.confirmedAt = raw.confirmedAt;
    this.documentStatus = raw.documentStatus;
    this.archivedAt = raw.archivedAt;
    this.confirmationSummary = new ConfirmationSummaryDto(
      raw.confirmationSummary,
    );
  }
}

// GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/transcript

/** 원문의 발화 구간 하나의 서버 응답 모양 */
export interface DocumentTranscriptSegmentRaw {
  startMillis: number;
  endMillis: number | null;
  speakerNumber: number | null;
  text: string;
}

/** 원문의 발화 구간 하나. 문서 원문 조회 응답의 `segments`가 담아요 */
export class DocumentTranscriptSegmentDto {
  /** 녹음한 오디오의 시작을 0으로 한 발화 시작 위치(ms). 0 이상 */
  startMillis: number;
  /** 발화가 끝난 위치(ms). 알 수 없으면 null. 값이 있으면 `startMillis` 이상 */
  endMillis: number | null;
  /** 같은 원문 안에서만 쓰는 익명 화자 번호(1부터). 화자를 알 수 없으면 null. 멤버와 연결되지 않아요 */
  speakerNumber: number | null;
  /** 읽기 전용 발화 문장 */
  text: string;

  constructor(raw: DocumentTranscriptSegmentRaw) {
    this.startMillis = raw.startMillis;
    this.endMillis = raw.endMillis;
    this.speakerNumber = raw.speakerNumber;
    this.text = raw.text;
  }
}

/**
 * 문서 원문 조회의 서버 응답 모양. 백엔드 구현을 기준으로 적었어요.
 *
 * 노션 명세는 `segments`가 빈 배열일 수 있고 `startMillis`가 null일 수 있다고 적지만,
 * 서버는 구간이 1개 이상이고 시작 시각이 있는 원문만 돌려주고, 구간이 없으면 500으로 답해요.
 */
export interface GetDocumentTranscriptResponseRaw {
  transcriptId: number;
  /** 지금은 늘 false */
  isPartial: boolean;
  recordingDurationSeconds: number;
  /** 저장된 전체 원문 */
  transcriptText: string;
  segments: DocumentTranscriptSegmentRaw[];
}

/** 문서에 연결된 전사 원문 조회 응답. 녹음에 참여하지 않은 워크스페이스 멤버도 볼 수 있어요 */
export class GetDocumentTranscriptResponseDto {
  /** 문서에 연결된 전사 원문 ID. 문서 상세의 `sourceTranscriptId`와 같아요 */
  transcriptId: number;
  /** 원본 녹음 길이(초) */
  recordingDurationSeconds: number;
  /** 발화 구간. 1개 이상이고, 시작 시각 오름차순(같으면 저장된 순서)으로 와요 */
  segments: DocumentTranscriptSegmentDto[];

  // 화면은 구간만 그리므로 전체 원문(transcriptText)과 늘 false인 isPartial은 옮기지 않아요
  constructor(raw: GetDocumentTranscriptResponseRaw) {
    this.transcriptId = raw.transcriptId;
    this.recordingDurationSeconds = raw.recordingDurationSeconds;
    this.segments = raw.segments.map(
      (segment) => new DocumentTranscriptSegmentDto(segment),
    );
  }
}

// GET /api/v1/workspaces/{workspaceId}/document-recordings

/** 녹음별 문서 목록의 문서 항목 서버 응답 모양 */
export interface DocumentRecordingDocumentRaw {
  id: number;
  title: string;
  summary: string | null;
}

/** 녹음별 문서 목록에서 녹음 하나에 묶인 문서 항목. 문서 목록 조회의 문서 카드와 같은 문서예요 */
export class DocumentRecordingDocumentDto {
  /** 문서 ID. 문서 항목을 누르면 이 ID로 문서 보기를 열어요 */
  id: number;
  /** 읽기 전용 문서 제목. 문서 목록 조회의 `title`과 같은 값 */
  title: string;
  /** 한 줄 요약. 문서 목록 조회의 `summary`와 같은 값이며 없으면 null */
  summary: string | null;

  constructor(raw: DocumentRecordingDocumentRaw) {
    this.id = raw.id;
    this.title = raw.title;
    this.summary = raw.summary;
  }
}

/** 녹음별 문서 목록의 녹음 묶음 서버 응답 모양 */
export interface DocumentRecordingRaw {
  recordingId: number;
  title: string;
  startedAt: string;
  recordingDurationSeconds: number;
  documentCount: number;
  documents: DocumentRecordingDocumentRaw[];
}

/** 녹음별 문서 목록의 녹음 묶음 하나. 문서가 1개 이상 나온 녹음만 와요 */
export class DocumentRecordingDto {
  /** 녹음 세션 ID. 녹음 행을 누르면 이 ID로 녹음 문서를 열어요 */
  recordingId: number;
  /**
   * 표시 제목. 서버가 만들어요. 저장된 AI 녹음 제목이 있으면 그 제목, 없거나 만들지 못했으면
   * `{녹음을 시작한 멤버 닉네임} 님의 녹음`이라 화면에서 대체 이름을 따로 만들지 않아요
   */
  title: string;
  /** 녹음 시작 시각(ISO 8601, UTC). 연도를 포함한 날짜 표시는 화면이 맡아요 */
  startedAt: string;
  /** 일시정지를 뺀 서버 누적 녹음 길이(초). 소수 초는 버린 정수예요 */
  recordingDurationSeconds: number;
  /** 이 녹음의 문서 수. 항상 `documents` 길이와 같고 1 이상이에요 */
  documentCount: number;
  /** 이 녹음에서 나온 문서 전부. 생성 시각 오름차순(같으면 ID 오름차순)이고 페이지로 나뉘지 않아요 */
  documents: DocumentRecordingDocumentDto[];

  constructor(raw: DocumentRecordingRaw) {
    this.recordingId = raw.recordingId;
    this.title = raw.title;
    this.startedAt = raw.startedAt;
    this.recordingDurationSeconds = raw.recordingDurationSeconds;
    this.documentCount = raw.documentCount;
    this.documents = raw.documents.map(
      (document) => new DocumentRecordingDocumentDto(document),
    );
  }
}

/** 녹음별 문서 목록 조회의 서버 응답 모양 */
export interface GetDocumentRecordingsResponseRaw {
  items: DocumentRecordingRaw[];
  nextCursor: string | null;
}

/**
 * 녹음별 문서 목록 조회 응답. 문서 탭 「녹음별」 탭이 써요.
 *
 * 녹음 묶음이 녹음 시작 시각 내림차순(같으면 녹음 ID 내림차순)으로 최대 20개 와요.
 * 한 페이지의 녹음 수는 서버가 20개로 고정해 `size`를 받지 않아요.
 */
export class GetDocumentRecordingsResponseDto {
  /** 녹음 묶음 한 페이지. 문서가 나온 녹음이 없으면 빈 배열 */
  items: DocumentRecordingDto[];
  /** 다음 페이지 커서. 그대로 다음 요청의 `cursor`로 보내고 해석하거나 만들지 않아요. 마지막 페이지면 null */
  nextCursor: string | null;

  constructor(raw: GetDocumentRecordingsResponseRaw) {
    this.items = raw.items.map((item) => new DocumentRecordingDto(item));
    this.nextCursor = raw.nextCursor;
  }
}
