/**
 * 탐색 DTO
 *
 * - GET  /api/v1/workspaces/{workspaceId}/search/conversations
 * - POST /api/v1/workspaces/{workspaceId}/search/conversations (SSE)
 * - POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions (SSE)
 * - POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions/{questionId}/retry (SSE)
 * - GET  /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/messages
 * - GET  /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/events (SSE)
 * - POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}/execute
 * - POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}/cancel
 * - GET  /api/v1/workspaces/{workspaceId}/search/notion-connection
 * - POST /api/v1/workspaces/{workspaceId}/search/notion-connection/authorization
 * - DELETE /api/v1/workspaces/{workspaceId}/search/notion-connection
 */

/** 답변 준비 단계. SEARCHING은 문서 검색 중, GENERATING은 답변 생성 중 */
export type SearchStreamStage = "SEARCHING" | "GENERATING";

/** 메시지를 쓴 쪽. USER는 질문, ASSISTANT는 답변 */
export type SearchMessageRole = "USER" | "ASSISTANT";

/** 메시지 상태. 질문은 RECEIVED, 답변은 STREAMING에서 COMPLETED·FAILED·STOPPED 중 하나로 끝남 */
export type SearchMessageStatus =
  | "RECEIVED"
  | "STREAMING"
  | "COMPLETED"
  | "FAILED"
  | "STOPPED";

/**
 * 답변 근거 문서의 서버 응답 모양. 메시지 조회의 evidences[]와 evidence 이벤트의 items[]가 공유.
 *
 * 임시 가정: evidence 이벤트의 items[]는 API 명세에 필드가 아직 정해지지 않아 메시지 조회의 근거 필드를 따라 둠.
 * 명세가 정해지면 갱신
 */
export interface SearchEvidenceRaw {
  documentId: number;
  title: string;
  topic: string;
  rank: number;
}

/** 답변의 근거가 된 문서 하나. 메시지 조회와 evidence 이벤트가 공유 */
export class SearchEvidenceDto {
  /** 근거 문서 ID */
  documentId: number;
  /** 문서 제목 */
  title: string;
  /** 문서 주제 */
  topic: string;
  /** 관련도 순위. 1~3이고 작을수록 관련도가 높음 */
  rank: number;

  constructor(raw: SearchEvidenceRaw) {
    this.documentId = raw.documentId;
    this.title = raw.title;
    this.topic = raw.topic;
    this.rank = raw.rank;
  }
}

/** 답변에 쓴 노션 페이지의 서버 응답 모양. 메시지 조회의 notionEvidences[]와 notion_evidence 이벤트의 items[]가 공유 */
export interface SearchNotionEvidenceRaw {
  title: string;
  url: string;
}

/** 답변에 실제로 쓴 노션 페이지 하나. 본문과 인용 문구는 서버가 주지 않아요 */
export class SearchNotionEvidenceDto {
  /** 노션 페이지 제목 */
  title: string;
  /** 노션 페이지 주소(절대 URL). 근거에서 이 주소를 열어요 */
  url: string;

  constructor(raw: SearchNotionEvidenceRaw) {
    this.title = raw.title;
    this.url = raw.url;
  }
}

/**
 * 노션을 읽지 못해 회의 문서만으로 답한 사유.
 * NOT_CONNECTED는 연결 없음, PAGE_NOT_FOUND는 맞는 페이지 없음, READ_FAILED는 노션이 느리거나 실패함, CONNECTION_EXPIRED는 연결 만료
 */
export type SearchNotionNoticeReason =
  | "NOT_CONNECTED"
  | "PAGE_NOT_FOUND"
  | "READ_FAILED"
  | "CONNECTION_EXPIRED";

/** 쓰기 확인 카드 상태. PENDING 대기, RUNNING 실행 중, DONE 실행됨, CANCELED 취소됨, FAILED 실패 */
export type SearchWriteCardStatus =
  | "PENDING"
  | "RUNNING"
  | "DONE"
  | "CANCELED"
  | "FAILED";

/**
 * 직전 실행이 다시 누를 수 있는 오류로 대기로 돌아온 사유.
 * NOTION_RECONNECT_REQUIRED는 연결 만료, NOTION_CONNECTION_REQUIRED는 연결 없음,
 * NOTION_RATE_LIMITED는 노션 요청 많음, DATABASE_FIELDS_CHANGED는 DB 구조가 달라져 DB 칸을 갱신함
 */
export type SearchWriteCardRetryReason =
  | "NOTION_RECONNECT_REQUIRED"
  | "NOTION_CONNECTION_REQUIRED"
  | "NOTION_RATE_LIMITED"
  | "DATABASE_FIELDS_CHANGED";

