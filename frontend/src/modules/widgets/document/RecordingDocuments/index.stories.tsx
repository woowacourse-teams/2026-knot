import {
  recordingAudioSaveResponse,
  recordingDetailsResponse,
} from "@api/mock/responses/recording";
import { resetRecordingMockState } from "@api/mock/state/recording";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingDocuments from ".";

const WORKSPACE_ID = 1;
// 상태가 서로 다른 mock 녹음: 정리 중 · 내용 없음 · 문서 만들기 실패 · 전사 실패 · 다시 시도 횟수를 다 쓴 실패.
// 건너뛴 두 자리는 정리 완료와 일부만 만들어진 실패예요. 정리 완료는 문서로 이동만 하고, 일부만 만들어진 실패는 문서 만들기 실패와 화면이 같아요
const [
  organizingRecording,
  noContentRecording,
  failedRecording,
  transcriptionFailedRecording,
  ,
  ,
  retryExhaustedRecording,
] = recordingDetailsResponse;
// recordingDetailsResponse에 없는 녹음이라 기본 핸들러가 404로 답해요
const MISSING_RECORDING_ID = 999;
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const RECORDING_REQUEST =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId";
const AUDIO_SAVE_REQUEST = `${RECORDING_REQUEST}/audio-save`;
const TRANSCRIPTION_RETRY_REQUEST = `${RECORDING_REQUEST}/transcription/retry`;
// 끝냈지만 전사가 시작되기 전의 녹음이에요. PCM 조각 녹음은 이 동안 서버가 조각을 모아 저장해요
const awaitingAudioSaveDetail = {
  ...organizingRecording.detail,
  transcriptionAttemptId: null,
  transcriptionStatus: null,
  documentGeneration: null,
};
// 실패 작업의 보존 기한이 지나 서버가 작업 ID를 비운 문서 만들기 실패예요
const expiredFailedDetail = {
  ...failedRecording.detail,
  documentGeneration: {
    ...failedRecording.detail.documentGeneration,
    failedJobIds: [],
  },
};
// 응답을 덮는 스토리는 문서 페이지에서 따로 그려요. 실패 화면이 잘리지 않는 높이예요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 320 } };
// 녹음 상태는 전사 전으로 두고 저장 상태만 실패로 바꿔요
const failAudioSave = (failureCode: string) => ({
  recording: http.get(RECORDING_REQUEST, () =>
    HttpResponse.json(awaitingAudioSaveDetail),
  ),
  audioSave: http.get(AUDIO_SAVE_REQUEST, () =>
    HttpResponse.json({
      ...recordingAudioSaveResponse,
      audioSaveStatus: "FAILED",
      failureCode,
      canRetry: false,
      quality: null,
    }),
  ),
});

