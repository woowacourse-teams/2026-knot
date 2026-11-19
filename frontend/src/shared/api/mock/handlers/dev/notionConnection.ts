import { http, HttpResponse } from "msw";

import { searchNotionConnectionResponse } from "@api/mock/responses/search";
import type { SearchNotionConnection } from "@api/mock/types/search";

/**
 * 개발 브라우저에서 노션 연결 상태를 고르는 localStorage 키.
 * E2E는 `page.addInitScript`로 이 값을 넣고 연결된 앱 화면을 열어요.
 */
export const NOTION_CONNECTION_SCENARIO_KEY = "knot.mock.notionConnection";

const NOT_CONNECTED = {
  status: "NOT_CONNECTED",
  notionWorkspaceName: null,
  notionWorkspaceIcon: null,
  connectedBy: null,
  canDisconnect: false,
} satisfies SearchNotionConnection;

/** 다른 멤버가 연결한 노션을 끊을 수 없는 멤버가 보는 모습 */
const CONNECTED_BY_OTHER = {
  ...searchNotionConnectionResponse,
  connectedBy: { memberId: 2, nickname: "모카" },
  canDisconnect: false,
} satisfies SearchNotionConnection;

/**
 * 고를 수 있는 상황. 값이 없으면 기본 핸들러(연결한 사람이 보는 연결됨)가 답해요.
 *
 * - `alreadyConnected`: 연결 전으로 보이지만 그사이 다른 멤버가 연결을 마쳐, 연결 시작이 409로 거절돼요
 */
const SCENARIOS = {
  notConnected: NOT_CONNECTED,
  connectedByOther: CONNECTED_BY_OTHER,
  expired: { ...CONNECTED_BY_OTHER, status: "EXPIRED" },
  expiredOwner: { ...searchNotionConnectionResponse, status: "EXPIRED" },
  alreadyConnected: NOT_CONNECTED,
} satisfies Record<string, SearchNotionConnection>;

type NotionConnectionScenario = keyof typeof SCENARIOS;

const readScenario = () => {
  try {
    const value = localStorage.getItem(NOTION_CONNECTION_SCENARIO_KEY);

    return value !== null && value in SCENARIOS
      ? (value as NotionConnectionScenario)
      : null;
  } catch {
    return null;
  }
};

// 끊기나 409로 바뀐 연결. 새로고침하면 고른 상황으로 돌아가요
let changedConnection: SearchNotionConnection | null = null;

/** 브라우저 QA와 E2E에서 노션 연결 상태와 끊기, 연결 시작 409를 재현해요 */
export const notionConnectionHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/search/notion-connection", () => {
    const scenario = readScenario();
    const connection =
      changedConnection ?? (scenario ? SCENARIOS[scenario] : null);

    return connection ? HttpResponse.json(connection) : undefined;
  }),
  http.delete(
    "*/api/v1/workspaces/:workspaceId/search/notion-connection",
    () => {
      changedConnection = NOT_CONNECTED;

      return new HttpResponse(null, { status: 204 });
    },
  ),
  http.post(
    "*/api/v1/workspaces/:workspaceId/search/notion-connection/authorization",
    () => {
      if (readScenario() !== "alreadyConnected" || changedConnection) return;

      changedConnection = CONNECTED_BY_OTHER;

      return HttpResponse.json(
        {
          code: "NOTION_CONNECTION_ALREADY_CONNECTED",
          message: "이미 노션이 연결돼 있어요",
        },
        { status: 409 },
      );
    },
  ),
];