/**
 * 실패한 카드의 이유.
 * LOCATION_PERMISSION_LOST는 위치에 쓸 권한이 없어짐, NOTION_CONNECTION_CHANGED는 카드를 만든 뒤 다른 노션으로 바뀜,
 * BODY_TOO_LONG은 노션이 본문이 길다고 받지 않음, NOTION_NO_RESPONSE는 10초 동안 노션 응답 없음
 */
export type SearchWriteCardFailureReason =
  | "LOCATION_PERMISSION_LOST"
  | "NOTION_CONNECTION_CHANGED"
  | "BODY_TOO_LONG"
  | "NOTION_NO_RESPONSE";

/** 쓰기 확인 카드가 만들 위치의 서버 응답 모양 */
export interface SearchWriteCardLocationRaw {
  name: string;
  path: string;
  kind: "PAGE" | "DATABASE";
}

/** 쓰기 확인 카드가 페이지를 만들 위치 */
export class SearchWriteCardLocationDto {
  /** 상위 페이지나 DB의 이름. 예: 회의록 */
  name: string;
  /** 위치 경로 한 줄. 예: 팀 위키 / 회의록 */
  path: string;
  /** PAGE면 일반 페이지 아래, DATABASE면 DB 아래 */
  kind: "PAGE" | "DATABASE";

  constructor(raw: SearchWriteCardLocationRaw) {
    this.name = raw.name;
    this.path = raw.path;
    this.kind = raw.kind;
  }
}

/** DB 아래에 만들 때 채워질 칸 하나의 서버 응답 모양 */
export interface SearchWriteCardDatabaseFieldRaw {
  name: string;
  value: string;
}

/** DB 아래에 만들 때 채워질 칸 하나 */
export class SearchWriteCardDatabaseFieldDto {
  /** 속성 이름. 예: 날짜 */
  name: string;
  /** 채워질 값. 제목 속성은 초안 제목, 날짜 속성은 회의 날짜(예: 2026-11-13) */
  value: string;

  constructor(raw: SearchWriteCardDatabaseFieldRaw) {
    this.name = raw.name;
    this.value = raw.value;
  }
}

/** 초안의 근거가 된 회의 문서의 서버 응답 모양 */
export interface SearchWriteCardSourceDocumentRaw {
  documentId: number;
  title: string;
}

/** 초안의 근거가 된 회의 문서 */
export class SearchWriteCardSourceDocumentDto {
  /** 회의 문서 ID */
  documentId: number;
  /** 회의 문서 제목 */
  title: string;

  constructor(raw: SearchWriteCardSourceDocumentRaw) {
    this.documentId = raw.documentId;
    this.title = raw.title;
  }
}

/** 이미 만든 페이지 이력 하나의 서버 응답 모양 */
export interface SearchWriteCardExistingPageRaw {
  url: string;
  createdAt: string;
}

/** 같은 탐색 대화에서 같은 회의 문서로 이미 만든 노션 페이지 하나. 노션에 지금도 있는지는 확인하지 않은 이력 */
export class SearchWriteCardExistingPageDto {
  /** 만든 노션 페이지 주소(절대 URL) */
  url: string;
  /** 만든 시각(ISO 8601) */
  createdAt: string;

  constructor(raw: SearchWriteCardExistingPageRaw) {
    this.url = raw.url;
    this.createdAt = raw.createdAt;
  }
}

/**
 * 쓰기 확인 카드의 서버 응답 모양.
 * 카드 실행·취소 응답, write_card 이벤트의 card, 메시지 조회의 writeCard가 공유
 */
export interface SearchWriteCardRaw {
  id: number;
  type: "NOTION_PAGE_CREATE";
  status: SearchWriteCardStatus;
  location: SearchWriteCardLocationRaw;
  title: string;
  body: string;
  databaseFields: SearchWriteCardDatabaseFieldRaw[] | null;
  sourceDocument: SearchWriteCardSourceDocumentRaw;
  executable: boolean;
  createdPageUrl: string | null;
  retryReason: SearchWriteCardRetryReason | null;
  failureReason: SearchWriteCardFailureReason | null;
  existingPages: SearchWriteCardExistingPageRaw[];
}

