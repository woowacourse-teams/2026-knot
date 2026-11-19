import {
  searchNotionConnectionResponse,
  searchWriteCardCreatedPageUrl,
  searchWriteCardLongBodyResponse,
  searchWriteCardPageResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import SearchWriteCard from ".";

const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: "1", sessionId: "10" },
});
const NOTION_CONNECTION_URL =
  "*/api/v1/workspaces/:workspaceId/search/notion-connection";
const FIGMA_URL = "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=";

/** 노션 연결 응답을 덮는 스토리는 문서 페이지에서 따로 그려요 */
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 560 } };

/** 이번 스토리에서만 워크스페이스의 노션 연결을 넘긴 상태로 바꿔요 */
const connectionHandler = (status: "NOT_CONNECTED" | "EXPIRED") =>
  http.get(NOTION_CONNECTION_URL, () =>
    HttpResponse.json({ ...searchNotionConnectionResponse, status }),
  );

/**
 * 탐색 채팅에서 회의 문서를 노션 페이지로 만들어 달라고 하면 답글 아래에 나오는 확인 카드예요.
 *
 * **무엇을 보여주나**
 * - 페이지가 생길 위치(경로 한 줄, DB 아래면 끝에 「DB」), 제목, 내용 앞부분, DB 칸(DB 아래일 때만), 근거 회의 문서를 보기 전용으로 보여줘요.
 * - 값을 바꾸려면 카드가 아니라 채팅으로 다시 요청해요. 그러면 서버가 이 카드를 취소됨으로 바꾸고 새 카드를 만들어요.
 * - 내용은 접힌 높이까지만 보이고 [전체 보기]로 펼쳐요.
 *
 * **동작 규칙**
 * - 노션에는 [만들기]를 눌렀을 때만 만들어요. 누르면 바로 두 버튼을 막고 처리 중을 보여줘요. 빠르게 두 번 눌러도 요청은 한 번이에요.
 * - 실행과 취소가 끝나면 대화 메시지를 다시 받아 카드의 지금 상태를 그려요. 이 스토리에는 메시지 조회가 없어, 누른 뒤 카드가 처음 상태로 돌아와요. 바뀐 모습은 상태별 스토리로 봐요.
 * - 다시 누를 수 있는 오류(연결 만료, 연결 없음, 노션 요청 많음, DB 구조 바뀜)는 카드를 대기로 되돌리고 사유를 알려요. 다시 실행할 수 없는 오류는 「만들지 못했어요」와 이유를 보여줘요.
 * - 위치 권한이 없어진 실패, 다른 노션으로 바뀐 실패, 본문이 긴 실패는 「만들지 못했어요」 아래 이유 문구만 달라요.
 * - [다시 연결]과 [연결]은 연결된 앱 화면으로 가요. 연결을 마친 뒤 이 카드로 돌아오는 동작은 아직 정하지 않았어요.
 */
const meta = {
  title: "Search/SearchWriteCard",
  component: SearchWriteCard,
  parameters: {
    design: [
      { name: "확인 카드 / 대기, DB 아래 (DB 칸 표시)", type: "figma", url: `${FIGMA_URL}6500-4008` },
      { name: "확인 카드 / 대기, 내용이 길 때 (펼침)", type: "figma", url: `${FIGMA_URL}6551-4318` },
      { name: "확인 카드 실패와 안내 문구", type: "figma", url: `${FIGMA_URL}6500-4482` },
    ],
  },
  args: { card: searchWriteCardResponse },
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={[CONVERSATION_PATH]}>
        <Routes>
          <Route
            path={PATH_ROUTE.CHAT_SESSION}
            element={
              // 카드는 탐색 답변 칸(760px) 안에 놓여요
              <div style={{ maxWidth: "47.5rem", padding: "2.5rem" }}>
                <Story />
              </div>
            }
          />
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof SearchWriteCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** DB 아래에 만드는 대기 카드예요. 채워질 DB 칸이 함께 보여요. 예: 「오늘 회의록 노션 회의록 DB에 만들어 줘」 */
export const Default: Story = {};

/** 일반 페이지 아래에 만드는 대기 카드예요. DB 칸이 없어요. */
export const UnderPage: Story = {
  args: { card: searchWriteCardPageResponse },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-3961` } },
};

/** 초안이 길어 접어 둔 카드예요. 아래가 흐려지고 [전체 보기]를 누르면 초안 전체가 보여요. 예: 회의록 전체를 만들어 달라고 했을 때 */
export const LongBody: Story = {
  args: { card: searchWriteCardLongBodyResponse },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6551-4262` } },
};

