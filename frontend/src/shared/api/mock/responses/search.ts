import type {
  SearchAnswerEventsStream,
  SearchAnswerRetryStream,
  SearchAnswerStream,
  SearchConversations,
  SearchMessages,
  SearchQuestionAnswerStream,
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
    },
    {
      id: 3,
      role: "USER",
      sequence: 3,
      content: "알림 설정은 어디까지 정했어?",
      status: "RECEIVED",
      createdAt: "2026-10-05T01:01:00Z",
      evidences: [],
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
    },
    {
      id: 5,
      role: "USER",
      sequence: 5,
      content: "다음 스프린트 회고 일정 정해졌어?",
      status: "RECEIVED",
      createdAt: "2026-10-05T01:02:00Z",
      evidences: [],
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
