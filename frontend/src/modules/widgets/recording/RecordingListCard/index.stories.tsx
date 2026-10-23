import { CURRENT_RECORDING_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import {
  currentRecordingResponse,
  recordingDetailsResponse,
} from "@api/mock/responses/recording";
import {
  findRecordingDetail,
  resetRecordingMockState,
} from "@api/mock/state/recording";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingListCard from ".";

const WORKSPACE_ID = 1;
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: String(WORKSPACE_ID) },
});
const CURRENT_URL = `*${CURRENT_RECORDING_API_PATH(WORKSPACE_ID)}`;
// 녹음 상세 mock에서 전사가 실패한 녹음. 전사 다시 시도 mock은 이 녹음만 접수해요
const TRANSCRIPTION_FAILED_RECORDING_ID = recordingDetailsResponse.find(
  ({ detail }) => detail.transcriptionStatus === "FAILED",
)!.recordingId;
// 녹음 상세 mock에서 문서 만들기가 실패한 녹음. 실패 작업이 다시 시도 mock 응답의 작업이에요
const DOCUMENT_FAILED_RECORDING_ID = recordingDetailsResponse.find(
  ({ detail }) =>
    detail.documentGeneration?.status === "FAILED" &&
    detail.documentGeneration.failedJobIds[0] ===
      documentGenerationJobRetryResponse.jobId,
)!.recordingId;

/**
 * 정리에 실패한 녹음의 현재 녹음 응답. 다시 시도를 접수하면(mock 상태의 녹음 상세가 전사 중 · 대기로 바뀌면)
 * 서버처럼 「문서 정리 중」으로 답해, 스토리에서 다시 시도 흐름을 끝까지 볼 수 있어요
 */
const respondFailedRecording = (
  status: "TRANSCRIPTION_FAILED" | "DOCUMENT_FAILED",
) =>
  http.get(CURRENT_URL, () => {
    const recordingId =
      status === "TRANSCRIPTION_FAILED"
        ? TRANSCRIPTION_FAILED_RECORDING_ID
        : DOCUMENT_FAILED_RECORDING_ID;
    const detail = findRecordingDetail(recordingId);
    const isRetried =
      detail?.transcriptionStatus === "PROCESSING" ||
      detail?.documentGeneration?.status === "QUEUED";

    return HttpResponse.json({
      ...currentRecordingResponse,
      recordingId,
      status: isRetried ? "PROCESSING" : status,
      retryable: true,
    });
  });

// 응답이나 이 탭의 녹음을 바꾸는 스토리는 문서 페이지에서 따로 그려야 다른 스토리와 섞이지 않아요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 280 } };

/** 이 탭의 녹음을 처음 상태로 되돌려요. 녹음은 전역 저장소에 있어 스토리끼리 이어지기 때문이에요. */
const resetRecording = () => {
  useRecordingStore.getState().discardRecording();
};

/**
 * 워크스페이스 홈의 「진행 중인 녹음」 카드예요. 내 가장 최근 녹음 하나의 진행 상태만 보여 줘요.
 *
 * **동작 규칙**
 * - 다른 사람의 녹음은 보이지 않고, 개수도 표시하지 않아요. 함께 녹음·여러 녹음은 이후 버전이에요.
 * - 녹음이 있으면 한 칸에 상태(점 + 상태 · 녹음한 시간), 녹음 이름(「{내 닉네임} 님의 녹음」), 안내 한 줄을 보여 줘요.
 * - 녹음 중이면 빨간 점·빨간 글자에 시간이 1초마다 늘어나고, 일시정지면 회색 점·회색 글자에 시간이 멈춰요.
 * - 이 탭에서 시작한 녹음이면 이 탭의 상태와 시간을 보여 주고 「녹음 화면으로」 버튼으로 녹음 화면에 가요.
 * - 다른 탭에서 시작한 녹음이면 서버가 알려 준 상태와 시간을 보여 주고 버튼은 숨겨요. 녹음 화면은 녹음을 시작한 탭에서만 열 수 있기 때문이에요.
 * - 녹음이 없으면 빈 상태 문구로 독의 마이크에서 녹음을 시작하도록 안내해요.
 * - 녹음 상태를 불러오지 못하면 칸 자리에 실패 안내와 「다시 시도」를 둬요. 「다시 시도」는 이 카드의 녹음만 다시 불러와요. 표시만 실패한 것이라 녹음은 멈추지 않고, 이 탭에서 시작한 녹음은 그대로 보여요.
 * - 녹음이나 내 닉네임을 불러오는 동안에는 칸 자리를 뼈대로 채워 카드 높이가 흔들리지 않게 해요. 카드 제목은 그대로 보여요.
 * - 녹음을 끝내고 문서를 정리하는 중이면 청록 점·청록 글자로 「문서 정리 중」을 보여 주고 시간은 두지 않아요. 「보기」로 그 녹음의 문서 정리 화면에 가요. 정리 화면은 어느 탭에서나 열 수 있어 버튼을 숨기지 않아요.
 * - 정리하는 동안에는 3초마다 다시 불러와, 정리가 끝나면 녹음이 없는 모습으로 돌아가요.
 * - 전사(녹음을 글로 옮기기)나 문서 생성이 끝내 실패하면 어두운 빨간 점·글자로 실패한 단계의 제목(「녹음을 글로 옮기지 못했어요」 · 「문서를 만들지 못했어요」)과 보관 안내를 보여 주고, 오른쪽에 「다시 시도」를 둬요.
 * - 「다시 시도」는 전사 실패면 전사를, 문서 생성 실패면 그 녹음의 실패한 문서 작업을 모두 다시 접수하고, 접수되면 「문서 정리 중」으로 돌아가요. 응답을 기다리는 동안 버튼은 로딩이에요.
 * - 다시 시도가 거절되면(더 시도할 수 없음) 알리지 않고 녹음을 다시 불러오고, 네트워크·서버 오류면 오류 토스트로 알려요. 다시 시도할 수 없는 실패(`retryable=false`)는 아직 시안이 없어 녹음이 없는 모습이에요.
 * - 홈에 들어올 때와 다른 창에서 돌아올 때 녹음을 다시 불러와요.
 */