/**
 * 녹음을 끝낸 뒤 문서가 만들어질 때까지 머무는 정리 화면의 가운데 섹션이에요.
 * 주소의 녹음 상태에 따라 정리 중 · 문서로 만들 내용 없음 · 문서를 만들지 못함 · 불러오지 못함 가운데 하나를 보여 줘요.
 *
 * **동작 규칙**
 * - 녹음을 끝낸 뒤 오디오 업로드를 확인하는 동안, 전사하는 동안, 문서를 만드는 동안이 모두 정리 중이에요.
 *   정리 중에는 버튼 없이 3초마다 녹음 상태를 다시 확인해요. 결과가 정해지면 그 화면으로 바꾸고 더 확인하지 않아요.
 * - 문서로 만들 내용이 없었으면 오류가 아니라 결과라서 「다시 시도」 없이 「홈으로」만 둬요. 같은 녹음으로는 결과가 같기 때문이에요.
 * - 문서 만들기가 실패로 끝났으면 녹음을 보관했다고 알리고 「다시 시도」를 둬요.
 *   문서는 주제마다 따로 만들어서 한 녹음에서 여러 작업이 실패할 수 있어요. 「다시 시도」를 한 번 누르면 실패한 작업을 모두 다시 시도하고, 하나라도 접수되면 정리 중으로 돌아가요.
 *   요청이 모두 끝날 때까지 버튼을 누를 수 없고, 서버 오류로 실패하면 버튼이 다시 열려요.
 * - 문서가 일부만 만들어진 실패도 같은 화면이에요. 만들어진 문서로 보내지 않고, 「다시 시도」는 실패한 작업만 다시 시도해요.
 *   일부만 만들어진 경우만의 화면은 시안이 아직 없어서 따로 두지 않았어요. 만들어진 문서는 홈과 문서 탭에서 볼 수 있어요.
 * - 전사가 실패했으면 제목만 「녹음을 글로 옮기지 못했어요」로 다르고, 그림 · 설명 · 버튼은 문서 만들기 실패와 같아요.
 *   「다시 시도」는 실패 이유와 녹음 방식에 관계없이 전사부터 다시 접수하고, 접수되면 정리 중으로 돌아가요.
 *   409 · 404로 거절되면(보관 기간이 지남, 이미 전사 중, 전사 기능 꺼짐, 음성 없음) 녹음 상태를 다시 확인한 뒤 「홈으로」만 둬요. 서버에서 이미 전사 중이면 정리 중으로 바꿔요.
 *   서버 오류로 실패하면 콘솔에만 남기고 버튼을 그대로 둬요.
 * - 다시 시도할 작업이 남아 있지 않은 실패, 남은 작업의 다시 시도가 모두 거절된 경우는 같은 제목에 설명 없이 「홈으로」만 둬요.
 *   서버가 다시 시도할 수 없는 이유를 나눠 주지 않아 이유를 짐작해 쓰지 않아요.
 * - 다시 시도가 서버 오류나 연결 문제로 접수되지 않으면 「다시 시도」를 그대로 두고 오류 토스트 「다시 시도하지 못했어요. 잠시 후 다시 시도해 주세요.」로 알려요. 거절(409 · 404)이나 로그인이 풀린 경우에는 띄우지 않아요.
 * - 다시 시도가 409로 거절되면 녹음 상태를 다시 확인해요. 다른 곳에서 먼저 다시 시도해 서버에서는 이미 정리 중이거나 정리가 끝났을 수 있어서예요.
 *   그런 경우에는 그 상태의 화면으로 바꾸고, 그 작업이 또 실패하면 「다시 시도」를 다시 보여 줘요.
 * - PCM 조각 녹음은 끝낸 뒤 서버가 조각을 모아 저장해요. 전사가 시작되기 전에는 녹음 상태와 함께 저장 상태도 3초마다 확인하고, 저장 중에는 정리 중이에요.
 *   저장된 음성이 없어 실패했으면 문서로 만들 내용이 없을 때와 같은 화면이에요. 그 밖의 이유로 실패했거나 폐기됐으면 안내 없이 워크스페이스 홈으로 보내요.
 *   WebM 녹음은 저장 상태가 없다고(409) 답해 와서 한 번만 확인하고 더 확인하지 않아요.
 *   저장 상태를 확인하지 못하면(서버 · 네트워크 문제) 정리 중 대신 그 자리에 「녹음 저장 상태를 확인하지 못했어요」와 「다시 시도」를 둬요.
 *   이때도 3초마다 계속 확인하고, 저장 상태를 다시 받으면 정리 중으로 돌아가요. 「다시 시도」는 저장 상태만 바로 다시 확인해요.
 * - 녹음 상태를 불러오지 못하면 「문서를 불러오지 못했어요」와 「다시 시도」를 보여 줘요.
 *   이때는 저절로 다시 확인하지 않고, 「다시 시도」를 누르면 다시 확인해요.
 * - 정리 중에 녹음이 없어지거나 볼 수 없게 되어도 「문서를 불러오지 못했어요」로 바꾸고 더 확인하지 않아요.
 *   서버 · 네트워크 문제로 확인에 실패한 것이면 정리 중 화면을 유지하고 계속 확인해요.
 * - 주소의 번호는 숫자인지 확인하지 않고 그대로 요청해요. 서버가 잘못된 주소라고 답하면 「문서를 불러오지 못했어요」를 보여 줘요.
 * - 정리가 끝난 녹음은 그 녹음에서 나온 첫 문서로, 아직 녹음 중이거나 일시 정지한 녹음은 녹음 화면으로, 로그인이 풀렸으면 로그인 화면으로 보내요.
 *   정리가 끝났는데 그 녹음에서 나온 문서가 없거나 문서 목록을 불러오지 못하면 워크스페이스 홈으로 보내요.
 */
const meta = {
  title: "Document/RecordingDocuments",
  component: RecordingDocuments,
  parameters: {
    recordingId: organizingRecording.recordingId,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35460",
    },
  },
  // 다시 시도한 기록은 mock 상태에 남으므로, 스토리마다 기본 응답으로 되돌려요
  loaders: [
    () => {
      resetRecordingMockState();
    },
  ],
  decorators: [
    (Story, { parameters }) => (
      <MemoryRouter
        initialEntries={[
          getRouterPath({
            routeKey: "RECORDING_DOCUMENTS",
            params: {
              workspaceId: String(WORKSPACE_ID),
              recordingId: String(parameters.recordingId),
            },
          }),
        ]}
      >
        <ToastProvider>
          <Routes>
            <Route path={PATH_ROUTE.RECORDING_DOCUMENTS} element={<Story />} />
            <Route
              path={PATH_ROUTE.WORKSPACE_HOME}
              element={<p>워크스페이스 홈으로 이동했어요.</p>}
            />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RecordingDocuments>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 녹음을 끝내고 문서를 정리하는 동안의 모습이에요. 버튼 없이 기다리게 하고, 3초마다 상태를 다시 확인해요. */
export const Default: Story = {};

/** 대화가 너무 짧거나 정리할 논의가 없어 문서를 만들지 않았을 때예요. 오류가 아니라 결과라서 「다시 시도」 없이 「홈으로」만 있어요. */
export const NothingToOrganize: Story = {
  parameters: {
    recordingId: noContentRecording.recordingId,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2262-33302",
    },
  },
};

/** 문서 만들기가 실패로 끝났을 때예요. 녹음은 보관돼 있어 「다시 시도」를 둬요. 누르면 정리 중 모습으로 돌아가요. */
export const DocumentFailed: Story = {
  parameters: {
    recordingId: failedRecording.recordingId,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35662",
    },
  },
};

