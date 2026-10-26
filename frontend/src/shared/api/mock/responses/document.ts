import { meResponse } from "@api/mock/responses/auth";
import type {
  DocumentConfirmationsResponse,
  DocumentDetailResponse,
  DocumentListItem,
  DocumentRecording,
  DocumentRecordingsResponse,
  DocumentsResponse,
  DocumentTranscriptResponse,
  DocumentTranscriptSegment,
} from "@api/mock/types/document";

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

// 지난 시각을 고정값으로 두면 언젠가 전부 오래된 날짜가 되므로 지금을 기준으로 만들어요
const fromNow = (elapsed: number) =>
  new Date(Date.now() - elapsed).toISOString();

// 같은 녹음(42)에서 나온 두 문서예요. 본문은 문서 생성기 형식(09-29)의 두 예시(결정 있음 · 결정 없음)를 그대로 써요.
// id만 탐색 mock의 근거 문서 id(101 · 102)와 맞춰, 찾은 기록 카드를 누르면 404 대신 문서가 열려요.
// 101은 제목 · 주제도 카드와 같아요. 102는 결정 없음 예시를 써야 해서 맞추지 않았어요.
// 그래서 「회원 탈퇴 정책 논의」 카드를 누르면 「홈 개편 논의」 문서가 열려요.
export const documentDetailsResponse = [
  {
    id: 101,
    recordingSessionId: 42,
    topic: "회원 관리",
    title: "회원 탈퇴 정책",
    summary: "탈퇴한 사용자의 게시글을 남기는 기준을 정했어요.",
    content: [
      "## 결정",
      "탈퇴한 사용자의 게시글은 유지하고, 작성자를 '탈퇴한 사용자'로 표시해요.",
      "",
      "## 적용 범위",
      "댓글이 달린 게시글만 유지하고, 댓글 없는 글은 함께 삭제해요.",
      "",
      "## 이유",
      "댓글이 달린 글이 사라지면 대화 흐름이 끊겨요.",
      "",
      "## 미결정 항목",
      "- 첨부파일을 게시글과 함께 유지할지 — 아직 정해지지 않음",
      "- 탈퇴 후 복구 기간 — 다음 논의에서 확인",
    ].join("\n"),
    status: "DRAFT",
    createdAt: fromNow(2 * DAY),
    archivedAt: null,
    recordingDurationSeconds: 1920,
    sourceTranscriptId: 81,
    myConfirmationState: "PENDING",
    confirmationSummary: {
      confirmedCount: 2,
      pendingCount: 2,
      excludedCount: 0,
    },
  },
  {
    id: 102,
    recordingSessionId: 42,
    topic: "주간 회의",
    title: "홈 개편 논의",
    summary: null,
    content: [
      "## 결정",
      "이번 회의에서 정해진 내용은 없어요.",
      "",
      "## 논의한 내용",
      "- 개편 범위 — 홈 전체 개편과 일부 개선, 두 안을 비교했어요",
      "- 출시 시점 — 다음 분기 안에 가능한지 이야기했어요",
      "",
      "## 미결정 항목",
      "- 개편 범위 — 디자인 시안을 보고 정하기로 함",
      "- 출시 일정 — 개발 공수를 확인한 뒤 정하기로 함",
    ].join("\n"),
    status: "DRAFT",
    createdAt: fromNow(2 * DAY),
    archivedAt: null,
    recordingDurationSeconds: 1920,
    sourceTranscriptId: 81,
    myConfirmationState: "CONFIRMED",
    confirmationSummary: {
      confirmedCount: 3,
      pendingCount: 1,
      excludedCount: 0,
    },
  },
] satisfies DocumentDetailResponse[];

// 목록 항목은 상세에서 본문 · 보관 시각 · 원문 ID가 빠진 모양이에요.
// 같은 문서의 값이 목록과 상세에서 어긋나지 않게 상세에서 만들어요
const toListItem = ({
  id,
  recordingSessionId,
  topic,
  title,
  summary,
  status,
  createdAt,
  recordingDurationSeconds,
  myConfirmationState,
  confirmationSummary,
}: DocumentDetailResponse) =>
  ({
    id,
    recordingSessionId,
    topic,
    title,
    summary,
    status,
    createdAt,
    recordingDurationSeconds,
    myConfirmationState,
    confirmationSummary,
  }) satisfies DocumentListItem;