const meta = {
  title: "Recording/RecordingListCard",
  component: RecordingListCard,
  // 다시 시도가 mock의 다시 시도 기록을 바꾸므로 스토리를 옮길 때마다 되돌려요
  loaders: [
    () => {
      resetRecordingMockState();
    },
  ],
  parameters: {
    layout: "centered",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-13368",
    },
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
} satisfies Meta<typeof RecordingListCard>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 다른 탭에서 시작한 녹음이 이어지고 있을 때예요. 예: 녹음을 켜 둔 채 새 탭으로 홈을 연 경우 */
export const Default: Story = {};

/** 이 탭에서 시작한 녹음이 이어지고 있을 때예요. 예: 독의 마이크로 녹음을 시작하고 홈으로 돌아온 경우 */
export const RecordingInThisTab: Story = {
  beforeEach: () => {
    useRecordingStore.setState({
      status: "recording",
      session: {
        workspaceId: WORKSPACE_ID,
        recordingId: currentRecordingResponse.recordingId,
        audioMode: "FINAL_WEBM_V1",
      },
      accumulatedMs: (12 * 60 + 48) * 1000,
      resumedAt: Date.now(),
    });
  },
  parameters: {
    // 녹음은 전역 저장소에 있어, 문서 페이지에서 함께 그리면 다른 스토리도 이 탭의 녹음으로 보여요
    docs: ISOLATED_DOCS,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-13269",
    },
  },
};

/** 녹음을 잠시 멈췄을 때예요. 예: 회의 중 쉬는 시간에 일시정지하고 다른 탭으로 홈을 본 경우 */
export const Paused: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: http.get(CURRENT_URL, () =>
          HttpResponse.json({ ...currentRecordingResponse, status: "PAUSED" }),
        ),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2377-35853",
    },
  },
};

/** 녹음을 끝낸 뒤 문서를 정리하는 중일 때예요. 예: 녹음을 끝내고 정리 화면에서 홈으로 나온 경우 */
export const Organizing: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: http.get(CURRENT_URL, () =>
          HttpResponse.json({
            ...currentRecordingResponse,
            status: "PROCESSING",
          }),
        ),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1993-7173",
    },
  },
};

/**
 * 녹음을 끝낸 뒤 전사(녹음을 글로 옮기기)가 끝내 실패했을 때예요. 「다시 시도」를 누르면 전사를 다시 접수하고 문서 정리 중으로 돌아가요.
 * 녹음 ID는 녹음 상세 mock의 전사 실패 녹음이라 다시 시도가 실제로 접수되고, 접수 뒤에는 정리 중으로 바뀌어요
 */
export const TranscriptionFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: respondFailedRecording("TRANSCRIPTION_FAILED"),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4524-43789",
    },
  },
};

/**
 * 전사는 됐지만 문서 생성이 끝내 실패했을 때예요. 「다시 시도」를 누르면 그 녹음의 실패한 문서 작업을 모두 다시 접수해요.
 * 녹음 ID는 녹음 상세 mock의 문서 생성 실패 녹음이라 다시 시도가 실제로 접수되고, 접수 뒤에는 정리 중으로 바뀌어요
 */
export const DocumentFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: respondFailedRecording("DOCUMENT_FAILED"),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2067-26428",
    },
  },
};

/** 진행 중인 녹음이 없을 때예요. 홈에 처음 들어왔거나 녹음을 끝낸 문서 정리가 끝난 뒤의 모습이에요. */
export const Empty: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: http.get(
          CURRENT_URL,
          () => new HttpResponse(null, { status: 204 }),
        ),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-13367",
    },
  },
};

/** 녹음을 불러오는 중이에요. 홈에 들어오자마자 잠깐 보여요. */
export const Loading: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: http.get(CURRENT_URL, async () => {
          await delay("infinite");
          return new HttpResponse(null, { status: 204 });
        }),
      },
    },
  },
};

/** 녹음 상태를 불러오지 못했을 때예요. 예: 홈에 들어왔는데 서버가 잠시 답하지 못한 경우 */
export const LoadFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        currentRecording: http.get(
          CURRENT_URL,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3892-15440",
    },
  },
};
