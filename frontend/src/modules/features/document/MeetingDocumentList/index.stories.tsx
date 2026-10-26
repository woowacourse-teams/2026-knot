import { DOCUMENTS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents";
import { RECORDING_DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/document";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import type { DocumentsResponse } from "@api/mock/types/document";
import type { RecordingDocumentResponse } from "@api/mock/types/recordingDocument";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import MeetingDocumentList from ".";

const WORKSPACE_ID = 1;
const RECORDING_ID = recordingDocumentResponse.recordingId;
const RECORDING_DOCUMENT_REQUEST = `*${RECORDING_DOCUMENT_API_PATH(WORKSPACE_ID, RECORDING_ID)}`;
const DOCUMENTS_REQUEST = `*${DOCUMENTS_API_PATH(WORKSPACE_ID)}`;

// 응답을 바꾸는 스토리만 따로 그려 문서 페이지에서 다른 스토리의 응답과 섞이지 않게 해요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 320 } };

/** 문서 생성이 실패한 녹음으로 답해요. 다시 시도할 수 있는지는 덮어 정해요 */
const respondWithDocumentFailed = (
  overrides: Partial<RecordingDocumentResponse> = {},
) =>
  http.get(RECORDING_DOCUMENT_REQUEST, () =>
    HttpResponse.json({
      ...recordingDocumentResponse,
      processingStatus: "DOCUMENT_FAILED",
      retryable: true,
      retryableJobIds: [documentGenerationJobRetryResponse.jobId],
      ...overrides,
    }),
  );

/**
 * 녹음 문서의 「이 회의에서 나온 문서」예요. 그 녹음에서 나온 주제별 문서를 제목과 한 줄 요약의 카드로 두 열에 보여 주고, 카드를 누르면 그 문서의 문서 보기로 가요.
 * 녹음 문서에서 회의 전체 요약 아래에 놓여요. 완료된 녹음과 문서 생성이 실패한 녹음에만 있어요.
 *
 * **동작 규칙**
 * - 보여 주는 문서와 순서는 문서 탭 「녹음별」 탭에서 그 녹음 아래에 보이는 문서와 같아요. 만든 순서대로예요.
 * - 주제 · 날짜 · 녹음 길이 · 내 확인 상태는 보여 주지 않아요. 한 줄 요약이 없는 문서는 제목만 있어요. 제목과 요약은 한 줄을 넘으면 말줄임해요.
 * - 「이 회의에서 나온 문서」 옆에 만들어진 문서 수가 있어요. 실패 카드는 세지 않고, 만들어진 문서가 없으면 수를 두지 않아요.
 * - 문서 생성이 실패한 녹음이면 문서 카드 뒤에 「문서를 만들지 못했어요」 카드가 하나 있어요. 실패한 문서 작업이 여러 개여도 카드는 하나예요.
 *   다시 시도할 수 있으면 그 카드의 「다시 시도」가 실패한 문서 작업을 모두 다시 접수하고, 녹음 문서는 정리 중으로 바뀌어요.
 * - 목록을 불러오지 못하면 이 자리에만 안내와 「다시 시도」가 있어요. 녹음 제목 · 요약 · 전사 · 녹음 음성은 그대로 읽을 수 있어요.
 * - 좁은 화면에서는 카드를 한 열로 쌓아요.
 */
const meta = {
  title: "Document/MeetingDocumentList",
  component: MeetingDocumentList,
  parameters: {
    design: [
      {
        name: "Card/RelatedDoc",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5374-1752",
      },
      {
        name: "녹음 문서/기본 · 원문 자동 열림",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5373-3187",
      },
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
          {/* 녹음 문서처럼 720px 열 안에 놓아요 */}
          <div style={{ maxWidth: "45rem" }}>
            <Routes>
              <Route path={PATH_ROUTE.RECORDING_DOCUMENT} element={<Story />} />
            </Routes>
          </div>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof MeetingDocumentList>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 완료된 녹음이에요. 그 녹음에서 나온 문서가 카드로 있어요 */
export const Default: Story = {};

/** 일부 문서를 만들지 못한 녹음이에요. 만든 문서 카드 뒤에 실패 카드와 「다시 시도」가 있어요 */
export const DocumentFailed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5373-3717",
    },
    msw: { handlers: [respondWithDocumentFailed()] },
    docs: ISOLATED_DOCS,
  },
};

/** 다시 시도할 수 없는 문서 생성 실패예요. 실패 카드에 「다시 시도」가 없어요 */
export const DocumentFailedNotRetryable: Story = {
  parameters: {
    msw: {
      handlers: [
        respondWithDocumentFailed({ retryable: false, retryableJobIds: [] }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 문서를 하나도 만들지 못한 녹음이에요. 머리와 실패 카드만 있고 문서 수는 없어요 */
export const AllDocumentsFailed: Story = {
  parameters: {
    msw: {
      handlers: [
        respondWithDocumentFailed(),
        http.get(DOCUMENTS_REQUEST, () =>
          HttpResponse.json({
            topics: [],
            items: [],
            nextCursor: null,
          } satisfies DocumentsResponse),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 목록을 불러오지 못했을 때예요. 이 자리에만 안내와 「다시 시도」가 있어요 */
export const LoadFailed: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(
          DOCUMENTS_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};
