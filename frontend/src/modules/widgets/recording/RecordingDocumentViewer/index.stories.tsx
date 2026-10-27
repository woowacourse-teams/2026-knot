import { RECORDING_DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/document";
import { RECORDING_TRANSCRIPT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcript";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import type { RecordingDocumentResponse } from "@api/mock/types/recordingDocument";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingDocumentViewer from ".";

const WORKSPACE_ID = 1;
const RECORDING_ID = recordingDocumentResponse.recordingId;
const RECORDING_DOCUMENT_REQUEST = `*${RECORDING_DOCUMENT_API_PATH(WORKSPACE_ID, RECORDING_ID)}`;
const RECORDING_TRANSCRIPT_REQUEST = `*${RECORDING_TRANSCRIPT_API_PATH(WORKSPACE_ID, RECORDING_ID)}`;
const FIGMA_FILE = "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot";

// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요.
// 높이는 녹음 문서 전체가 잘리지 않는 값이에요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 1200 } };

/** 녹음 문서 조회가 이 값을 덮어 답하게 해요. 나머지 값은 완료된 녹음 mock 그대로예요 */
const respondWithRecordingDocument = (
  overrides: Partial<RecordingDocumentResponse>,
) =>
  http.get(RECORDING_DOCUMENT_REQUEST, () =>
    HttpResponse.json({ ...recordingDocumentResponse, ...overrides }),
  );

/** 피그마 프레임 주소 */
const figma = (nodeId: string) => ({
  type: "figma" as const,
  url: `${FIGMA_FILE}?node-id=${nodeId}`,
});

/**
 * 녹음 하나를 다시 보는 녹음 문서 영역이에요. 위에서부터 녹음 제목, 녹음 음성, 회의 전체 요약, 이 회의에서 나온 문서가 한 열로 놓여요.
 * 전사는 이 영역이 아니라 화면 오른쪽 원문 패널에 있어요. 문서 탭 「녹음별」 탭에서 녹음 행을 누르면 이 화면이 열려요.
 * 어느 녹음을 보고 있는지는 GNB의 위치 표시가 `워크스페이스 이름 › 문서 › 녹음 제목`으로 알려 줘요.
 *
 * **동작 규칙**
 * - 녹음 제목은 AI가 전사를 바탕으로 만든 제목이에요. 아직 없거나 만들지 못했으면 「OO 님의 녹음」(녹음한 사람 이름)이에요. 제목 아래에는 녹음을 시작한 날과 녹음 길이가 있어요.
 * - 회의 전체 요약이 완료일 때만 제목 줄 오른쪽 끝에 「복사」가 있어요. 누르면 맨 위에 녹음 제목, 그 아래 요약을 마크다운으로 복사하고 3초 동안 「복사됨」으로 바뀌어요.
 * - 녹음의 처리 상태마다 보이는 것이 달라요.
 *   - 문서 정리 중: 정리 중 안내만 있어요. 정리가 끝나면 3초 안에 저절로 바뀌어요.
 *   - 전사 실패: 「녹음을 글로 옮기지 못했어요」. 다시 시도하면 전사부터 다시 해요.
 *   - 주제 분류 실패: 「문서를 만들지 못했어요」. 정리할 내용이 있는지 아직 몰라 제목 말고는 보여 주지 않아요. 다시 시도하면 주제 분류부터 다시 해요.
 *   - 정리할 내용 없음: 녹음 음성과 「변환된 음성 기록이 없어요.」가 있어요. 오류가 아니라 결과라서 경고색과 다시 시도가 없어요.
 *   - 완료 · 문서 생성 실패: 녹음 음성, 회의 전체 요약, 이 회의에서 나온 문서가 있어요. 문서 생성 실패는 이 회의에서 나온 문서의 마지막 카드로 알려요.
 * - 「다시 시도」는 서버가 다시 시도할 수 있다고 할 때만, 워크스페이스 멤버 누구에게나 보여요. 누르면 응답을 기다리는 동안 다시 누를 수 없고, 접수되면 정리 중으로 바뀌어요.
 * - 회의 전체 요약은 요약 상태마다 달라요. 만드는 중이면 만들고 있다고 알리고 3초마다 다시 확인해요. 실패하면 「요약을 만들지 못했어요」와, 다시 시도할 수 있으면 「다시 시도」가 있어요.
 *   요약 기능이 생기기 전에 끝난 녹음은 요약이 없는 까닭만 회색으로 한 줄 알려요. 요약 실패는 이 자리에서만 알리고 토스트를 띄우지 않아요.
 * - 녹음 문서를 불러오지 못하면 「녹음을 불러오지 못했어요」와 `다시 시도`가 있어요. 화면을 새로고침하지 않고 녹음 문서만 다시 불러와요.
 */
const meta = {
  title: "Recording/RecordingDocumentViewer",
  component: RecordingDocumentViewer,
  parameters: {
    design: [
      { name: "녹음 문서/기본 · 원문 자동 열림", ...figma("5373-3187") },
      { name: "녹음 문서 화면 11개", ...figma("5368-2224") },
      { name: "녹음 문서 정책", ...figma("5455-64380") },
      { name: "녹음 문서 부품", ...figma("5367-1713") },
    ],
  },
  decorators: [
    (Story) => (
      <MemoryRouter
        initialEntries={[
          getRouterPath({
            routeKey: "RECORDING_DOCUMENT",
            params: {
              workspaceId: String(WORKSPACE_ID),
              recordingId: String(RECORDING_ID),
            },
          }),
        ]}
      >
        <ToastProvider>
          <Routes>
            <Route path={PATH_ROUTE.RECORDING_DOCUMENT} element={<Story />} />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RecordingDocumentViewer>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 문서 정리와 요약을 모두 마친 녹음이에요. 녹음 음성, 회의 전체 요약, 이 회의에서 나온 문서가 모두 있어요 */
export const Default: Story = {};

/** 녹음 문서를 받기 전이에요. 제목 · 녹음 음성 · 요약 자리를 덩어리로 채워요 */
export const Loading: Story = {
  parameters: {
    design: figma("5373-3379"),
    msw: {
      handlers: [
        http.get(RECORDING_DOCUMENT_REQUEST, async () => {
          await delay("infinite");
          return HttpResponse.json(recordingDocumentResponse);
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음 문서를 불러오지 못했을 때예요. 예: 다른 워크스페이스의 녹음 주소로 들어왔을 때 */
export const LoadFailed: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(RECORDING_DOCUMENT_REQUEST, () =>
          HttpResponse.json(
            {
              code: "RECORDING_NOT_FOUND",
              message: "녹음 세션을 찾을 수 없습니다.",
            },
            { status: 404 },
          ),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 회의 전체 요약을 만드는 중이에요. 이 회의에서 나온 문서는 요약과 관계없이 보여요 */
export const SummaryDrafting: Story = {
  parameters: {
    design: figma("5377-3036"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          meetingSummary: {
            status: "RUNNING",
            content: null,
            retryable: false,
          },
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 회의 전체 요약을 만들지 못했고 다시 시도할 수 있을 때예요. 「다시 시도」로 요약만 다시 접수해요 */
export const SummaryFailed: Story = {
  parameters: {
    design: figma("5377-3163"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          meetingSummary: { status: "FAILED", content: null, retryable: true },
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 회의 전체 요약을 만들지 못했고 다시 시도할 수 없을 때예요. 「다시 시도」 없이 실패만 알려요 */
export const SummaryFailedNotRetryable: Story = {
  parameters: {
    msw: {
      handlers: [
        respondWithRecordingDocument({
          meetingSummary: { status: "FAILED", content: null, retryable: false },
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 회의 전체 요약 기능이 생기기 전에 끝난 녹음이에요. 실패나 만드는 중으로 보이지 않게 까닭만 알려요 */
export const NoSummary: Story = {
  parameters: {
    design: figma("5373-3952"),
    msw: {
      handlers: [respondWithRecordingDocument({ meetingSummary: null })],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음을 끝내고 아직 문서를 정리하는 중이에요. 예: 녹음 직후 문서 탭에서 그 녹음을 열었을 때 */
export const Organizing: Story = {
  parameters: {
    design: figma("5373-3450"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          processingStatus: "PROCESSING",
          meetingSummary: null,
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음을 글로 옮기지 못한 녹음이에요. 「다시 시도」로 전사부터 다시 접수해요 */
export const TranscriptionFailed: Story = {
  parameters: {
    design: figma("5373-3533"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          processingStatus: "TRANSCRIPTION_FAILED",
          retryable: true,
          meetingSummary: null,
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 전사는 됐지만 주제를 나누지 못한 녹음이에요. 「다시 시도」로 주제 분류부터 다시 접수해요 */
export const TopicFailed: Story = {
  parameters: {
    design: figma("5373-3625"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          processingStatus: "DOCUMENT_FAILED",
          retryable: true,
          retryableJobIds: [documentGenerationJobRetryResponse.jobId],
          meetingSummary: null,
        }),
        http.get(RECORDING_TRANSCRIPT_REQUEST, () =>
          HttpResponse.json(
            {
              code: "RECORDING_TRANSCRIPT_NOT_FOUND",
              message: "녹음 전사를 찾을 수 없습니다.",
            },
            { status: 404 },
          ),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 주제를 나눈 뒤 일부 문서를 만들지 못한 녹음이에요. 만든 문서 카드 뒤에 실패 카드가 하나 있어요 */
export const DocumentFailed: Story = {
  parameters: {
    design: figma("5373-3717"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          processingStatus: "DOCUMENT_FAILED",
          retryable: true,
          retryableJobIds: [documentGenerationJobRetryResponse.jobId],
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음에 정리할 내용이 없었을 때예요. 녹음 음성은 들을 수 있어요 */
export const NoContent: Story = {
  parameters: {
    design: figma("5373-3869"),
    msw: {
      handlers: [
        respondWithRecordingDocument({
          processingStatus: "NO_CONTENT",
          meetingSummary: null,
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};
