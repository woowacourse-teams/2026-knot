import { RECORDINGS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings";
import { CURRENT_RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import {
  currentRecordingResponse,
  recordingsResponse,
} from "@api/mock/responses/recording";
import { resetRecordingMockState } from "@api/mock/state/recording";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingHistoryCard from ".";

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CURRENT_URL = `*${CURRENT_RECORDING_API_PATH(WORKSPACE_ID)}`;
const RECORDINGS_URL = `*${RECORDINGS_API_PATH(WORKSPACE_ID)}`;
const figmaUrl = (nodeId: string) =>
  `https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=${nodeId}`;

/** 현재 녹음 조회가 녹음이 없다고(204) 답해 맨 위 줄이 없어요 */
const noCurrentRecording = http.get(
  CURRENT_URL,
  () => new HttpResponse(null, { status: 204 }),
);

// 응답이나 이 탭의 녹음을 바꾸는 스토리는 문서 페이지에서 따로 그려야 다른 스토리와 섞이지 않아요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 620 } };

/** 이 탭의 녹음을 처음 상태로 되돌려요. 녹음은 전역 저장소에 있어 스토리끼리 이어지기 때문이에요. */
const resetRecording = () => {
  useRecordingStore.getState().discardRecording();
};

/** 이 탭에서 12분 48초째 녹음하고 있는 것처럼 저장소를 채워요 */
const setRecordingInThisTab = (status: "recording" | "paused") => {
  useRecordingStore.setState({
    status,
    session: {
      workspaceId: WORKSPACE_ID,
      recordingId: currentRecordingResponse.recordingId,
      audioMode: "FINAL_WEBM_V1",
    },
    accumulatedMs: (12 * 60 + 48) * 1000,
    resumedAt: status === "recording" ? Date.now() : null,
  });
};

/**
 * 워크스페이스 홈 왼쪽 위의 「녹음 기록」 카드예요. 지난 「진행 중인 녹음」 카드와 「최근 추가된 문서」 카드를 대신해요.
 *
 * **동작 규칙**
 * - 맨 위에는 내 녹음 중 · 일시정지 녹음 한 줄(녹음 이름, 상태 · 시간, 안내, 「녹음 화면으로」)을, 그 아래에는 워크스페이스의 끝난 녹음을 최신순으로 보여 줘요. 누가 녹음했는지는 보여 주지 않아요.
 * - 이 탭에서 시작한 녹음이면 「녹음 화면으로」로 녹음 화면에 가요. 다른 탭에서 시작한 녹음이면 버튼을 누를 수 없고, 그 탭에서 일시정지 · 끝내기를 할 수 있다고 안내해요.
 * - 맨 위 줄은 카드 머리 아래에 고정되고, 목록만 카드 안에서 스크롤돼요. 목록 아래 40px은 흐리게 보여요. 스크롤 끝에 닿으면 다음 20개를 이어 받아요.
 * - 행은 제목을 한 줄로 자르고, 마우스를 올리면 전체 제목을 브라우저 툴팁으로 보여 줘요.
 * - 완료 행은 녹음 날짜와 녹음 길이 칩을, 그 밖의 행은 처리 상태(문서 정리 중 · 녹음을 글로 옮기지 못했어요 · 문서를 만들지 못했어요 · 변환된 음성 기록이 없어요.)를 보여 줘요.
 * - 「녹음 보기」로 그 녹음의 녹음 문서에 가요. 문서 정리 중 행의 「녹음 보기」는 흐리게 보이고 누를 수 없어요.
 * - 서버가 다시 시도할 수 있다고 알려 준 실패 행에는 「녹음 보기」 대신 「다시 시도」가 있어요. 누르면 그 행이 문서 정리 중으로 바뀌어요.
 * - 문서 정리 중 행이 있는 동안 3초마다 목록을 다시 받아요.
 * - 맨 위 줄과 목록이 모두 없으면 카드 크기를 그대로 두고 빈 상태 문구를 보여 줘요. 불러오지 못하면 목록 자리에 안내와 「다시 시도」를 둬요.
 */
const meta = {
  title: "Recording/RecordingHistoryCard",
  component: RecordingHistoryCard,
  // 다시 시도가 mock의 다시 시도 기록을 바꾸므로 스토리를 옮길 때마다 되돌려요
  loaders: [
    () => {
      resetRecordingMockState();
    },
  ],
  parameters: {
    layout: "centered",
    design: [
      {
        name: "Card/RecordingHistory",
        type: "figma",
        url: figmaUrl("5423-5379"),
      },
      { name: "홈/녹음 기록", type: "figma", url: figmaUrl("5422-4668") },
    ],
  },
  beforeEach: resetRecording,
  decorators: [
    (Story) => (
      <MemoryRouter initialEntries={[HOME_PATH]}>
        {/* 다시 시도의 오류 토스트가 뜨도록 앱처럼 토스트 공급자를 둬요 */}
        <ToastProvider>
          <Routes>
            <Route path={PATH_ROUTE.WORKSPACE_HOME} element={<Story />} />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RecordingHistoryCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 진행 중인 내 녹음 없이 지난 녹음만 있을 때예요. 처리 상태마다 한 행씩 있고, 아래로 스크롤하면 다음 묶음을 받아요.
 * 맨 위의 문서 정리 중 행은 녹음을 막 끝낸 직후의 모습이기도 해요.
 */
export const Default: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: { handlers: { currentRecording: noCurrentRecording } },
    design: { type: "figma", url: figmaUrl("5423-4715") },
  },
};

/** 이 탭에서 녹음하고 있을 때예요. 예: 독의 마이크로 녹음을 시작하고 홈으로 돌아온 경우 */
export const RecordingInThisTab: Story = {
  beforeEach: () => setRecordingInThisTab("recording"),
  parameters: {
    // 녹음은 전역 저장소에 있어, 문서 페이지에서 함께 그리면 다른 스토리도 이 탭의 녹음으로 보여요
    docs: ISOLATED_DOCS,
    design: { type: "figma", url: figmaUrl("5423-4808") },
  },
};

/** 이 탭의 녹음을 잠시 멈췄을 때예요. 예: 회의 중 쉬는 시간에 일시정지하고 홈을 본 경우 */
export const PausedInThisTab: Story = {
  beforeEach: () => setRecordingInThisTab("paused"),
  parameters: {
    docs: ISOLATED_DOCS,
    design: { type: "figma", url: figmaUrl("5423-4905") },
  },
};

/** 다른 탭에서 녹음하고 있을 때예요. 예: 녹음을 켜 둔 채 새 탭으로 홈을 연 경우. 녹음 화면은 녹음을 시작한 탭에서만 열 수 있어요 */
export const RecordingInOtherTab: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    design: { type: "figma", url: figmaUrl("5423-5002") },
  },
};

/** 스크롤 끝에서 다음 녹음을 받는 중일 때예요. 목록이 짧아 끝 표시가 바로 보이게 첫 묶음을 세 개로 줄였어요 */
export const LoadingMore: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: noCurrentRecording,
        recordings: http.get(RECORDINGS_URL, async ({ request }) => {
          if (new URL(request.url).searchParams.has("cursor")) {
            await delay("infinite");
          }

          return HttpResponse.json({
            items: recordingsResponse.items.slice(0, 3),
            nextCursor: "next",
          });
        }),
      },
    },
    design: { type: "figma", url: figmaUrl("5423-5249") },
  },
};

/** 녹음 기록을 불러오는 중이에요. 홈에 들어오자마자 잠깐 보여요 */
export const Loading: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        recordings: http.get(RECORDINGS_URL, async () => {
          await delay("infinite");
          return HttpResponse.json(recordingsResponse);
        }),
      },
    },
    design: { type: "figma", url: figmaUrl("5476-65003") },
  },
};

/** 녹음 기록을 불러오지 못했을 때예요. 다음 묶음을 받지 못해도 같은 모습이에요. 예: 서버가 잠시 답하지 못한 경우 */
export const LoadFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: noCurrentRecording,
        recordings: http.get(
          RECORDINGS_URL,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
    design: { type: "figma", url: figmaUrl("5423-5317") },
  },
};

/** 진행 중인 내 녹음도, 지난 녹음도 없을 때예요. 예: 워크스페이스를 막 만든 경우 */
export const Empty: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: noCurrentRecording,
        recordings: http.get(RECORDINGS_URL, () =>
          HttpResponse.json({ items: [], nextCursor: null }),
        ),
      },
    },
    design: { type: "figma", url: figmaUrl("5476-65117") },
  },
};
