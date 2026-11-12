import { searchMessagesResponse } from "@api/mock/responses/search";
import { PATH_ROUTE, getRouterPath } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import SearchConversation from ".";

const WORKSPACE_ID = "1";
const CHAT_PATH = getRouterPath({
  routeKey: "CHAT",
  params: { workspaceId: WORKSPACE_ID },
});
const CONVERSATION_PATH = getRouterPath({
  routeKey: "CHAT_SESSION",
  params: { workspaceId: WORKSPACE_ID, sessionId: "10" },
});
/** mock 대화에서 근거가 2개인 첫 답변의 ID */
const FIRST_ANSWER_ID = 2;
const MESSAGES_URL =
  "*/api/v1/workspaces/:workspaceId/search/conversations/:conversationId/messages";

/**
 * 탐색 화면 가운데의 대화 칸이에요. 지금 대화가 어떤 상태인지에 따라 칸에 무엇을 그릴지 정해요.
 *
 * **동작 규칙**
 * - 질문이 아직 없는 빈 대화에서는 「무엇을 찾고 있나요?」 안내를 칸 가운데에 띄워요.
 * - 빈 대화는 저장하지 않고, 첫 질문을 받은 뒤에야 대화가 돼요.
 * - 대화가 있으면 질문은 오른쪽 말풍선, 답변은 왼쪽 문단으로 위에서부터 차례로 그려요. 답변은 줄바꿈마다 문단을 나눠요.
 * - 근거가 있는 답변 아래에만 「기록 N개에서 찾았어요」 버튼이 있어요. 누르면 그 답변의 찾은 기록이 화면 오른쪽 패널에 열려요.
 * - 저장된 질문·답변 뒤에는 이 대화에서 독으로 보내 지금 받고 있는 질문과 답변을 이어 그려요. 서버가 질문을 받기 전에는 질문만 보이고, 답변은 도착하는 대로 늘어나요.
 * - 답변이 끝나 대화를 다시 불러와도 같은 답변이 두 번 보이지 않아요.
 * - 이전 실패 답변은 미검증 글자 대신 안내 문단으로 보여줘요. 새 질문으로 대화를 이어갈 수 있어요.
 * - 지난 대화의 메시지를 받는 동안에는 시작 전 안내를 띄우지 않아요. 보던 대화에서 옮겨 왔으면 응답이 올 때까지 보던 대화를 누를 수 없게 둔 채 보여주고, 보여 줄 대화가 없으면 스켈레톤을 그려요(SEARCH-15).
 * - 메시지를 받지 못하면 칸 안에 불러오기 실패 안내와 `다시 시도`를 둬요.
 * - 빈 대화 아래 「탐색 사용법 보기」를 누르면 탐색 튜토리얼을 다시 볼 수 있어요. 튜토리얼이 떠 있는 동안은 숨겨요.
 * - 탐색 튜토리얼 3단계에는 서버 대화 대신 예시 대화(목 데이터)를 그려요. 근거 문서 카드는 예시라 누를 수 없어요.
 * - 대화는 메시지 조회 mock 응답으로 그려요. 근거가 2개·3개·없는 답변이 한 턴씩 있어요.
 */
const meta = {
  title: "Search/SearchConversation",
  component: SearchConversation,
  parameters: {
    layout: "fullscreen",
    initialPath: CHAT_PATH,
    design: [
      { name: "기존 오류 표시 참고", type: "figma", url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2072-26749" },
      {
        name: "탐색/시작 전",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2098-29235",
      },
      {
        name: "Search/Answer",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36200",
      },
      {
        name: "탐색/시작 전 (탐색 사용법 보기)",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1917-18392",
      },
    ],
  },
  decorators: [
    (Story, { parameters }) => (
      <MemoryRouter initialEntries={[parameters.initialPath]}>
        <Routes>
          {[PATH_ROUTE.CHAT, PATH_ROUTE.CHAT_SESSION].map((path) => (
            <Route
              key={path}
              path={path}
              element={
                // 대화 칸은 화면 높이를 채우고 독과 같은 폭(760px)에 놓이므로 탐색 화면과 비슷하게 줘요
                <div
                  style={{
                    height: "40rem",
                    maxWidth: "47.5rem",
                    margin: "0 auto",
                  }}
                >
                  <Story />
                </div>
              }
            />
          ))}
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof SearchConversation>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 새 채팅을 눌러 질문 없이 탐색 화면에 들어온 상태예요. */
export const Default: Story = {};

/** 질문과 답변이 오간 대화예요. 찾은 기록은 닫혀 있어요. */
export const Conversation: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2106-28969",
    },
  },
};

/** 첫 답변의 「기록 2개에서 찾았어요」를 눌러 그 답변의 찾은 기록을 연 상태예요. 그 버튼만 채워진 모양이에요. */
export const EvidenceOpen: Story = {
  parameters: {
    initialPath: `${CONVERSATION_PATH}?messageId=${FIRST_ANSWER_ID}`,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2106-28974",
    },
  },
};

/**
 * 이전에 실패한 답변을 안내 문단으로 보여줘요. 저장된 미검증 글자는 표시하지 않아요.
 */
export const AnswerGuidance: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    docs: { story: { inline: false, iframeHeight: 640 } },
    msw: {
      handlers: {
        messages: http.get(MESSAGES_URL, ({ params }) => {
          const [question, answer, ...rest] = searchMessagesResponse.items;

          return HttpResponse.json({
            ...searchMessagesResponse,
            conversationId: Number(params.conversationId),
            items: [
              question,
              {
                ...answer,
                content: "댓글이 달린 게시글은",
                status: "FAILED",
                evidences: [],
              },
              ...rest,
            ],
          });
        }),
      },
    },
  },
};

/**
 * 새로고침이나 주소로 지난 대화에 들어와, 보여 줄 대화 없이 메시지를 받는 중이에요.
 * 보던 대화에서 옮겨 온 경우에는 이 화면 대신 보던 대화를 그대로 둬요.
 */
export const Loading: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3818-39781",
    },
    msw: {
      handlers: {
        messages: http.get(MESSAGES_URL, async () => {
          await delay("infinite");
          return HttpResponse.json(searchMessagesResponse);
        }),
      },
    },
  },
};

/** 지난 대화의 메시지를 받지 못한 상태예요. 피그마 시안이 아직 없어요. */
export const LoadFailed: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    msw: {
      handlers: {
        messages: http.get(MESSAGES_URL, () =>
          HttpResponse.json(null, { status: 500 }),
        ),
      },
    },
  },
};

/** 탐색 튜토리얼 3단계예요. 서버 대신 예시 대화를 그리고, 답변을 한 글자씩 쓴 뒤 근거 문서 카드를 보여 줘요. */
export const TutorialExample: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6342-4909",
    },
  },
  decorators: [
    (Story) => {
      if (useSearchTutorialStore.getState().step !== 3) {
        useSearchTutorialStore.setState({ step: 3, variant: "default", source: "search", draft: null, isAnswerShown: false });
      }

      return <Story />;
    },
  ],
};