/** 노션에 쓰기 전에 무엇이 생기는지 보여 주는 확인 카드. 보기 전용이고 값은 채팅으로만 바꿔요 */
export class SearchWriteCardDto {
  /** 쓰기 확인 카드 ID. 실행·취소 경로에 실어요 */
  id: number;
  /** 쓰기 종류. 지금은 노션 페이지 만들기 하나 */
  type: "NOTION_PAGE_CREATE";
  /** 카드 상태. DONE·CANCELED·FAILED는 끝 상태 */
  status: SearchWriteCardStatus;
  /** 만들 위치 */
  location: SearchWriteCardLocationDto;
  /** 만들 페이지 제목 */
  title: string;
  /** 만들 페이지 본문 전체. 화면이 앞부분만 보이고 펼쳐요 */
  body: string;
  /** DB 아래에 만들 때 채워질 칸. 일반 페이지 아래면 null */
  databaseFields: SearchWriteCardDatabaseFieldDto[] | null;
  /** 초안의 근거가 된 회의 문서 */
  sourceDocument: SearchWriteCardSourceDocumentDto;
  /** 실행 요청을 받는 카드인지. DB 속성을 확인하지 못한 카드는 false라 [만들기]를 누를 수 없어요 */
  executable: boolean;
  /** 만든 노션 페이지 주소. status가 DONE일 때만 값이 있고 그 밖에는 null */
  createdPageUrl: string | null;
  /** 직전 실행이 대기로 돌아온 사유. 없으면 null */
  retryReason: SearchWriteCardRetryReason | null;
  /** status가 FAILED일 때 이유. 그 밖에는 null */
  failureReason: SearchWriteCardFailureReason | null;
  /** 같은 대화에서 같은 회의 문서로 이미 만든 페이지 이력. 최근에 만든 것부터, 없으면 빈 배열 */
  existingPages: SearchWriteCardExistingPageDto[];

  constructor(raw: SearchWriteCardRaw) {
    this.id = raw.id;
    this.type = raw.type;
    this.status = raw.status;
    this.location = new SearchWriteCardLocationDto(raw.location);
    this.title = raw.title;
    this.body = raw.body;
    this.databaseFields =
      raw.databaseFields?.map(
        (field) => new SearchWriteCardDatabaseFieldDto(field),
      ) ?? null;
    this.sourceDocument = new SearchWriteCardSourceDocumentDto(
      raw.sourceDocument,
    );
    this.executable = raw.executable;
    this.createdPageUrl = raw.createdPageUrl;
    this.retryReason = raw.retryReason;
    this.failureReason = raw.failureReason;
    this.existingPages = raw.existingPages.map(
      (page) => new SearchWriteCardExistingPageDto(page),
    );
  }
}

// GET /api/v1/workspaces/{workspaceId}/search/conversations

/** 대화 목록 한 줄의 서버 응답 모양 */
export interface SearchConversationRaw {
  id: number;
  title: string;
  lastMessagePreview: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 대화 목록에 놓이는 탐색 대화 하나 */
export class SearchConversationDto {
  /** 탐색 대화 ID */
  id: number;
  /** 첫 질문에서 만든 표시 제목 */
  title: string;
  /** 마지막 메시지 미리보기. 메시지가 없으면 null */
  lastMessagePreview: string | null;
  /** 첫 질문을 접수한 시각(ISO 8601) */
  createdAt: string;
  /** 마지막으로 메시지가 오간 시각(ISO 8601). 목록은 이 값의 내림차순 */
  updatedAt: string;

  constructor(raw: SearchConversationRaw) {
    this.id = raw.id;
    this.title = raw.title;
    this.lastMessagePreview = raw.lastMessagePreview;
    this.createdAt = raw.createdAt;
    this.updatedAt = raw.updatedAt;
  }
}

/** 대화 목록 조회의 서버 응답 모양 */
export interface GetSearchConversationsResponseRaw {
  items: SearchConversationRaw[];
  nextCursor: string | null;
}

/** 내 탐색 대화 목록 한 페이지 조회 응답 */
export class GetSearchConversationsResponseDto {
  /** 대화 목록. updatedAt 내림차순, 같으면 ID 내림차순. 빈 대화와 첫 답변이 실패한 대화는 서버가 빼요 */
  items: SearchConversationDto[];
  /** 다음 페이지를 받을 커서. 마지막 페이지면 null */
  nextCursor: string | null;

