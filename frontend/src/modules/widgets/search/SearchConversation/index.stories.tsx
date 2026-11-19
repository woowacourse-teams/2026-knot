import {
  searchMessagesResponse,
  searchNotionEvidencesResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import type { SearchMessage } from "@api/mock/types/search";
import { PATH_ROUTE, getRouterPath } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useSearchAnswerStore } from "@store/searchAnswerStore";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { delay, http, HttpResponse } from "msw";
import { type ReactElement, useEffect, useState } from "react";
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
const FIGMA_URL = "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=";

/** 메시지 응답을 덮는 노션 스토리는 문서 페이지에서 따로 그려요 */
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 640 } };

/** 이번 스토리에서만 첫 답변을 넘긴 값으로 덮어 메시지를 돌려줘요 */
const firstAnswerHandler = (patch: Partial<SearchMessage>) =>
  http.get(MESSAGES_URL, ({ params }) => {
    const [question, answer, ...rest] = searchMessagesResponse.items;

    return HttpResponse.json({
      ...searchMessagesResponse,
      conversationId: Number(params.conversationId),
      items: [question, { ...answer, ...patch }, ...rest],
    });
  });

/** 이 대화에서 보낸 질문이 접수돼, 답변 텍스트가 오기 전 넘긴 진행 단계에 있는 상태로 그려요. 스토리를 떠나면 비워요 */
const answeringDecorator =
  (stage: "SEARCHING" | "GENERATING") =>
  (Story: () => ReactElement) => {
    const AnsweringStory = () => {
      useState(() => {
        useSearchAnswerStore.setState({
          status: "STREAMING",
          question: "기획서 출시일이 회의 결정과 같아?",
          conversationId: 10,
          questionMessageId: 1001,
          answerMessageId: 1002,
          text: "",
          stage,
        });
      });
      useEffect(() => () => useSearchAnswerStore.getState().abort(), []);

      return <Story />;
    };

    return <AnsweringStory />;
  };

/**
 * 탐색 화면 가운데의 대화 칸이에요. 지금 대화가 어떤 상태인지에 따라 칸에 무엇을 그릴지 정해요.
 *
 * **동작 규칙**
 * - 질문이 아직 없는 빈 대화에서는 「무엇을 찾고 있나요?」 안내를 칸 가운데에 띄워요.
 * - 빈 대화는 저장하지 않고, 첫 질문을 받은 뒤에야 대화가 돼요.
 * - 대화가 있으면 질문은 오른쪽 말풍선, 답변은 왼쪽 문단으로 위에서부터 차례로 그려요. 답변은 줄바꿈마다 문단을 나눠요.
 * - 근거가 있는 답변 아래에만 「기록 N개에서 찾았어요」 버튼이 있어요. 누르면 그 답변의 찾은 기록이 화면 오른쪽 패널에 열려요.
 * - 답에 쓴 노션 페이지는 그 옆에 페이지마다 노션 버튼으로 따로 보여요. 누르면 노션 페이지가 새 탭으로 열리고, 버튼이 넘치면 다음 줄로 이어요.
 * - 답변 텍스트가 오기 전에는 답변 자리에 「찾는 중」, 읽기가 끝나면 「작성 중」을 보여줘요. 어느 앱을 읽는지는 보여주지 않아요.
 * - 노션을 읽지 못해 회의 문서만으로 답했으면 답변 아래에 사유를 알려요. 연결이 없거나 만료됐으면 [노션 연결], [다시 연결]로 연결된 앱 화면에 가요.
 * - 노션 페이지를 만들어 달라는 답변에는 답글 아래 확인 카드가 붙어요. 카드의 상태별 모습은 Search/SearchWriteCard에서 봐요.
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

/** 질문이 접수돼 회의 문서와 노션을 읽고 있는 동안이에요. 답변 자리에 「찾는 중」이 보여요. */
export const Searching: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4894` },
  },
  decorators: [answeringDecorator("SEARCHING")],
};

/** 읽기가 끝나 답글을 쓰기 시작한 뒤 첫 글자가 오기 전이에요. 「찾는 중」이 「작성 중」으로 바뀌어요. */
export const Generating: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4894` },
  },
  decorators: [answeringDecorator("GENERATING")],
};

/** 회의 문서 2건과 노션 페이지 1건으로 답했을 때예요. 노션 페이지는 「기록 2개에서 찾았어요」 옆 노션 버튼으로 보여요. */
export const NotionEvidence: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6563-7887` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        messages: firstAnswerHandler({
          notionEvidences: searchNotionEvidencesResponse.slice(0, 1),
        }),
      },
    },
  },
};

/** 노션 페이지 5건을 답에 써 노션 버튼이 한 줄을 넘쳤을 때예요. 넘친 버튼은 다음 줄 왼쪽부터 이어요. */
export const NotionEvidenceOverflow: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6567-4447` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        messages: firstAnswerHandler({
          notionEvidences: searchNotionEvidencesResponse,
        }),
      },
    },
  },
};

/** 회의 문서 근거 없이 노션 페이지로만 답했을 때예요. 「기록 N개에서 찾았어요」 없이 노션 버튼만 보여요. */
export const NotionEvidenceOnly: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        messages: firstAnswerHandler({
          evidences: [],
          notionEvidences: searchNotionEvidencesResponse.slice(0, 1),
        }),
      },
    },
  },
};

/** 워크스페이스에 노션이 연결돼 있지 않아 회의 문서만으로 답했을 때예요. [노션 연결]로 연결된 앱 화면에 가요. */
export const NotionNotConnected: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4982` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: { messages: firstAnswerHandler({ notionNotice: "NOT_CONNECTED" }) },
    },
  },
};

/** 노션에서 질문에 맞는 페이지를 찾지 못했을 때예요. 버튼 없이 안내만 보여요. */
export const NotionPageNotFound: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4982` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: { messages: firstAnswerHandler({ notionNotice: "PAGE_NOT_FOUND" }) },
    },
  },
};

/** 노션이 느리거나 실패해 읽지 못했을 때예요. 답변은 기다리지 않고 회의 문서만으로 나와요. */
export const NotionReadFailed: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4982` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: { messages: firstAnswerHandler({ notionNotice: "READ_FAILED" }) },
    },
  },
};

/** 노션 연결이 만료돼 읽지 못했을 때예요. [다시 연결]로 연결된 앱 화면에 가요. */
export const NotionConnectionExpired: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4982` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        messages: firstAnswerHandler({ notionNotice: "CONNECTION_EXPIRED" }),
      },
    },
  },
};

/** 회의 문서를 노션 페이지로 만들어 달라는 답변에 확인 카드가 붙은 대화예요. [만들기]를 누르면 끝난 뒤 메시지를 다시 받아 카드를 바꿔요. */
export const WriteCard: Story = {
  parameters: {
    initialPath: CONVERSATION_PATH,
    design: { type: "figma", url: `${FIGMA_URL}6500-4008` },
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        messages: firstAnswerHandler({ writeCard: searchWriteCardResponse }),
      },
    },
  },
};