/** 같은 대화에서 같은 회의 문서로 이미 만든 페이지가 있을 때예요. 최근 것부터 안내하고, 만들기는 막지 않아요. */
export const ExistingPage: Story = {
  args: {
    card: {
      ...searchWriteCardResponse,
      existingPages: [
        { url: searchWriteCardCreatedPageUrl, createdAt: "2026-11-13T05:00:00Z" },
      ],
    },
  },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4375` } },
};

/** DB 속성을 아직 확인하지 못한 카드예요. DB 칸 대신 「확인하는 중」이 보이고 [만들기]를 누를 수 없어요. */
export const DatabaseFieldsUnchecked: Story = {
  args: {
    card: { ...searchWriteCardResponse, databaseFields: null, executable: false },
  },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4430` } },
};

/** [만들기]를 눌러 노션에 만드는 중이에요. 두 버튼을 누를 수 없어요. 다른 탭에서 누른 카드도 끝날 때까지 이 모습이에요. */
export const Running: Story = {
  args: { card: { ...searchWriteCardResponse, status: "RUNNING" } },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4058` } },
};

/** 노션에 페이지를 만든 뒤예요. [노션에서 열기]로 만든 페이지를 새 탭으로 열어요. */
export const Done: Story = {
  args: {
    card: {
      ...searchWriteCardResponse,
      status: "DONE",
      createdPageUrl: searchWriteCardCreatedPageUrl,
    },
  },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4116` } },
};

/** [취소]를 눌렀거나 채팅으로 다시 요청해 취소된 카드예요. 내용은 흐리게 두고 다시 실행할 수 없어요. */
export const Canceled: Story = {
  args: { card: { ...searchWriteCardResponse, status: "CANCELED" } },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4170` } },
};

/** 다시 실행할 수 없는 오류로 실패한 카드예요. 예: 노션이 10초 동안 응답하지 않았을 때 */
export const Failed: Story = {
  args: {
    card: {
      ...searchWriteCardResponse,
      status: "FAILED",
      failureReason: "NOTION_NO_RESPONSE",
    },
  },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4219` } },
};

/** 실행할 때 노션 연결이 만료돼 대기로 돌아온 카드예요. [다시 연결]로 연결된 앱 화면에 가요. 다시 연결되면 [만들기]가 돌아와요. */
export const ReconnectRequired: Story = {
  args: {
    card: { ...searchWriteCardResponse, retryReason: "NOTION_RECONNECT_REQUIRED" },
  },
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}6500-4271` },
    docs: ISOLATED_DOCS,
    msw: { handlers: { notionConnection: connectionHandler("EXPIRED") } },
  },
};

/**
 * 실행할 때 워크스페이스에 노션 연결이 없어 대기로 돌아온 카드예요. [연결]로 연결된 앱 화면에 가요.
 * 시안은 실험 페이지에 있고 디자인 컨펌 전이에요.
 */
export const ConnectionRequired: Story = {
  args: {
    card: { ...searchWriteCardResponse, retryReason: "NOTION_CONNECTION_REQUIRED" },
  },
  parameters: {
    design: { type: "figma", url: `${FIGMA_URL}6586-5134` },
    docs: ISOLATED_DOCS,
    msw: { handlers: { notionConnection: connectionHandler("NOT_CONNECTED") } },
  },
};

/** 노션이 요청이 많다고 거절해 대기로 돌아온 카드예요. 잠시 뒤 [만들기]를 다시 누르면 돼요. */
export const RateLimited: Story = {
  args: {
    card: { ...searchWriteCardResponse, retryReason: "NOTION_RATE_LIMITED" },
  },
  parameters: { design: { type: "figma", url: `${FIGMA_URL}6500-4328` } },
};

/** 만들 때 DB 구조가 달라져 DB 칸을 새로 불러온 카드예요. 바뀐 칸을 확인하고 [만들기]를 다시 눌러요. 카드 시안은 없고 문구만 정해져 있어요. */
export const DatabaseFieldsChanged: Story = {
  args: {
    card: { ...searchWriteCardResponse, retryReason: "DATABASE_FIELDS_CHANGED" },
  },
};
