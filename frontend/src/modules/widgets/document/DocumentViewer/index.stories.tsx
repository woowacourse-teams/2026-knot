import { DOCUMENTS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents";
import { DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { DOCUMENT_CONFIRMATIONS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations";
import { DOCUMENT_MY_CONFIRMATION_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations/me";
import {
  documentDetailsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import { resetDocumentMockState } from "@api/mock/state/document";
import type { DocumentsResponse } from "@api/mock/types/document";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { spyOn, userEvent, within } from "storybook/test";

import DocumentViewer from ".";

const WORKSPACE_ID = 1;
const [documentResponse, confirmedDocumentResponse] = documentDetailsResponse;
const DOCUMENT_REQUEST = `*${DOCUMENT_API_PATH(WORKSPACE_ID, documentResponse.id)}`;
const DOCUMENTS_REQUEST = `*${DOCUMENTS_API_PATH(WORKSPACE_ID)}`;
const CONFIRMATIONS_REQUEST = `*${DOCUMENT_CONFIRMATIONS_API_PATH(WORKSPACE_ID, documentResponse.id)}`;
const MY_CONFIRMATION_REQUEST = `*${DOCUMENT_MY_CONFIRMATION_API_PATH(WORKSPACE_ID, documentResponse.id)}`;

// 문서가 하나뿐인 녹음처럼, 같은 녹음의 문서 목록에 이 문서만 담아요
const onlyDocumentItems = documentsResponse.items.filter(
  ({ id }) => id === documentResponse.id,
);
const singleDocumentRecordingResponse = {
  topics: onlyDocumentItems.map(({ topic }) => ({ topic, documentCount: 1 })),
  items: onlyDocumentItems,
  nextCursor: null,
} satisfies DocumentsResponse;

interface GetDocumentEntryParams {
  documentId: number;
  /** 녹음 직후 정리 화면에서 넘어왔는지. 그러면 주소에 `?from=recording`이 붙어요 */
  isFromRecording: boolean;
}

/** 이 워크스페이스에서 문서를 여는 주소예요. */
const getDocumentEntry = ({
  documentId,
  isFromRecording,
}: GetDocumentEntryParams) => {
  const path = getRouterPath({
    routeKey: "DOCUMENT",
    params: {
      workspaceId: String(WORKSPACE_ID),
      documentId: String(documentId),
    },
  });

  return isFromRecording
    ? `${path}?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`
    : path;
};

/** 녹음 직후 정리 화면에서 넘어온 문서로 열어요. 이때만 스테퍼가 보여요 */
const FROM_RECORDING = { isFromRecording: true };

// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 마지막 스토리의 응답이 모든 스토리에 적용돼요.
// 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요. 높이는 문서 전체가 잘리지 않는 값이에요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 760 } };

/**
 * 문서 하나를 열었을 때 경로 · 복사 버튼 · 제목 · 날짜 · 녹음 길이 · 확인 수 · 본문을 보여 주는 문서 보기 영역이에요.
 * 본문 아래 줄에는 확인 버튼을 놓고, 녹음 직후 정리 화면에서 넘어왔을 때만 같은 녹음의 문서를 넘기는 스테퍼를 함께 놓아요.
 * 문서 탭 · 사이드바 · 홈 카드 · 탐색의 찾은 기록 카드에서 문서를 누르거나, 녹음을 끝내고 정리가 끝나면 이 화면이 열려요.
 *
 * **동작 규칙**
 * - 주소의 문서 번호로 문서를 불러와요. 번호가 숫자인지는 확인하지 않고 그대로 요청하고, 잘못된 주소인지는 서버가 판단해요.
 *   같은 기준을 화면에도 두면 기준이 두 곳에 생기기 때문이에요.
 * - 잘못된 주소 · 없는 문서 · 볼 수 없는 문서일 때, 서버 오류나 네트워크 문제로 못 불러올 때 모두 「문서를 불러오지 못했어요」와 `다시 시도`를 보여 줘요.
 *   `다시 시도`를 누르면 화면을 새로고침하지 않고 문서만 다시 불러와요. 새로고침하면 진행 중인 녹음이 사라지기 때문이에요.
 * - 다른 탭에 다녀오면 문서를 다시 받아요. 이때 서버 오류나 네트워크 문제로 못 받으면 보던 문서를 그대로 둬요.
 * - 문서를 불러올 때나 「문서를 확인했어요」를 누를 때 로그인이 풀렸으면 로그인 화면으로 보내요.
 * - 결정이 없는 회의 문서는 「이번 회의에서 정해진 내용은 없어요.」 문장을 흐리게 보여 줘요.
 * - 「복사」를 누르면 제목을 `#` 제목으로 본문 앞에 붙여 마크다운으로 복사하고, 3초 동안 「복사됨」으로 바뀌어요. 다른 문서로 넘어가면 「복사됨」은 남지 않아요.
 * - 확인 수에 포인터를 올리면 확인한 사람이 위에, 아직 확인하지 않은 사람이 아래에 흐리게 보여요. 확인하지 않고 나간 사람은 빼요.
 *   내 이름 옆에는 「(나)」를 붙여요. 이름이 길어 줄어들어도 「(나)」는 남아요.
 *   목록은 포인터를 올릴 때가 아니라 문서를 받자마자 받아 둬요. 올린 뒤에 받으면 기다림이 눈에 띄기 때문이에요.
 *   목록을 받지 못했으면 「목록을 불러오지 못했어요」를 보여 주고, 포인터를 다시 올리면 다시 받아요.
 * - 본문 아래 줄 왼쪽의 스테퍼는 같은 녹음에서 나온 문서 가운데 지금 문서가 몇 번째인지 보여 줘요. 한 회의에서 주제마다 문서가 따로 만들어지기 때문이에요.
 *   녹음 직후 정리 화면에서 넘어와 주소에 `?from=recording`이 있을 때만 보여요. 녹음한 문서를 이어서 확인할 때만 쓰고, 문서 탭 · 사이드바 · 홈 카드 · 탐색에서 연 저장 문서에는 두지 않아요.
 *   순서는 서버가 준 순서 그대로예요. 문서가 하나뿐이어도 `1 / 1`로 보여 주고, 첫 문서에서는 이전을, 마지막 문서에서는 다음을 누를 수 없어요.
 *   이전 · 다음을 누르면 그 문서의 주소로 옮겨 가고, 주소의 `?from=recording`도 이어 붙여 스테퍼가 남아요. 방문 기록에 쌓이므로 뒤로 가기를 누르면 앞 문서로 돌아와요.
 *   같은 녹음의 문서 목록을 받기 전이거나 받지 못하면 스테퍼만 그리지 않고, 문서는 그대로 보여 줘요.
 * - 본문 아래 줄 오른쪽의 확인 버튼은 확인 대상에게만 보여요. 문서가 만들어질 때의 워크스페이스 멤버가 확인 대상이고, 그 뒤에 들어온 사람은 읽기만 해요.
 *   아직 확인하지 않았으면 「문서를 확인했어요」가 보여요. 누르면 응답을 기다리는 동안 누를 수 없고, 응답이 오면 누를 수 없는 「확인했어요」로 바뀌며 확인 수가 1 늘어요. 확인은 되돌릴 수 없어요.
 *   확인 대상이 아니라는 응답을 받으면 문서를 다시 불러와 버튼을 없애요. 그 밖의 실패에서는 버튼이 그대로 남아 다시 누를 수 있어요.
 */
const meta = {
  title: "Document/DocumentViewer",
  component: DocumentViewer,
  parameters: {
    documentId: documentResponse.id,
    isFromRecording: false,
    design: [
      {
        name: "문서/확인한 사람 보기",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36058",
      },
      {
        name: "문서/녹음 직후 · 결정 없음",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2259-32252",
      },
      {
        name: "Confirm · 문서 확인",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-14787",
      },
    ],
  },
  // 「문서를 확인했어요」를 누른 기록이 다른 스토리에 남지 않게, 스토리를 열 때마다 mock 상태를 처음으로 되돌려요
  loaders: [
    () => {
      resetDocumentMockState();
    },
  ],
  decorators: [
    (Story, { parameters }) => (
      <MemoryRouter
        initialEntries={[
          getDocumentEntry({
            documentId: parameters.documentId,
            isFromRecording: parameters.isFromRecording,
          }),
        ]}
      >
        <Routes>
          <Route path={PATH_ROUTE.DOCUMENT} element={<Story />} />
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof DocumentViewer>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 문서 탭에서 저장된 문서를 연 기본 모습이에요. 아직 확인하지 않은 문서라서 본문 아래 줄의 오른쪽에 「문서를 확인했어요」만 있어요.
 * 버튼을 누르면 「확인했어요」로 바뀌고 확인 수가 「3명 확인했어요」로 늘어요.
 */
export const Default: Story = {};

/**
 * 녹음을 끝내고 정리가 끝나 정리 화면에서 넘어온 문서예요. 주소에 `?from=recording`이 붙어 있어요.
 * 본문 아래 줄 왼쪽에 스테퍼가 있고, 같은 녹음의 두 문서 중 두 번째라서 `2 / 2`예요.
 * 이전을 누르면 같은 녹음의 앞 문서(AlreadyConfirmed가 여는 문서)로 넘어가고, 그 문서에서도 스테퍼가 남아 `1 / 2`가 돼요.
 */
export const FromRecording: Story = {
  parameters: FROM_RECORDING,
};

/**
 * 이미 확인한 문서를 열었을 때예요. 아래 줄의 오른쪽에 누를 수 없는 「확인했어요」가 있어요.
 * 결정이 없는 회의 문서라서 「이번 회의에서 정해진 내용은 없어요.」 문장이 흐리게 보여요.
 */
export const AlreadyConfirmed: Story = {
  parameters: { documentId: confirmedDocumentResponse.id },
};

/**
 * 확인 대상이 아닌 사람이 정리 화면에서 넘어온 문서를 열었을 때예요. 예를 들면 문서가 만들어진 뒤에 워크스페이스에 들어온 사람이에요.
 * 아래 줄에는 스테퍼만 있고 확인 버튼은 없어요. 문서 탭에서 열었다면 스테퍼도 없어 아래 줄이 통째로 빠져요.
 */
export const ConfirmationNotRequired: Story = {
  parameters: {
    ...FROM_RECORDING,
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        document: http.get(DOCUMENT_REQUEST, () =>
          HttpResponse.json({
            ...documentResponse,
            myConfirmationState: "NOT_REQUIRED",
          }),
        ),
      },
    },
  },
};

/** 「복사」를 누른 직후예요. 제목과 본문을 마크다운으로 복사하고, 버튼이 3초 동안 「복사됨」으로 바뀐 뒤 「복사」로 돌아와요. */
export const Copied: Story = {
  // 스토리북 화면은 클립보드 쓰기 권한이 없을 수 있어서, 클립보드를 대신하고 스토리가 끝나면 되돌려요
  beforeEach: () => {
    const clipboardSpy = spyOn(
      navigator.clipboard,
      "writeText",
    ).mockResolvedValue();

    return () => {
      clipboardSpy.mockRestore();
    };
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // 문서를 받기 전에는 복사 버튼이 없어 보일 때까지 기다려요
    const copyButton = await canvas.findByRole("button", { name: "복사" });

    await userEvent.click(copyButton);
  },
};

/**
 * 「문서를 확인했어요」를 누르고 응답을 기다리는 동안이에요. 버튼에 스피너가 돌고 다시 누를 수 없어요.
 * 이 스토리에서는 응답이 오지 않아서 계속 이 모습이에요.
 */
export const Confirming: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        myConfirmation: http.put(MY_CONFIRMATION_REQUEST, async () => {
          // 응답을 끝내 보내지 않아, 누른 뒤 기다리는 모습으로 머물러요
          await delay("infinite");
          return new HttpResponse(null);
        }),
      },
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // 문서를 받기 전에는 확인 버튼이 없어 보일 때까지 기다려요
    const confirmButton = await canvas.findByRole("button", {
      name: "문서를 확인했어요",
    });

    await userEvent.click(confirmButton);
  },
};

/**
 * 확인한 사람 목록을 받지 못했을 때예요. 확인 수는 문서와 함께 받으므로 그대로 보여요.
 * 확인 수에 포인터를 올리면 「목록을 불러오지 못했어요」가 떠요. 이 스토리에서는 포인터를 다시 올려도 계속 실패해요.
 */
export const MembersLoadFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        confirmations: http.get(
          CONFIRMATIONS_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};

/**
 * 주제가 하나뿐인 회의처럼 녹음에서 문서가 하나만 나왔을 때예요. 정리 화면에서 넘어온 문서예요.
 * 스테퍼를 숨기지 않고 `1 / 1`로 보여 주며, 이전과 다음을 모두 누를 수 없어요.
 */
export const SingleDocumentRecording: Story = {
  parameters: {
    ...FROM_RECORDING,
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        documents: http.get(DOCUMENTS_REQUEST, () =>
          HttpResponse.json(singleDocumentRecordingResponse),
        ),
      },
    },
  },
};

/**
 * 정리 화면에서 넘어왔는데 같은 녹음의 문서 목록을 받지 못했을 때예요. 스테퍼만 빠지고 문서와 확인 버튼은 그대로 보여요.
 * 목록을 받는 중에도 같은 모습이에요.
 */
export const RecordingDocumentsLoadFailed: Story = {
  parameters: {
    ...FROM_RECORDING,
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        documents: http.get(
          DOCUMENTS_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};

/** 문서를 불러오는 동안 보이는 모습이에요. 경로 · 복사 버튼 · 제목 · 날짜 줄 · 본문 자리를 회색 덩어리로 채워 둬요. */
export const Loading: Story = {
  parameters: {
    // 덩어리만 그려져 문서 전체보다 짧아 높이를 따로 둬요
    docs: { story: { inline: false, iframeHeight: 360 } },
    msw: {
      handlers: {
        document: http.get(DOCUMENT_REQUEST, async () => {
          await delay("infinite");
          return HttpResponse.json(documentResponse);
        }),
      },
    },
  },
};

/** 문서를 불러오지 못했을 때예요. 잘못된 주소 · 없는 문서 · 볼 수 없는 문서 · 서버 오류 · 네트워크 문제가 모두 이 모습이에요. 이 스토리에서는 `다시 시도`를 눌러도 계속 실패해요. */
export const LoadFailed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3669-13842",
    },
    docs: { story: { inline: false, iframeHeight: 260 } },
    msw: {
      handlers: {
        document: http.get(
          DOCUMENT_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};