  constructor(raw: GetSearchConversationsResponseRaw) {
    this.items = raw.items.map((item) => new SearchConversationDto(item));
    this.nextCursor = raw.nextCursor;
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/conversations (SSE)

/** 첫 질문 전송 시 앱이 넘기는 값 */
export interface PostSearchConversationRequestInput {
  content: string;
  requestId: string;
}

/** 첫 질문 전송 요청 본문. 응답은 JSON이 아니라 SSE 스트림 */
export class PostSearchConversationRequestDto {
  /** 질문 내용. 앞뒤 공백 제거 */
  content: string;
  /** 같은 질문의 재전송을 서버가 알아보는 키(UUID). 재시도에도 같은 값을 보냄 */
  requestId: string;

  constructor({ content, requestId }: PostSearchConversationRequestInput) {
    this.content = content.trim();
    this.requestId = requestId;
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions (SSE)

/** 후속 질문 전송 시 앱이 넘기는 값 */
export interface PostSearchQuestionRequestInput {
  content: string;
  requestId: string;
}

/** 후속 질문 전송 요청 본문. 첫 질문과 모양이 같고 응답도 같은 SSE 스트림 */
export class PostSearchQuestionRequestDto {
  /** 질문 내용. 앞뒤 공백 제거 */
  content: string;
  /** 같은 질문의 재전송을 서버가 알아보는 키(UUID). 재시도에도 같은 값을 보냄 */
  requestId: string;

  constructor({ content, requestId }: PostSearchQuestionRequestInput) {
    this.content = content.trim();
    this.requestId = requestId;
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/questions/{questionId}/retry (SSE)

/** 실패한 답변 재시도 시 앱이 넘기는 값 */
export interface PostSearchAnswerRetryRequestInput {
  requestId: string;
}

/**
 * 실패한 답변 재시도 요청 본문. 질문 내용은 경로의 질문을 다시 쓰므로 싣지 않아요.
 * 응답은 첫 질문과 같은 SSE 스트림이지만 accepted만 모양이 달라요(`SearchAnswerRetryAcceptedDto`)
 */
export class PostSearchAnswerRetryRequestDto {
  /** 같은 재시도 요청의 중복 실행을 서버가 알아보는 키(UUID) */
  requestId: string;

  constructor({ requestId }: PostSearchAnswerRetryRequestInput) {
    this.requestId = requestId;
  }
}

/** 재시도 스트림 `event: accepted`의 data 모양. 첫 질문과 달리 대화 ID가 없고 질문 ID 이름이 `questionId`예요 */
export interface SearchAnswerRetryAcceptedRaw {
  questionId: number;
  answerMessageId: number;
  status: "STREAMING";
}

/**
 * 재시도 요청이 접수됐음을 알리는 이벤트. 재시도 스트림의 첫 이벤트.
 * 요청 함수가 경로의 대화 ID와 합쳐 첫 질문과 같은 `SearchStreamAcceptedDto`로 바꿔 내므로 앱의 다른 곳은 이 모양을 보지 않아요
 */
export class SearchAnswerRetryAcceptedDto {
  /** 다시 쓴 질문 메시지 ID. 경로의 질문 ID와 같음 */
  questionId: number;
  /** 비우고 다시 채우는 답변 메시지 ID. 실패했던 답변 메시지와 같고, 뒤따르는 이벤트의 answerMessageId와 같음 */
  answerMessageId: number;
  /** 접수 상태. 항상 STREAMING이고, 끝난 상태는 후속 이벤트로 알림 */
  status: "STREAMING";

  constructor(raw: SearchAnswerRetryAcceptedRaw) {
    this.questionId = raw.questionId;
    this.answerMessageId = raw.answerMessageId;
    this.status = raw.status;
  }
}

// 아래 이벤트 DTO는 첫 질문·후속 질문·재시도 스트림이 공유 (재시도의 accepted만 서버 모양이 달라 요청 함수가 이 모양으로 맞춰요)

/** `event: accepted`의 data 모양. 재시도 요청 함수는 서버의 `SearchAnswerRetryAcceptedRaw`와 경로의 대화 ID로 이 모양을 만들어요 */
export interface SearchStreamAcceptedRaw {
  conversationId: number;
  questionMessageId: number;
  answerMessageId: number;
}

/** 서버가 질문을 받아 대화와 메시지를 만들었음을 알리는 이벤트. 스트림의 첫 이벤트 */
export class SearchStreamAcceptedDto {
  /** 탐색 대화 ID. 첫 질문이면 새로 만들어진 대화, 후속 질문·재시도면 경로의 대화 */
  conversationId: number;
  /** 저장된 질문 메시지 ID. 재시도면 다시 쓴 질문 */
  questionMessageId: number;
  /** 이어서 채워질 답변 메시지 ID. 뒤따르는 이벤트의 answerMessageId와 같음 */
  answerMessageId: number;

  constructor(raw: SearchStreamAcceptedRaw) {
    this.conversationId = raw.conversationId;
    this.questionMessageId = raw.questionMessageId;
    this.answerMessageId = raw.answerMessageId;
  }
}

/** `event: progress`의 data 모양 */
export interface SearchStreamProgressRaw {
  stage: SearchStreamStage;
}

/** 답변 준비 단계가 바뀌었음을 알리는 이벤트 */
export class SearchStreamProgressDto {
  /** 지금 단계. SEARCHING(문서 검색 중) 또는 GENERATING(답변 생성 중) */
  stage: SearchStreamStage;

  constructor(raw: SearchStreamProgressRaw) {
    this.stage = raw.stage;
  }
}

/** `event: delta`의 data 모양 */
export interface SearchStreamDeltaRaw {
  answerMessageId: number;
  text: string;
}

/** 답변 조각. 스트림 하나에서 여러 번 도착함 */
export class SearchStreamDeltaDto {
  /** 이 조각이 속한 답변 메시지 ID */
  answerMessageId: number;
  /** 답변의 일부 텍스트. 도착 순서대로 이어 붙이면 전체 답변이 됨 */
  text: string;

  constructor(raw: SearchStreamDeltaRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.text = raw.text;
  }
}

/** `event: evidence`의 data 모양 */
export interface SearchStreamEvidenceRaw {
  answerMessageId: number;
  items: SearchEvidenceRaw[];
}

/** 답변의 근거 문서 목록을 알리는 이벤트 */
export class SearchStreamEvidenceDto {
  /** 근거가 붙는 답변 메시지 ID */
  answerMessageId: number;
  /** 근거 문서. 최대 3개, 관련도 순(rank 오름차순) */
  items: SearchEvidenceDto[];

  constructor(raw: SearchStreamEvidenceRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.items = raw.items.map((item) => new SearchEvidenceDto(item));
  }
}

/** `event: notion_evidence`의 data 모양 */
export interface SearchStreamNotionEvidenceRaw {
  answerMessageId: number;
  items: SearchNotionEvidenceRaw[];
}

/** 답변에 실제로 쓴 노션 페이지 목록을 알리는 이벤트. 회의 문서 근거(evidence)와 따로 와요 */
export class SearchStreamNotionEvidenceDto {
  /** 근거가 붙는 답변 메시지 ID */
  answerMessageId: number;
  /** 답변에 쓴 노션 페이지. 개수 상한과 순서는 명세 미정이라 서버가 준 순서 그대로 */
  items: SearchNotionEvidenceDto[];

  constructor(raw: SearchStreamNotionEvidenceRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.items = raw.items.map((item) => new SearchNotionEvidenceDto(item));
  }
}

/** `event: notion_notice`의 data 모양 */
export interface SearchStreamNotionNoticeRaw {
  answerMessageId: number;
  reason: SearchNotionNoticeReason;
}

/** 노션을 읽지 못해 회의 문서만으로 답했음을 알리는 이벤트. 이때도 답변은 completed로 끝나요 */
export class SearchStreamNotionNoticeDto {
  /** 안내가 붙는 답변 메시지 ID */
  answerMessageId: number;
  /** 노션을 읽지 못한 사유 */
  reason: SearchNotionNoticeReason;

  constructor(raw: SearchStreamNotionNoticeRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.reason = raw.reason;
  }
}

/** `event: write_card`의 data 모양 */
export interface SearchStreamWriteCardRaw {
  answerMessageId: number;
  card: SearchWriteCardRaw;
}

/** 초안을 담은 쓰기 확인 카드를 알리는 이벤트. 한 답변에 최대 하나이고 처음에는 대기(PENDING)로 와요 */
export class SearchStreamWriteCardDto {
  /** 카드가 붙는 답변 메시지 ID */
  answerMessageId: number;
  /** 쓰기 확인 카드 */
  card: SearchWriteCardDto;

  constructor(raw: SearchStreamWriteCardRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.card = new SearchWriteCardDto(raw.card);
  }
}

/** `event: excluded_documents`의 data 모양 */
export interface SearchStreamExcludedDocumentsRaw {
  count: number;
}

/** 답변 근거에서 제외된 문서 수를 알리는 이벤트 */
export class SearchStreamExcludedDocumentsDto {
  /** 제외된 문서 수 */
  count: number;

  constructor(raw: SearchStreamExcludedDocumentsRaw) {
    this.count = raw.count;
  }
}

/** `event: completed`의 data 모양 */
export interface SearchStreamCompletedRaw {
  answerMessageId: number;
  status: "COMPLETED";
}

/** 답변이 끝나고 저장까지 마쳤음을 알리는 이벤트. 스트림의 마지막 이벤트 */
export class SearchStreamCompletedDto {
  /** 완료된 답변 메시지 ID */
  answerMessageId: number;
  /** 답변 메시지의 최종 상태. 항상 COMPLETED */
  status: "COMPLETED";

  constructor(raw: SearchStreamCompletedRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.status = raw.status;
  }
}

/** `event: failed`의 data 모양 */
export interface SearchStreamFailedRaw {
  answerMessageId: number;
  status: "FAILED";
  code: string;
}

/** accepted 뒤에 답변 생성이 실패했음을 알리는 이벤트. HTTP 상태가 아니라 이벤트로 옴 */
export class SearchStreamFailedDto {
  /** 실패한 답변 메시지 ID */
  answerMessageId: number;
  /** 답변 메시지의 최종 상태. 항상 FAILED */
  status: "FAILED";
  /** 실패 원인 오류 코드. 예: LLM_STREAM_FAILED */
  code: string;

  constructor(raw: SearchStreamFailedRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.status = raw.status;
    this.code = raw.code;
  }
}

/** `event: stopped`의 data 모양 */
export interface SearchStreamStoppedRaw {
  answerMessageId: number;
  status: "STOPPED";
}

/** 답변 생성이 중단됐음을 알리는 이벤트 */
export class SearchStreamStoppedDto {
  /** 중단된 답변 메시지 ID */
  answerMessageId: number;
  /** 답변 메시지의 최종 상태. 항상 STOPPED */
  status: "STOPPED";

  constructor(raw: SearchStreamStoppedRaw) {
    this.answerMessageId = raw.answerMessageId;
    this.status = raw.status;
  }
}

// GET /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/answers/{answerId}/events (SSE)
// 끊긴 답변을 다시 받는 스트림. 답변 메시지 ID를 `answerMessageId`가 아니라 `answerId`라고 불러요.
// 요청 함수가 첫 질문 스트림과 같은 이벤트 DTO로 바꿔 내므로, 앱의 다른 곳은 아래 Raw 모양을 보지 않아요

/** 재연결 스트림 `event: snapshot`의 data 모양 */
export interface SearchAnswerSnapshotRaw {
  answerId: number;
  content: string;
  status: SearchMessageStatus;
}

/** 재연결 스트림 `event: delta`의 data 모양 */
export interface SearchAnswerEventsDeltaRaw {
  answerId: number;
  text: string;
}

/** 재연결 스트림 `event: evidence`의 data 모양 */
export interface SearchAnswerEventsEvidenceRaw {
  answerId: number;
  items: SearchEvidenceRaw[];
}

/** 재연결 스트림 `event: notion_evidence`의 data 모양 */
export interface SearchAnswerEventsNotionEvidenceRaw {
  answerId: number;
  items: SearchNotionEvidenceRaw[];
}

/** 재연결 스트림 `event: notion_notice`의 data 모양 */
export interface SearchAnswerEventsNotionNoticeRaw {
  answerId: number;
  reason: SearchNotionNoticeReason;
}

/** 재연결 스트림 `event: write_card`의 data 모양. 카드는 지금 상태로 와요 */
export interface SearchAnswerEventsWriteCardRaw {
  answerId: number;
  card: SearchWriteCardRaw;
}

/** 재연결 스트림 `event: completed`의 data 모양 */
export interface SearchAnswerEventsCompletedRaw {
  answerId: number;
  status: "COMPLETED";
}

/** 재연결 스트림 `event: failed`의 data 모양 */
export interface SearchAnswerEventsFailedRaw {
  answerId: number;
  status: "FAILED";
  code: string;
}

/** 재연결 스트림 `event: stopped`의 data 모양 */
export interface SearchAnswerEventsStoppedRaw {
  answerId: number;
  status: "STOPPED";
}

/** 다시 연결했을 때 서버에 저장된 답변 본문을 알리는 이벤트. 재연결 스트림의 첫 이벤트 */
export class SearchStreamSnapshotDto {
  /** 답변 메시지 ID */
  answerMessageId: number;
  /** 지금까지 저장된 답변 본문 전체. 받던 본문을 이 값으로 바꾸고, 뒤따르는 delta를 이어 붙임 */
  content: string;
  /** 답변 메시지의 지금 상태. 이미 끝난 답변이면 뒤따르는 끝 이벤트와 같은 값 */
  status: SearchMessageStatus;

  constructor(raw: SearchAnswerSnapshotRaw) {
    this.answerMessageId = raw.answerId;
    this.content = raw.content;
    this.status = raw.status;
  }
}

/**
 * 탐색 스트림이 흘려보내는 이벤트 한 건.
 *
 * `event`로 갈라 `data`의 모양이 좁혀지도록 유니언으로 둡니다.
 * 명세에 없는 이벤트 이름은 요청 함수에서 버리므로 여기에 두지 않습니다.
 */
export type SearchStreamEvent =
  | { event: "accepted"; data: SearchStreamAcceptedDto }
  | { event: "snapshot"; data: SearchStreamSnapshotDto }
  | { event: "progress"; data: SearchStreamProgressDto }
  | { event: "delta"; data: SearchStreamDeltaDto }
  | { event: "evidence"; data: SearchStreamEvidenceDto }
  | { event: "notion_evidence"; data: SearchStreamNotionEvidenceDto }
  | { event: "notion_notice"; data: SearchStreamNotionNoticeDto }
  | { event: "write_card"; data: SearchStreamWriteCardDto }
  | { event: "excluded_documents"; data: SearchStreamExcludedDocumentsDto }
  | { event: "completed"; data: SearchStreamCompletedDto }
  | { event: "failed"; data: SearchStreamFailedDto }
  | { event: "stopped"; data: SearchStreamStoppedDto };

// GET /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/messages

/** 탐색 메시지 하나의 서버 응답 모양 */
export interface SearchMessageRaw {
  id: number;
  role: SearchMessageRole;
  sequence: number;
  content: string;
  status: SearchMessageStatus;
  createdAt: string;
  evidences: SearchEvidenceRaw[];
  /** 명세상 항상 오지만, 노션 연결을 배포하기 전 서버는 보내지 않아 없을 수 있어요 */
  notionEvidences?: SearchNotionEvidenceRaw[];
  /** 명세상 항상 오지만(해당 없으면 null), 노션 연결을 배포하기 전 서버는 보내지 않아 없을 수 있어요 */
  notionNotice?: SearchNotionNoticeReason | null;
  /** 명세상 항상 오지만(카드가 없으면 null), 노션 연결을 배포하기 전 서버는 보내지 않아 없을 수 있어요 */
  writeCard?: SearchWriteCardRaw | null;
}

/** 탐색 대화의 메시지 하나 */
export class SearchMessageDto {
  /** 메시지 ID. accepted 이벤트의 questionMessageId·answerMessageId와 같은 값 */
  id: number;
  /** 쓴 쪽. USER는 질문, ASSISTANT는 답변 */
  role: SearchMessageRole;
  /** 대화 안에서의 순서. 응답은 오름차순 */
  sequence: number;
  /** 질문 내용 또는 답변 본문. 생성 중인 답변은 저장된 데까지의 본문 */
  content: string;
  /** 메시지 상태. 질문은 RECEIVED */
  status: SearchMessageStatus;
  /** 만든 시각(ISO 8601) */
  createdAt: string;
  /** 답변의 근거 문서. 최대 3개, 관련도 순(rank 오름차순). 질문(USER)은 빈 배열 */
  evidences: SearchEvidenceDto[];
  /** 답변에 실제로 쓴 노션 페이지. 없거나 질문(USER)이면 빈 배열. 서버가 필드를 보내지 않으면 빈 배열 */
  notionEvidences: SearchNotionEvidenceDto[];
  /** 노션을 읽지 못해 회의 문서만으로 답한 사유. 해당 없으면 null. 서버가 필드를 보내지 않으면 null */
  notionNotice: SearchNotionNoticeReason | null;
  /** 이 답변에 붙은 쓰기 확인 카드의 지금 상태. 카드가 없으면 null. 서버가 필드를 보내지 않으면 null */
  writeCard: SearchWriteCardDto | null;

  constructor(raw: SearchMessageRaw) {
    this.id = raw.id;
    this.role = raw.role;
    this.sequence = raw.sequence;
    this.content = raw.content;
    this.status = raw.status;
    this.createdAt = raw.createdAt;
    this.evidences = raw.evidences.map(
      (evidence) => new SearchEvidenceDto(evidence),
    );
    this.notionEvidences = (raw.notionEvidences ?? []).map(
      (evidence) => new SearchNotionEvidenceDto(evidence),
    );
    this.notionNotice = raw.notionNotice ?? null;
    this.writeCard = raw.writeCard
      ? new SearchWriteCardDto(raw.writeCard)
      : null;
  }
}

/** 탐색 메시지 조회의 서버 응답 모양 */
export interface GetSearchMessagesResponseRaw {
  conversationId: number;
  items: SearchMessageRaw[];
  hasPrevious: boolean;
  previousCursor: string | null;
}

/** 탐색 대화의 최근 메시지 한 페이지 조회 응답 */
export class GetSearchMessagesResponseDto {
  /** 조회한 탐색 대화 ID */
  conversationId: number;
  /** 메시지 목록. sequence 오름차순(오래된 것부터) */
  items: SearchMessageDto[];
  /** 이 페이지보다 이전 메시지가 더 있는지 */
  hasPrevious: boolean;
  /** 이전 페이지를 받을 커서. 이전 메시지가 없으면 null */
  previousCursor: string | null;

  constructor(raw: GetSearchMessagesResponseRaw) {
    this.conversationId = raw.conversationId;
    this.items = raw.items.map((item) => new SearchMessageDto(item));
    this.hasPrevious = raw.hasPrevious;
    this.previousCursor = raw.previousCursor;
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}/execute

/**
 * 쓰기 확인 카드 실행 응답. 서버 응답은 카드 객체 그대로예요.
 * 다시 누를 수 있는 오류(PENDING과 retryReason)와 다시 실행할 수 없는 오류(FAILED와 failureReason)도 HTTP 오류가 아니라 이 200 응답으로 와요.
 * 대기가 아닌 카드에 온 실행 요청(두 번 누름, 여러 탭)도 오류가 아니고 그 카드의 지금 상태를 줘요
 */
export class PostSearchWriteCardExecuteResponseDto {
  /** 실행이 끝난 뒤의 카드. 다른 요청이 실행 중이면 RUNNING일 수 있어요 */
  card: SearchWriteCardDto;

  constructor(raw: SearchWriteCardRaw) {
    this.card = new SearchWriteCardDto(raw);
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards/{cardId}/cancel

/** 쓰기 확인 카드 취소 응답. 서버 응답은 카드 객체 그대로이고, 이미 취소된 카드도 오류 없이 지금 상태를 줘요 */
export class PostSearchWriteCardCancelResponseDto {
  /** 취소한 뒤의 카드. status는 CANCELED */
  card: SearchWriteCardDto;

  constructor(raw: SearchWriteCardRaw) {
    this.card = new SearchWriteCardDto(raw);
  }
}

// GET /api/v1/workspaces/{workspaceId}/search/notion-connection

/** 노션 연결 상태. NOT_CONNECTED 연결 없음, CONNECTED 연결됨, EXPIRED 만료(다시 연결 필요) */
export type SearchNotionConnectionStatus =
  | "NOT_CONNECTED"
  | "CONNECTED"
  | "EXPIRED";

/** 노션을 연결한 사람의 서버 응답 모양 */
export interface SearchNotionConnectorRaw {
  memberId: number;
  nickname: string;
}

/** 노션을 연결한 워크스페이스 멤버 */
export class SearchNotionConnectorDto {
  /** 연결한 사람의 멤버 ID */
  memberId: number;
  /** 연결한 사람의 닉네임 */
  nickname: string;

  constructor(raw: SearchNotionConnectorRaw) {
    this.memberId = raw.memberId;
    this.nickname = raw.nickname;
  }
}

/** 노션 연결 조회의 서버 응답 모양 */
export interface GetSearchNotionConnectionResponseRaw {
  status: SearchNotionConnectionStatus;
  notionWorkspaceName: string | null;
  notionWorkspaceIcon: string | null;
  connectedBy: SearchNotionConnectorRaw | null;
  canDisconnect: boolean;
}

/** 워크스페이스의 노션 연결 조회 응답. 연결이 없어도 200이고 status가 NOT_CONNECTED예요 */
export class GetSearchNotionConnectionResponseDto {
  /** 연결 상태. CONNECTED면 [연결]을 보여 주지 않고, EXPIRED면 [다시 연결]을 보여 줘요 */
  status: SearchNotionConnectionStatus;
  /** 연결한 노션 워크스페이스 이름. NOT_CONNECTED면 null */
  notionWorkspaceName: string | null;
  /** 연결한 노션 워크스페이스 아이콘(이모지 한 글자 또는 이미지 절대 URL). 아이콘이 없거나 NOT_CONNECTED면 null */
  notionWorkspaceIcon: string | null;
  /** 연결한 사람. NOT_CONNECTED면 null. 만료 뒤 다시 연결하면 다시 연결한 멤버로 바뀌어요 */
  connectedBy: SearchNotionConnectorDto | null;
  /** 지금 멤버가 끊을 수 있는지. 연결한 사람이거나 워크스페이스 OWNER면 true, NOT_CONNECTED면 false */
  canDisconnect: boolean;

  constructor(raw: GetSearchNotionConnectionResponseRaw) {
    this.status = raw.status;
    this.notionWorkspaceName = raw.notionWorkspaceName;
    this.notionWorkspaceIcon = raw.notionWorkspaceIcon;
    this.connectedBy = raw.connectedBy
      ? new SearchNotionConnectorDto(raw.connectedBy)
      : null;
    this.canDisconnect = raw.canDisconnect;
  }
}

// POST /api/v1/workspaces/{workspaceId}/search/notion-connection/authorization

/** 노션 연결 시작 시 앱이 넘기는 값 */
export interface PostSearchNotionConnectionAuthorizationRequestInput {
  agreed: true;
}

/**
 * 노션 연결 시작 요청 본문. 연결이 없을 때와 만료됐을 때만 부를 수 있고, 연결됨이면 409 NOTION_CONNECTION_ALREADY_CONNECTED예요
 */
export class PostSearchNotionConnectionAuthorizationRequestDto {
  /** 「내가 노션에서 볼 수 있는 내용 전부가 멤버 모두에게 보일 수 있다」는 안내에 동의했는지. 서버는 true만 받아요 */
  agreed: true;

  constructor({ agreed }: PostSearchNotionConnectionAuthorizationRequestInput) {
    this.agreed = agreed;
  }
}

/** 노션 연결 시작의 서버 응답 모양 */
export interface PostSearchNotionConnectionAuthorizationResponseRaw {
  authorizationUrl: string;
}

/** 노션 연결 시작 응답 */
export class PostSearchNotionConnectionAuthorizationResponseDto {
  /** 노션 동의 화면 주소(절대 URL). 화면은 같은 탭에서 이 주소로 이동해요 */
  authorizationUrl: string;

  constructor(raw: PostSearchNotionConnectionAuthorizationResponseRaw) {
    this.authorizationUrl = raw.authorizationUrl;
  }
}

// DELETE /api/v1/workspaces/{workspaceId}/search/notion-connection
// 응답 본문이 없어(204) DTO가 없어요
