import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import {
  useSearchTutorialStore,
  type SearchTutorialStep,
  type SearchTutorialVariant,
} from "@store/searchTutorialStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter, Route, Routes } from "react-router";

import SearchTutorial from ".";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({ routeKey: "WORKSPACE_HOME", params: { workspaceId: WORKSPACE_ID } });
const CHAT_PATH = getRouterPath({ routeKey: "CHAT", params: { workspaceId: WORKSPACE_ID } });
const FIGMA = "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=";

/** 튜토리얼이 밝히는 GNB 탐색 탭과 독 자리. 실제 GNB와 독 대신 같은 자리에 같은 이름표만 둬요 */
const StandInScreen = ({ isChat }: { isChat: boolean }) => (
  <div style={{ position: "relative", height: "100vh", background: "#FAF9F7" }}>
    <nav style={{ display: "flex", justifyContent: "center", paddingTop: 24 }}>
      <div style={{ display: "flex", gap: 2, padding: 6, borderRadius: 999, background: "#fff" }}>
        <span style={{ padding: "6px 14px" }}>홈</span>
        <span data-search-tutorial="search-tab" style={{ padding: "6px 14px" }}>
          탐색
        </span>
        <span style={{ padding: "6px 14px" }}>문서</span>
      </div>
    </nav>
    <div style={{ position: "absolute", bottom: 56, left: 0, right: 0, display: "flex", justifyContent: "center" }}>
      {isChat ? (
        <div
          data-search-tutorial="dock-input"
          style={{ width: "min(760px, calc(100% - 32px))", height: 64, borderRadius: 30, background: "#3C3B39" }}
        />
      ) : (
        <span data-search-tutorial="dock-chat" style={{ width: 40, height: 40, borderRadius: 20, background: "#3C3B39" }} />
      )}
    </div>
  </div>
);

interface PresetParameters {
  initialPath: string;
  step: SearchTutorialStep;
  variant: SearchTutorialVariant;
}

/**
 * 탐색 튜토리얼이에요. 탐색이라는 기능이 있고, 회의가 끝난 뒤 정한 것과 못 정한 것을 물어볼 때 쓴다는 것을 직접 겪게 해요.
 *
 * **언제 뜨나**
 * - 완료된 회의 문서가 있는 워크스페이스에서, 멤버마다 처음 한 번 떠요. 문서가 없으면 첫 문서가 생길 때까지 기다려요.
 * - 독과 GNB가 있는 화면 어디서든 떠요. 녹음 중이거나 토스트, 대화상자가 떠 있거나 입력창에 적고 있으면 끝날 때까지 기다려요.
 * - 녹음 문서 화면에서는 5초가 지나거나 한 번 스크롤한 뒤에 떠요.
 * - 「다음에 볼게요」를 누르면 다음 날부터 한 번 더 뜨고, 그때는 「닫기」만 둬요. 끝내거나 건너뛰거나 닫으면 다시 뜨지 않아요.
 * - 탐색 빈 대화의 「탐색 사용법 보기」로 언제든 다시 볼 수 있어요.
 *
 * **단계**
 * 1. 화면을 어둡게 하고 GNB 탐색 탭과 독 채팅 버튼(탐색 화면에서는 입력창)만 밝혀요. 밝힌 곳이나 「보여 주세요」를 누르면 탐색으로 가요.
 * 2. 예시 질문을 독 입력창에 한 글자씩 넣고 자동으로 보내요.
 * 3. 예시 답변을 대화 칸에 쓰듯 보여 준 뒤 결론과 근거 문서를 설명해요(탐색 대화 칸의 `TutorialExample` 스토리).
 * 4. 질문 종류 카드 3개와 입력창을 밝혀 직접 묻게 해요. 카드를 누르거나 직접 보내면 실제 질문이 되고 끝나요.
 *
 * 예시 대화는 늘 같은 목 데이터라 서버에 보내지 않고 대화 목록에도 남지 않아요.
 * 이 스토리는 실제 GNB와 독 대신 같은 자리에 표시만 두고 단계를 정해 보여 줘요.
 */
const meta = {
  title: "Search/SearchTutorial",
  component: SearchTutorial,
  parameters: {
    layout: "fullscreen",
    initialPath: HOME_PATH,
    step: 1,
    variant: "default",
    design: [
      { name: "탐색/튜토리얼 1 · 홈에서 시작", type: "figma", url: `${FIGMA}6342-4519` },
      { name: "Coach/Card", type: "figma", url: `${FIGMA}6341-5037` },
      { name: "Tutorial/Bar", type: "figma", url: `${FIGMA}6341-5032` },
      { name: "Search/QuestionKind", type: "figma", url: `${FIGMA}6219-5398` },
      { name: "탐색/튜토리얼 3 · 예시 답변과 근거 설명", type: "figma", url: `${FIGMA}6342-4909` },
    ],
  },
  decorators: [
    (Story, { parameters }) => {
      const { initialPath, step, variant } = parameters as unknown as PresetParameters;
      const state = useSearchTutorialStore.getState();
      // 정해 둔 단계로 바로 그려요. 저절로 뜨는 판단은 단계가 있으면 건너뛰어요
      if (state.step !== step || state.variant !== variant) {
        useSearchTutorialStore.setState({ step, variant, source: "home", draft: null, isAnswerShown: false });
      }

      return (
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            {[PATH_ROUTE.WORKSPACE_HOME, PATH_ROUTE.CHAT].map((path) => (
              <Route
                key={path}
                path={path}
                element={
                  <ToastProvider>
                    <StandInScreen isChat={path === PATH_ROUTE.CHAT} />
                    <Story />
                  </ToastProvider>
                }
              />
            ))}
          </Routes>
        </MemoryRouter>
      );
    },
  ],
} satisfies Meta<typeof SearchTutorial>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 홈에서 처음 뜬 1단계예요. 탐색 탭과 독 채팅 버튼만 밝아요. */
export const Default: Story = {};

/** 「다음에 볼게요」를 누른 다음 날 두 번째로 뜬 1단계예요. 미루기 대신 닫기만 있어요. */
export const ShownAgain: Story = {
  parameters: { variant: "again" },
};

/** 녹음 문서를 살짝 확인한 뒤 뜬 1단계예요. 제목이 지금 회의를 가리켜요. */
export const OnRecordingDocument: Story = {
  parameters: { variant: "document", design: { type: "figma", url: `${FIGMA}6348-5084` } },
};

/** 탐색 화면에서 뜬 1단계예요. 독 채팅 버튼 대신 펼쳐진 입력창을 밝혀요. */
export const OnSearchScreen: Story = {
  parameters: { initialPath: CHAT_PATH, design: { type: "figma", url: `${FIGMA}6348-5057` } },
};

/** 2단계예요. 예시 질문을 입력창에 넣는 동안 안내 카드와 위쪽 바를 보여 줘요. */
export const TypingExampleQuestion: Story = {
  parameters: { initialPath: CHAT_PATH, step: 2, design: { type: "figma", url: `${FIGMA}6342-4813` } },
};

/** 4단계예요. 질문 종류 카드 3개와 입력창을 밝혀 직접 묻게 해요. */
export const AskYourself: Story = {
  parameters: { initialPath: CHAT_PATH, step: 4, design: { type: "figma", url: `${FIGMA}6342-4954` } },
};