const [memberWithdrawalDocument, homeRenewalDocument] = documentDetailsResponse;

// 워크스페이스의 문서 전체예요. 핸들러가 cursor · size로 잘라서 돌려줘요.
// 문서는 서버 정렬(생성 시각 내림차순, 같으면 ID 내림차순)을, 주제는 서버가 주는 이름순을 따라요.
// 상세 mock이 있는 문서는 101 · 102뿐이라, 나머지 문서는 눌러도 문서 없음(404)으로 답해요
export const documentsResponse = {
  topics: [
    { topic: "사용자 인터뷰", documentCount: 3 },
    { topic: "주간 회의", documentCount: 2 },
    { topic: "회원 관리", documentCount: 2 },
  ],
  items: [
    {
      id: 104,
      recordingSessionId: 44,
      topic: "주간 회의",
      title: "스프린트 회고",
      summary: "이번 스프린트에서 잘된 점과 다음에 바꿀 점을 정리했어요.",
      status: "DRAFT",
      createdAt: fromNow(3 * HOUR),
      recordingDurationSeconds: 2710,
      myConfirmationState: "PENDING",
      confirmationSummary: {
        confirmedCount: 0,
        pendingCount: 4,
        excludedCount: 0,
      },
    },
    {
      id: 103,
      recordingSessionId: 43,
      topic: "사용자 인터뷰",
      title: "온보딩 인터뷰 정리",
      summary: "처음 쓰는 사람이 어디에서 막히는지 세 명에게 물었어요.",
      status: "DRAFT",
      createdAt: fromNow(DAY),
      recordingDurationSeconds: 3540,
      myConfirmationState: "NOT_REQUIRED",
      confirmationSummary: {
        confirmedCount: 1,
        pendingCount: 2,
        excludedCount: 0,
      },
    },
    toListItem(homeRenewalDocument),
    toListItem(memberWithdrawalDocument),
    {
      id: 100,
      recordingSessionId: 41,
      topic: "사용자 인터뷰",
      title: "검색 기능 사용성 인터뷰",
      summary: "찾은 기록을 다시 여는 과정이 번거롭다는 의견이 많았어요.",
      status: "DRAFT",
      createdAt: fromNow(6 * DAY),
      recordingDurationSeconds: 1265,
      myConfirmationState: "CONFIRMED",
      confirmationSummary: {
        confirmedCount: 3,
        pendingCount: 1,
        excludedCount: 0,
      },
    },
    {
      id: 99,
      recordingSessionId: 40,
      topic: "회원 관리",
      title: "휴면 계정 전환 기준",
      summary: "1년 동안 로그인하지 않은 계정을 휴면으로 바꾸기로 했어요.",
      status: "DRAFT",
      createdAt: fromNow(13 * DAY),
      recordingDurationSeconds: 845,
      myConfirmationState: "CONFIRMED",
      confirmationSummary: {
        confirmedCount: 2,
        pendingCount: 1,
        excludedCount: 1,
      },
    },
    {
      id: 98,
      recordingSessionId: 39,
      topic: "사용자 인터뷰",
      title: "첫 사용자 인터뷰",
      summary: null,
      status: "ARCHIVED",
      createdAt: fromNow(40 * DAY),
      recordingDurationSeconds: 5400,
      myConfirmationState: "CONFIRMED",
      confirmationSummary: {
        confirmedCount: 4,
        pendingCount: 0,
        excludedCount: 0,
      },
    },
  ],
  nextCursor: null,
} satisfies DocumentsResponse;

