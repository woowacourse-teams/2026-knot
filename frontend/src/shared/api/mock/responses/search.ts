import type {
  SearchAnswerEventsStream,
  SearchAnswerRetryStream,
  SearchAnswerStream,
  SearchConversations,
  SearchMessages,
  SearchNotionConnection,
  SearchNotionConnectionAuthorization,
  SearchNotionEvidenceItem,
  SearchQuestionAnswerStream,
  SearchWriteCard,
} from "@api/mock/types/search";

/**
 * 답변이 조각으로 도착하는 모습을 화면에서 확인하려고 일부러 잘게 나눠 둔 첫 질문 응답이에요.
 * 실제 LLM이 붙으면 그때의 조각 모양으로 맞춰요.
 */
export const searchAnswerStreamResponse = {
  conversationId: 100,
  questionMessageId: 1001,
  answerMessageId: 1002,
  stages: ["SEARCHING", "GENERATING"],
  deltas: [
    "DB는 ",
    "지난주 ",
    "기술 선정 회의에서 ",
    "PostgreSQL",
    "로 정해졌어요. ",
    "초기 스키마는 ",
    "ERD 문서에 ",
    "정리돼 있어요.",
  ],
  evidences: [
    {
      documentId: 11,
      title: "DB 기술 선정 회의록",
      topic: "DB 기술 선정",
      rank: 1,
    },
    {
      documentId: 12,
      title: "초기 ERD",
      topic: "데이터 모델",
      rank: 2,
    },
    {
      documentId: 13,
      title: "백엔드 기술 스택 정리",
      topic: "기술 스택",
      rank: 3,
    },
  ],
} satisfies SearchAnswerStream;

/** 문서 근거 없이 인사에 응답하는 일반 대화의 정상 응답이에요. */
export const searchGeneralAnswerStreamResponse = {
  ...searchAnswerStreamResponse,
  deltas: ["안녕하세요! 무엇을 도와드릴까요?"],
  evidences: [],
} satisfies SearchAnswerStream;

/**
 * 첫 질문에 이어 보내는 후속 질문 응답이에요.
 * 같은 대화에 메시지가 쌓이므로 첫 질문 응답과 메시지 ID가 겹치지 않게 둬요.
 */
export const searchQuestionAnswerStreamResponse = {
  questionMessageId: 1003,
  answerMessageId: 1004,
  stages: ["SEARCHING", "GENERATING"],
  deltas: ["초기 스키마는 ", "백엔드 팀이 ", "ERD 문서로 ", "정리했어요."],
  evidences: [
    {
      documentId: 12,
      title: "초기 ERD",
      topic: "데이터 모델",
      rank: 1,
    },
  ],
} satisfies SearchQuestionAnswerStream;

/**
 * 실패한 답변을 다시 시도했을 때의 응답이에요.
 * 서버는 실패했던 답변 메시지를 비우고 다시 채우므로, accepted의 `questionId`·`answerMessageId`는 핸들러가 요청 경로의 질문 ID로 채워요.
 * 첫 질문과 달리 accepted에 대화 ID가 없고 `status: "STREAMING"`이 함께 와요.
 */
export const searchAnswerRetryStreamResponse = {
  stages: ["SEARCHING", "GENERATING"],
  deltas: ["다시 찾아보니 ", "탈퇴한 사용자 게시글은 ", "익명으로 남겨요."],
  evidences: [
    {
      documentId: 101,
      title: "회원 탈퇴 정책",
      topic: "회원 관리",
      rank: 1,
    },
  ],
} satisfies SearchAnswerRetryStream;

/**
 * 끊긴 답변에 다시 연결했을 때의 응답이에요.
 * 서버가 그때까지 저장한 본문을 snapshot으로 먼저 보내고 나머지 조각을 이어 보내요. 답변 ID는 핸들러가 요청 경로에서 채워요.
 */