/** 전사가 실패했을 때예요. 제목만 「녹음을 글로 옮기지 못했어요」로 다르고 나머지는 문서 만들기 실패와 같아요. 「다시 시도」를 누르면 전사부터 다시 접수해 정리 중 모습으로 돌아가요. */
export const TranscriptionFailed: Story = {
  parameters: {
    recordingId: transcriptionFailedRecording.recordingId,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4524-43475",
    },
  },
};

/** 다시 시도할 작업이 없는 실패일 때예요. 예: 실패한 작업의 보존 기한이 지나 서버가 작업 ID를 비운 경우. 설명 없이 「홈으로」만 있어요. 붙인 피그마 프레임은 「다시 시도」가 있는 모습이에요. */
export const DocumentFailedWithoutRetry: Story = {
  parameters: {
    recordingId: failedRecording.recordingId,
    msw: {
      handlers: {
        recording: http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(expiredFailedDetail),
        ),
      },
    },
    docs: ISOLATED_DOCS,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35662",
    },
  },
};

/** 남은 실패 작업의 다시 시도를 서버가 거절했을 때예요. 예: 다시 시도할 수 있는 횟수를 다 쓴 작업. 「다시 시도」를 누르면 설명과 버튼이 사라지고 「홈으로」만 남아요. */
export const RetryRejected: Story = {
  parameters: {
    recordingId: retryExhaustedRecording.recordingId,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35662",
    },
  },
};

/** 「다시 시도」가 서버 오류로 접수되지 않았을 때예요. 「다시 시도」를 누르면 버튼은 그대로 남고 오류 토스트가 떠요. 이 스토리에서는 다시 눌러도 계속 실패해요. */
export const RetryRequestFailed: Story = {
  parameters: {
    recordingId: failedRecording.recordingId,
    msw: {
      handlers: {
        documentGenerationRetry: http.post(
          "*/api/v1/workspaces/:workspaceId/document-generation-jobs/:jobId/retry",
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
    docs: ISOLATED_DOCS,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4826-33517",
    },
  },
};

/** 전사 다시 시도를 서버가 거절했을 때예요. 예: 삭제됐거나 사용할 수 없는 녹음(409 AUDIO_UPLOAD_NOT_COMPLETED). 「다시 시도」를 누르면 설명과 버튼이 사라지고 「홈으로」만 남아요. 붙인 피그마 프레임은 「다시 시도」가 있는 모습이에요. */
export const TranscriptionRetryRejected: Story = {
  parameters: {
    recordingId: transcriptionFailedRecording.recordingId,
    msw: {
      handlers: {
        transcriptionRetry: http.post(TRANSCRIPTION_RETRY_REQUEST, () =>
          HttpResponse.json(
            {
              code: "AUDIO_UPLOAD_NOT_COMPLETED",
              message: "예약한 오디오 파일이 저장소에 올라가지 않았습니다",
            },
            { status: 409 },
          ),
        ),
      },
    },
    docs: ISOLATED_DOCS,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4524-43475",
    },
  },
};

/** PCM 조각 녹음을 끝냈는데 저장된 음성이 없어 저장이 실패했을 때예요(AUDIO_NO_CONTENT). 문서로 만들 내용이 없을 때와 같은 화면이에요. */
export const AudioSaveNoContent: Story = {
  parameters: {
    msw: { handlers: failAudioSave("AUDIO_NO_CONTENT") },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2262-33302",
    },
  },
};

/** PCM 조각 녹음의 저장이 그 밖의 이유로 끝내 실패했을 때예요. 업로드 실패와 같이 안내 없이 실패 이유를 콘솔에 남기고 워크스페이스 홈으로 보내요. */
export const AudioSaveFailed: Story = {
  parameters: {
    msw: { handlers: failAudioSave("AUDIO_STORAGE_UNAVAILABLE") },
  },
};

/**
 * PCM 조각 녹음을 끝낸 뒤 저장 상태를 확인하지 못할 때예요. 서버 오류와 네트워크 문제가 모두 이 모습이에요.
 * 정리 중 화면이 끝없이 이어지지 않게, 그 자리에 확인하지 못했다는 안내와 「다시 시도」를 둬요. 이 스토리에서는 「다시 시도」를 눌러도 계속 실패해요.
 */
export const AudioSaveCheckFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        recording: http.get(RECORDING_REQUEST, () =>
          HttpResponse.json(awaitingAudioSaveDetail),
        ),
        audioSave: http.get(
          AUDIO_SAVE_REQUEST,
          () => new HttpResponse(null, { status: 503 }),
        ),
      },
    },
  },
};

/** 녹음 상태를 불러오지 못했을 때예요. 없는 녹음, 볼 수 없는 녹음, 서버 오류, 네트워크 문제가 모두 이 모습이에요. 이 스토리에서는 「다시 시도」를 눌러도 계속 실패해요. */
export const LoadFailed: Story = {
  parameters: {
    recordingId: MISSING_RECORDING_ID,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3669-13842",
    },
  },
};