// 문서 목록의 문서가 나온 녹음들이에요. 표시 제목은 서버가 만들어 주는 값이라, AI 제목이 없는 녹음(40)은 대체 이름 그대로예요.
// 시작 시각은 그 녹음의 첫 문서가 만들어진 시각보다 녹음 길이만큼 앞이에요
const documentRecordingTitles = [
  { recordingId: 44, title: "스프린트 회고 회의" },
  { recordingId: 43, title: "온보딩 사용자 인터뷰" },
  { recordingId: 42, title: "회원 탈퇴 정책 논의" },
  { recordingId: 41, title: "검색 기능 사용성 인터뷰" },
  { recordingId: 40, title: "유월 님의 녹음" },
  { recordingId: 39, title: "첫 사용자 인터뷰" },
];

// 녹음마다 그 녹음에서 나온 문서를 묶어요. 묶음 안 문서는 서버 정렬(생성 시각 오름차순, 같으면 ID 오름차순)이에요.
// 같은 문서가 문서 목록과 녹음별 목록 양쪽에서 같은 제목 · 요약으로 보이게 문서 목록의 항목에서 만들어요
const toDocumentRecording = ({
  recordingId,
  title,
}: (typeof documentRecordingTitles)[number]) => {
  const documents = documentsResponse.items
    .filter((item) => item.recordingSessionId === recordingId)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id - b.id);
  const [firstDocument] = documents;
  const recordingDurationSeconds = firstDocument.recordingDurationSeconds;

  return {
    recordingId,
    title,
    startedAt: new Date(
      new Date(firstDocument.createdAt).getTime() -
        recordingDurationSeconds * 1000,
    ).toISOString(),
    recordingDurationSeconds,
    documentCount: documents.length,
    documents: documents.map(({ id, title: documentTitle, summary }) => ({
      id,
      title: documentTitle,
      summary,
    })),
  } satisfies DocumentRecording;
};

// 문서 탭 「녹음별」 탭의 녹음 묶음 전체예요. 핸들러가 녹음 20개씩 잘라서 돌려줘요.
// 순서는 서버 정렬(녹음 시작 시각 내림차순, 같으면 녹음 ID 내림차순)이고, 문서 목록의 녹음 순서와 같아요
export const documentRecordingsResponse = {
  items: documentRecordingTitles.map(toDocumentRecording),
  nextCursor: null,
} satisfies DocumentRecordingsResponse;

// 로그인한 사람(auth mock의 me)을 확인 대상에 넣어, 문서 상세의 myConfirmationState와 맞춰요
const me = {
  memberId: meResponse.memberId,
  nickname: meResponse.nickname,
  profileImageUrl: meResponse.profileImageUrl,
};

// 문서 상세의 confirmationSummary · myConfirmationState와 같은 내용이에요.
// 순서는 서버 정렬(CONFIRMED → PENDING)을 따라요.
export const documentConfirmationsResponse = [
  {
    documentId: 101,
    confirmedCount: 2,
    pendingCount: 2,
    excludedCount: 0,
    confirmedByMe: false,
    items: [
      {
        memberId: 2,
        nickname: "도넛",
        profileImageUrl: null,
        confirmedAt: fromNow(DAY),
        state: "CONFIRMED",
      },
      {
        memberId: 3,
        nickname: "흑곰",
        profileImageUrl: null,
        confirmedAt: fromNow(5 * HOUR),
        state: "CONFIRMED",
      },
      { ...me, confirmedAt: null, state: "PENDING" },
      {
        memberId: 4,
        nickname: "유월",
        profileImageUrl: null,
        confirmedAt: null,
        state: "PENDING",
      },
    ],
    nextCursor: null,
  },
  {
    documentId: 102,
    confirmedCount: 3,
    pendingCount: 1,
    excludedCount: 0,
    confirmedByMe: true,
    items: [
      {
        memberId: 2,
        nickname: "도넛",
        profileImageUrl: null,
        confirmedAt: fromNow(DAY),
        state: "CONFIRMED",
      },
      {
        memberId: 3,
        nickname: "흑곰",
        profileImageUrl: null,
        confirmedAt: fromNow(5 * HOUR),
        state: "CONFIRMED",
      },
      { ...me, confirmedAt: fromNow(HOUR), state: "CONFIRMED" },
      {
        memberId: 4,
        nickname: "유월",
        profileImageUrl: null,
        confirmedAt: null,
        state: "PENDING",
      },
    ],
    nextCursor: null,
  },
] satisfies DocumentConfirmationsResponse[];

