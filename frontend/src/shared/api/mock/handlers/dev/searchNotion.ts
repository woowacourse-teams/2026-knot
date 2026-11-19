import { toSearchAnswerStreamResponse } from "@api/mock/handlers/api/v1/workspaces/[workspaceId]/search/conversations";
import {
  searchAnswerStreamResponse,
  searchMessagesResponse,
  searchNotionAnswerStreamResponse,
  searchNotionConnectionResponse,
  searchWriteCardAnswerStreamResponse,
  searchWriteCardCreatedPageUrl,
  searchWriteCardPageResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import type {
  SearchAnswerStream,
  SearchNotionNoticeReason,
  SearchWriteCard,
} from "@api/mock/types/search";
import { delay, http, HttpResponse } from "msw";

/**
 * 브라우저 QA와 E2E에서만 탐색 채팅의 노션 근거, 노션 안내, 진행 표시, 쓰기 확인 카드를 재현해요.
 *
 * `knot.mock.searchNotion`에 시나리오를 적으면 첫 질문, 후속 질문, 메시지 조회, 카드 실행과 취소, 노션 연결 조회가 그 시나리오로 답해요.
 * - `notion-evidence`: 회의 문서 2건과 노션 1건으로 답해요
 * - `notice:{사유}`: 노션을 읽지 못한 사유(`NOT_CONNECTED` 등)와 함께 답해요
 * - `progress`: 찾는 중과 작성 중을 각각 잠시 보여준 뒤 답해요
 * - `card:{결과}`: 답글과 대기 카드로 답하고, [만들기]마다 정한 결과를 차례로 돌려줘요. 후속 질문을 보내면 앞선 대기 카드를 취소됨으로 바꾸고 새 카드를 줘요
 * 카드와 연결 상태는 새로고침 뒤에도 이어지도록 `knot.mock.searchNotion.state`에 둬요.
 */
const SCENARIO_KEY = "knot.mock.searchNotion";
const STATE_KEY = "knot.mock.searchNotion.state";

/** 단계 하나를 보여 주는 시간(ms). 사람이 「찾는 중」, 「작성 중」을 알아볼 만큼 머물러요 */
const STAGE_HOLD = 1500;
/** 카드 실행이 끝나기까지 걸리는 시간(ms). 처리 중 모습을 볼 수 있게 늦춰요 */
const EXECUTE_DELAY = 800;

const FOLLOW_UP_QUESTION_ID = 1003;
const FOLLOW_UP_ANSWER_ID = 1004;

type ConnectionStatus = "CONNECTED" | "NOT_CONNECTED" | "EXPIRED";

interface MockState {
  /** 첫 답변에 붙은 카드 */
  card: SearchWriteCard;
  /** 후속 질문으로 받은 새 카드 */
  followUpCard: SearchWriteCard | null;
  /** [만들기]를 받은 수 */
  executeCount: number;
  connection: ConnectionStatus;
}

const readScenario = () => {
  try {
    return localStorage.getItem(SCENARIO_KEY);
  } catch {
    return null;
  }
};

const failed = (failureReason: SearchWriteCard["failureReason"]) =>
  ({ status: "FAILED", failureReason }) as const;
const retry = (retryReason: SearchWriteCard["retryReason"]) =>
  ({ status: "PENDING", retryReason }) as const;
const done = { status: "DONE", createdPageUrl: searchWriteCardCreatedPageUrl } as const;

/** 카드 시나리오마다 처음 카드, [만들기]를 누를 때마다 바뀔 카드 값, 처음 노션 연결 상태 */
const CARD_SCENARIOS: Record<
  string,
  {
    initial: SearchWriteCard;
    results: Partial<SearchWriteCard>[];
    connection: ConnectionStatus;
  }
> = {
  done: { initial: searchWriteCardResponse, results: [done], connection: "CONNECTED" },
  page: { initial: searchWriteCardPageResponse, results: [done], connection: "CONNECTED" },
  existing: {
    initial: {
      ...searchWriteCardResponse,
      existingPages: [
        { url: "https://www.notion.so/knot-team/weekly-recent", title: "11월 13일 주간 회의", createdAt: "2026-11-13T05:00:00Z" },
        { url: "https://www.notion.so/knot-team/weekly-older", title: "11월 6일 주간 회의", createdAt: "2026-11-06T05:00:00Z" },
      ],
    },
    results: [done],
    connection: "CONNECTED",
  },
  reconnect: { initial: searchWriteCardResponse, results: [retry("NOTION_RECONNECT_REQUIRED"), done], connection: "EXPIRED" },
  "connection-required": { initial: searchWriteCardResponse, results: [retry("NOTION_CONNECTION_REQUIRED"), done], connection: "NOT_CONNECTED" },
  "rate-limited": { initial: searchWriteCardResponse, results: [retry("NOTION_RATE_LIMITED"), done], connection: "CONNECTED" },
  "fields-changed": {
    initial: searchWriteCardResponse,
    results: [
      {
        ...retry("DATABASE_FIELDS_CHANGED"),
        databaseFields: [
          { name: "이름", value: "11월 13일 주간 회의" },
          { name: "회의 날짜", value: "2026-11-13" },
        ],
      },
      done,
    ],
    connection: "CONNECTED",
  },
  "permission-lost": { initial: searchWriteCardResponse, results: [failed("LOCATION_PERMISSION_LOST")], connection: "CONNECTED" },
  "connection-changed": { initial: searchWriteCardResponse, results: [failed("NOTION_CONNECTION_CHANGED")], connection: "CONNECTED" },
};

const cardScenario = () => {
  const scenario = readScenario();
  if (!scenario?.startsWith("card:")) return null;

  return CARD_SCENARIOS[scenario.slice("card:".length)] ?? null;
};

const readState = (): MockState | null => {
  const scenario = cardScenario();
  if (!scenario) return null;

  const saved = localStorage.getItem(STATE_KEY);
  if (saved) return JSON.parse(saved) as MockState;

  return {
    card: scenario.initial,
    followUpCard: null,
    executeCount: 0,
    connection: scenario.connection,
  };
};

const writeState = (state: MockState) => {
  localStorage.setItem(STATE_KEY, JSON.stringify(state));
};

/** 시나리오의 첫 질문 응답. 카드 시나리오가 아니면 노션 근거나 안내를 담아요 */
const firstAnswerStream = (): SearchAnswerStream | null => {
  const scenario = readScenario();
  if (scenario === "notion-evidence") return searchNotionAnswerStreamResponse;
  if (scenario?.startsWith("notice:")) {
    return {
      ...searchAnswerStreamResponse,
      notionNotice: scenario.slice("notice:".length) as SearchNotionNoticeReason,
    };
  }

  const state = readState();
  if (state) return { ...searchWriteCardAnswerStreamResponse, writeCard: state.card };

  return null;
};

const encoder = new TextEncoder();
const toFrame = (event: string, data: object) =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

/** 찾는 중과 작성 중을 각각 STAGE_HOLD만큼 보여준 뒤 답하는 스트림 */
const slowStageStream = () => {
  const { conversationId, questionMessageId, answerMessageId, deltas, evidences } =
    searchAnswerStreamResponse;

  return new HttpResponse(
    new ReadableStream({
      async start(controller) {
        const send = (event: string, data: object) =>
          controller.enqueue(encoder.encode(toFrame(event, data)));

        send("accepted", { conversationId, questionMessageId, answerMessageId });
        send("progress", { stage: "SEARCHING" });
        await delay(STAGE_HOLD);
        send("progress", { stage: "GENERATING" });
        await delay(STAGE_HOLD);
        for (const text of deltas) send("delta", { answerMessageId, text });
        send("evidence", { answerMessageId, items: evidences });
        send("completed", { answerMessageId, status: "COMPLETED" });
        controller.close();
      },
    }),
    { headers: { "Content-Type": "text/event-stream" } },
  );
};

export const searchNotionHandlers = [
  http.post("*/api/v1/workspaces/:workspaceId/search/conversations", () => {
    if (readScenario() === "progress") return slowStageStream();

    const stream = firstAnswerStream();
    if (!stream) return;

    return toSearchAnswerStreamResponse(stream);
  }),

  // 실제 서버처럼 같은 대화에서 다시 요청하면 앞선 대기 카드를 취소됨으로 바꾸고 새 카드를 만들어요(SEARCH-R41)
  http.post(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/questions",
    ({ params }) => {
      const state = readState();
      if (!state) return;

      const followUpCard = {
        ...state.card,
        id: state.card.id + 100,
        status: "PENDING",
        title: "주간 회의 결정 정리",
      } as const;
      writeState({
        ...state,
        card:
          state.card.status === "PENDING"
            ? { ...state.card, status: "CANCELED" }
            : state.card,
        followUpCard,
      });

      return toSearchAnswerStreamResponse({
        ...searchWriteCardAnswerStreamResponse,
        conversationId: Number(params.conversationId),
        questionMessageId: FOLLOW_UP_QUESTION_ID,
        answerMessageId: FOLLOW_UP_ANSWER_ID,
        deltas: ["제목을 바꾼 카드를 새로 준비했어요."],
        writeCard: followUpCard,
      });
    },
  ),

  http.get(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages",
    ({ params }) => {
      const stream = firstAnswerStream();
      if (!stream) return;

      const state = readState();
      const [question, answer] = searchMessagesResponse.items;
      const items = [
        { ...question, id: stream.questionMessageId, sequence: 1, content: "회의록을 노션에 만들어 줘" },
        {
          ...answer,
          id: stream.answerMessageId,
          sequence: 2,
          content: stream.deltas.join(""),
          evidences: stream.evidences,
          notionEvidences: stream.notionEvidences ?? [],
          notionNotice: stream.notionNotice ?? null,
          writeCard: state?.card ?? null,
        },
      ];

      if (state?.followUpCard) {
        items.push(
          { ...question, id: FOLLOW_UP_QUESTION_ID, sequence: 3, content: "제목을 주간 회의 결정 정리로 바꿔 줘" },
          {
            ...answer,
            id: FOLLOW_UP_ANSWER_ID,
            sequence: 4,
            content: "제목을 바꾼 카드를 새로 준비했어요.",
            evidences: [],
            notionEvidences: [],
            notionNotice: null,
            writeCard: state.followUpCard,
          },
        );
      }

      return HttpResponse.json({
        ...searchMessagesResponse,
        conversationId: Number(params.conversationId),
        items,
      });
    },
  ),

  http.post(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/execute",
    async ({ params }) => {
      const state = readState();
      const scenario = cardScenario();
      if (!state || !scenario) return;

      await delay(EXECUTE_DELAY);
      const isFollowUp = state.followUpCard?.id === Number(params.cardId);
      const target = isFollowUp ? state.followUpCard : state.card;
      if (!target) return HttpResponse.json(null, { status: 404 });
      // 대기가 아닌 카드는 다시 만들지 않고 지금 상태를 돌려줘요(SEARCH-R43)
      if (target.status !== "PENDING") return HttpResponse.json(target);

      const result = scenario.results[Math.min(state.executeCount, scenario.results.length - 1)];
      const executed = { ...target, retryReason: null, failureReason: null, ...result };
      writeState({
        ...state,
        executeCount: state.executeCount + 1,
        ...(isFollowUp ? { followUpCard: executed } : { card: executed }),
      });

      return HttpResponse.json(executed);
    },
  ),

  http.post(
    "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/write-cards/:cardId/cancel",
    ({ params }) => {
      const state = readState();
      if (!state) return;

      const isFollowUp = state.followUpCard?.id === Number(params.cardId);
      const target = isFollowUp ? state.followUpCard : state.card;
      if (!target) return HttpResponse.json(null, { status: 404 });

      const canceled = target.status === "PENDING" ? { ...target, status: "CANCELED" as const } : target;
      writeState({ ...state, ...(isFollowUp ? { followUpCard: canceled } : { card: canceled }) });

      return HttpResponse.json(canceled);
    },
  ),

  http.get("*/api/v1/workspaces/:workspaceId/search/notion-connection", () => {
    const state = readState();
    if (!state) return;

    return HttpResponse.json({ ...searchNotionConnectionResponse, status: state.connection });
  }),
];