export const searchAnswerEventsStreamResponse = {
  content: "DB는 지난주 ",
  deltas: ["기술 선정 회의에서 ", "PostgreSQL로 ", "정해졌어요."],
  evidences: [
    {
      documentId: 11,
      title: "DB 기술 선정 회의록",
      topic: "DB 기술 선정",
      rank: 1,
    },
  ],
} satisfies SearchAnswerEventsStream;

/**
 * 이미 질문이 오간 탐색 대화의 메시지 조회 응답이에요.
 *
 * 근거가 2개인 답변, 3개인 답변, 근거가 없는 답변을 한 턴씩 담아요.
 * 근거는 서버 계약대로 `rank` 오름차순(관련도 높은 순)이에요. 대화 ID는 핸들러가 요청 경로에서 채워요.
 */
export const searchMessagesResponse = {
  conversationId: 10,
  items: [
    {
      id: 1,
      role: "USER",
      sequence: 1,
      content: "탈퇴한 사용자 게시글은 어떻게 처리해?",
      status: "RECEIVED",
      createdAt: "2026-10-05T01:00:00Z",
      evidences: [],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
    {
      id: 2,
      role: "ASSISTANT",
      sequence: 2,
      content:
        "댓글이 달린 게시글은 유지하고, 작성자를 ‘탈퇴한 사용자’로 표시해요. 2026년 9월 15일 회의에서 정리한 내용이에요.\n첨부파일 처리는 아직 정해지지 않았어요.",
      status: "COMPLETED",
      createdAt: "2026-10-05T01:00:05Z",
      evidences: [
        {
          documentId: 101,
          title: "회원 탈퇴 정책",
          topic: "회원 관리",
          rank: 1,
        },
        {
          documentId: 102,
          title: "회원 탈퇴 정책 논의",
          topic: "회의록",
          rank: 2,
        },
      ],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
    {
      id: 3,
      role: "USER",
      sequence: 3,
      content: "알림 설정은 어디까지 정했어?",
      status: "RECEIVED",
      createdAt: "2026-10-05T01:01:00Z",
      evidences: [],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
    {
      id: 4,
      role: "ASSISTANT",
      sequence: 4,
      content:
        "푸시 알림은 댓글과 멘션에만 보내기로 했어요.\n방해 금지 시간은 밤 10시부터 아침 8시까지로 정했고, 사용자가 바꿀 수 있어요.",
      status: "COMPLETED",
      createdAt: "2026-10-05T01:01:05Z",
      evidences: [
        {
          documentId: 201,
          title: "푸시 알림 정책",
          topic: "알림",
          rank: 1,
        },
        {
          documentId: 202,
          title: "알림 설정 화면 기획",
          topic: "기획",
          rank: 2,
        },
        {
          documentId: 203,
          title: "9월 4주차 정기 회의",
          topic: "회의록",
          rank: 3,
        },
      ],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
    {
      id: 5,
      role: "USER",
      sequence: 5,
      content: "다음 스프린트 회고 일정 정해졌어?",
      status: "RECEIVED",
      createdAt: "2026-10-05T01:02:00Z",
      evidences: [],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
    {
      id: 6,
      role: "ASSISTANT",
      sequence: 6,
      content:
        "모아 둔 기록에서 다음 스프린트 회고 일정을 찾지 못했어요.\n일정이 정해지면 회의록에 남겨 주세요.",
      status: "COMPLETED",
      createdAt: "2026-10-05T01:02:05Z",
      evidences: [],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
    },
  ],
  hasPrevious: false,
  previousCursor: null,
} satisfies SearchMessages;

const SECOND = 1000;
const HOUR = 60 * 60 * SECOND;

// 고정 시각은 언젠가 전부 "이전"으로 묶이므로 지금 기준으로 만들어요
const fromNow = (elapsed: number) =>
  new Date(Date.now() - elapsed).toISOString();

/**
 * 대화 목록 조회 응답이에요. 오늘 대화와 어제 대화를 하나씩 담아 기간 묶음이 둘로 나뉘어요.
 * 첫 번째 대화 ID는 첫 질문 mock의 대화 ID와 같아, 개발 서버에서 목록을 누르면 메시지 조회 mock으로 이어져요.
 */
export const searchConversationsResponse = {
  items: [
    {
      id: 100,
      title: "DB 기술 선정 관련 문서 있어?",
      lastMessagePreview: "DB는 지난주 기술 선정 회의에서 PostgreSQL로 정해졌어요.",
      createdAt: fromNow(2 * HOUR),
      updatedAt: fromNow(30 * SECOND),
    },
    {
      id: 101,
      title: "온보딩 문서는 어디에 있어?",
      lastMessagePreview: "온보딩 문서는 팀 위키의 시작하기 페이지에 있어요.",
      createdAt: fromNow(26 * HOUR),
      updatedAt: fromNow(25 * HOUR),
    },
  ],
  nextCursor: null,
} satisfies SearchConversations;

/**
 * 노션 페이지 만들기 확인 카드의 대기 상태예요. 회의록 DB 아래에 만들어 DB 칸이 함께 와요.
 * 실행 핸들러는 이 카드를 실행됨(DONE)으로, 취소 핸들러는 취소됨(CANCELED)으로 바꿔 돌려줘요
 */
export const searchWriteCardResponse = {
  id: 31,
  type: "NOTION_PAGE_CREATE",
  status: "PENDING",
  location: { name: "회의록", path: "팀 위키 / 회의록", kind: "DATABASE" },
  title: "11월 13일 주간 회의",
  body: "## 결정\n- 출시일은 12월 3일로 정했어요.\n- QA는 11월 마지막 주에 해요.\n\n## 할 일\n- 도넛: 배포 체크리스트 정리\n- 유월: QA 일정 공유",
  databaseFields: [
    { name: "이름", value: "11월 13일 주간 회의" },
    { name: "날짜", value: "2026-11-13" },
  ],
  sourceDocument: { documentId: 204, title: "주간 회의" },
  executable: true,
  createdPageUrl: null,
  retryReason: null,
  failureReason: null,
  existingPages: [],
} satisfies SearchWriteCard;

/** 실행이 끝나 노션 페이지를 만든 뒤의 주소예요 */
export const searchWriteCardCreatedPageUrl =
  "https://www.notion.so/knot-team/11-13-31";

/** 노션이 연결된 워크스페이스의 노션 연결 조회 응답이에요. 연결한 사람이 보는 경우라 끊을 수 있어요 */
export const searchNotionConnectionResponse = {
  status: "CONNECTED",
  notionWorkspaceName: "Knot 팀",
  notionWorkspaceIcon: "🪢",
  connectedBy: { memberId: 1, nickname: "도넛" },
  canDisconnect: true,
} satisfies SearchNotionConnection;

/** 노션 연결 시작 응답이에요. 실제 서버는 노션 동의 화면 주소를 줘요 */
export const searchNotionConnectionAuthorizationResponse = {
  authorizationUrl:
    "https://api.notion.com/v1/oauth/authorize?client_id=knot&response_type=code&owner=user",
} satisfies SearchNotionConnectionAuthorization;

/**
 * 답에 쓴 노션 페이지 다섯 개예요(SEARCH-R37). 앞에서부터 잘라 1건, 3건, 줄이 넘치는 5건을 만들어요
 */
export const searchNotionEvidencesResponse = [
  { title: "출시 계획", url: "https://www.notion.so/knot-team/launch-plan" },
  { title: "2026 로드맵", url: "https://www.notion.so/knot-team/roadmap-2026" },
  { title: "마케팅 일정", url: "https://www.notion.so/knot-team/marketing-schedule" },
  {
    title: "12월 배포 체크리스트",
    url: "https://www.notion.so/knot-team/december-release-checklist",
  },
  { title: "QA 일정", url: "https://www.notion.so/knot-team/qa-schedule" },
] satisfies SearchNotionEvidenceItem[];

/**
 * 회의 문서와 노션을 함께 읽고 답한 첫 질문 응답이에요(SEARCH-R35, R37).
 * 회의 문서 근거 2개와 노션 근거 1개가 함께 와요
 */
export const searchNotionAnswerStreamResponse = {
  ...searchAnswerStreamResponse,
  deltas: [
    "노션 출시 계획에는 12월 1일로 적혀 있고, ",
    "11월 6일 회의에서도 12월 1일로 정했어요. 두 날짜가 같아요.\n",
    "12월 1일은 11월 3일 회의에서 처음 나온 날짜예요.",
  ],
  evidences: searchAnswerStreamResponse.evidences.slice(0, 2),
  notionEvidences: searchNotionEvidencesResponse.slice(0, 1),
} satisfies SearchAnswerStream;

/** 노션 연결이 없어 회의 문서만으로 답한 첫 질문 응답이에요(SEARCH-R39) */
export const searchNotionNoticeAnswerStreamResponse = {
  ...searchAnswerStreamResponse,
  notionNotice: "NOT_CONNECTED",
} satisfies SearchAnswerStream;

/** 회의 문서를 노션 페이지로 만들어 달라는 요청에 답글과 대기 확인 카드가 온 첫 질문 응답이에요(SEARCH-R44) */
export const searchWriteCardAnswerStreamResponse = {
  ...searchAnswerStreamResponse,
  deltas: ["주간 회의 회의록을 ", "노션 회의록 DB에 만들 준비를 했어요."],
  evidences: [],
  writeCard: searchWriteCardResponse,
} satisfies SearchAnswerStream;

/** 일반 페이지 아래에 만드는 대기 카드예요. DB 칸이 없어요(SEARCH-R48, R49) */
export const searchWriteCardPageResponse = {
  ...searchWriteCardResponse,
  id: 32,
  location: { name: "기획 문서", path: "팀 위키 / 기획 문서", kind: "PAGE" },
  title: "11월 6일 회의 결정 정리",
  body: "알림 채널 순서를 정했어요.\n실패 알림은 따로 모아 보여 줘요.\n첨부파일 처리는 다음 회의에서 정해요.",
  databaseFields: null,
  sourceDocument: { documentId: 205, title: "11/6 기획 회의록" },
} satisfies SearchWriteCard;

/** 내용이 길어 접어 보여 주는 대기 카드예요(SEARCH-R48) */
export const searchWriteCardLongBodyResponse = {
  ...searchWriteCardPageResponse,
  id: 33,
  body: [
    "## 결정",
    "- 알림 채널 순서를 정했어요. 앱 푸시, 이메일, 슬랙 순서예요.",
    "- 실패 알림은 따로 모아 보여 줘요.",
    "- 같은 알림이 5분 안에 다시 오면 한 번만 보내요.",
    "- 밤 10시부터 아침 8시까지는 앱 푸시를 보내지 않고 아침에 모아 보내요.",
    "- 알림 문구는 기획에서 정리한 표를 그대로 써요.",
    "",
    "## 할 일",
    "- 디자인: 알림 설정 화면 시안 만들기 (11월 12일까지)",
    "- 백엔드: 실패 알림 저장 API 설계 (11월 13일까지)",
    "- 프론트엔드: 알림 목록에 실패 알림 묶음 추가 (11월 14일까지)",
    "- 기획: 알림 문구 표 최종 확인",
    "",
    "## 다음 회의에서 정할 것",
    "- 첨부파일 처리는 다음 회의에서 정해요.",
    "- 알림을 30일 보관할지 90일 보관할지 정해요.",
    "- 알림 끄기를 워크스페이스 단위로 둘지 사람 단위로 둘지 정해요.",
    "",
    "## 참고",
    "- 지난 회의에서 나온 「알림이 너무 많다」는 의견을 반영했어요.",
    "- 이메일은 하루 1,000건 이하로 보낼 것으로 예상해요.",
  ].join("\n"),
} satisfies SearchWriteCard;