const SECOND = 1000;
const MINUTE = 60 * SECOND;

// 녹음 42의 발화예요. 서버처럼 시작 시각 오름차순으로 두었어요.
// 화자를 알 수 없는 구간(speakerNumber null)과 끝난 시각을 모르는 구간(endMillis null)을 하나씩 넣었어요
const transcriptSegments = [
  {
    startMillis: 2 * MINUTE + 40 * SECOND,
    endMillis: 2 * MINUTE + 46 * SECOND,
    speakerNumber: 1,
    text: "지난주에 이야기한 탈퇴 정책부터 정리할게요.",
  },
  {
    startMillis: 4 * MINUTE + 12 * SECOND,
    endMillis: 4 * MINUTE + 15 * SECOND,
    speakerNumber: 1,
    text: "탈퇴하면 게시글을 삭제할까요?",
  },
  {
    startMillis: 5 * MINUTE + 30 * SECOND,
    endMillis: 5 * MINUTE + 34 * SECOND,
    speakerNumber: 2,
    text: "댓글이 달린 글은 남겨야 하지 않나요?",
  },
  {
    startMillis: 6 * MINUTE + 2 * SECOND,
    endMillis: 6 * MINUTE + 8 * SECOND,
    speakerNumber: 3,
    text: "글은 남기고, 작성자는 '탈퇴한 사용자'로 표시하죠.",
  },
  {
    startMillis: 7 * MINUTE + 45 * SECOND,
    endMillis: 7 * MINUTE + 50 * SECOND,
    speakerNumber: 2,
    text: "첨부파일도 같이 남길지는 다음에 정해요.",
  },
  {
    startMillis: 8 * MINUTE + 20 * SECOND,
    endMillis: null,
    speakerNumber: null,
    text: "네, 좋아요.",
  },
  {
    startMillis: 9 * MINUTE + 10 * SECOND,
    endMillis: 9 * MINUTE + 15 * SECOND,
    speakerNumber: 1,
    text: "그럼 댓글 없는 글은 함께 삭제하는 걸로 해요.",
  },
  {
    startMillis: 10 * MINUTE + 24 * SECOND,
    endMillis: 10 * MINUTE + 28 * SECOND,
    speakerNumber: 3,
    text: "표시 문구는 '탈퇴한 사용자'로 통일해요.",
  },
  {
    startMillis: 11 * MINUTE + 30 * SECOND,
    endMillis: 11 * MINUTE + 35 * SECOND,
    speakerNumber: 2,
    text: "첨부파일은 다음 논의에서 확인하기로 해요.",
  },
  {
    startMillis: 12 * MINUTE + 48 * SECOND,
    endMillis: 12 * MINUTE + 52 * SECOND,
    speakerNumber: 1,
    text: "오늘 정한 내용은 문서로 정리해 둘게요.",
  },
  {
    startMillis: 15 * MINUTE + 2 * SECOND,
    endMillis: 15 * MINUTE + 6 * SECOND,
    speakerNumber: 2,
    text: "이어서 홈 화면 개편을 이야기할게요.",
  },
  {
    startMillis: 21 * MINUTE + 37 * SECOND,
    endMillis: 21 * MINUTE + 43 * SECOND,
    speakerNumber: 3,
    text: "홈은 아직 정하지 못했으니 다음 회의에서 다시 봐요.",
  },
] satisfies DocumentTranscriptSegment[];

// 문서 상세 mock(101 · 102)이 가리키는 원문(sourceTranscriptId 81)이에요. 두 문서는 같은 녹음에서 나와 원문이 같아요
export const documentTranscriptsResponse = [
  {
    transcriptId: memberWithdrawalDocument.sourceTranscriptId,
    isPartial: false,
    recordingDurationSeconds: memberWithdrawalDocument.recordingDurationSeconds,
    transcriptText: transcriptSegments.map(({ text }) => text).join(" "),
    segments: transcriptSegments,
  },
] satisfies DocumentTranscriptResponse[];
