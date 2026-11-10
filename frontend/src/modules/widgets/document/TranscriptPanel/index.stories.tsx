import { DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { DOCUMENT_TRANSCRIPT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/transcript";
import {
  documentDetailsResponse,
  documentTranscriptsResponse,
} from "@api/mock/responses/document";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import { resetDocumentMockState } from "@api/mock/state/document";
import type { DocumentTranscriptResponse } from "@api/mock/types/document";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { spyOn, userEvent, within } from "storybook/test";

import TranscriptPanel from ".";

const WORKSPACE_ID = 1;
const [documentResponse] = documentDetailsResponse;
const [transcriptResponse] = documentTranscriptsResponse;
const DOCUMENT_REQUEST = `*${DOCUMENT_API_PATH(WORKSPACE_ID, documentResponse.id)}`;
const TRANSCRIPT_REQUEST = `*${DOCUMENT_TRANSCRIPT_API_PATH(WORKSPACE_ID, documentResponse.id)}`;

/** 원문 패널이 놓이는 문서 보기 화면의 주소예요. 이 주소의 문서를 만든 원문을 불러와요 */
const DOCUMENT_PATH = getRouterPath({
  routeKey: "DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    documentId: String(documentResponse.id),
  },
});

/** 원문 패널이 놓이는 녹음 문서 화면의 주소예요. 이 주소의 녹음의 전사 전체를 불러와요 */
const RECORDING_DOCUMENT_PATH = getRouterPath({
  routeKey: "RECORDING_DOCUMENT",
  params: {
    workspaceId: String(WORKSPACE_ID),
    recordingId: String(recordingDocumentResponse.recordingId),
  },
});

const MILLIS_PER_SECOND = 1000;
// 기본 원문(32분)을 늦출 시간이에요. 앞 7줄은 한 시간 전, 뒤 5줄은 한 시간을 넘은 뒤가 돼요
const SHIFT_SECONDS = 50 * 60;
const SHIFT_MILLIS = SHIFT_SECONDS * MILLIS_PER_SECOND;

// 한 시간이 넘는 회의처럼, 기본 원문의 발화 시각과 녹음 길이만 50분 늦춰요. 문장은 그대로예요
const overOneHourTranscriptResponse = {
  ...transcriptResponse,
  recordingDurationSeconds:
    transcriptResponse.recordingDurationSeconds + SHIFT_SECONDS,
  segments: transcriptResponse.segments.map((segment) => ({
    ...segment,
    startMillis: segment.startMillis + SHIFT_MILLIS,
    endMillis:
      segment.endMillis === null ? null : segment.endMillis + SHIFT_MILLIS,
  })),
} satisfies DocumentTranscriptResponse;

// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 마지막 스토리의 응답이 모든 스토리에 적용돼요.
// 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요. 높이는 원문 전체가 잘리지 않는 값이에요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 1000 } };

/**
 * 전사를 한 줄씩 보여 주는 원문 패널이에요. 문서 보기와 녹음 문서 화면의 오른쪽에 놓여요.
 * 문서 보기에서는 그 문서를 만든 원본 전사를, 녹음 문서에서는 그 녹음의 전사 전체를 보여 줘요. 두 화면은 날짜와 맨 아래 문구만 달라요.
 * 머리에 제목 「원문」, 문서를 만든 날짜, 녹음 길이가 있고, 구분선 아래에 발화가 한 줄씩 놓여요.
 * 맨 아래에는 「이 문서를 만든 원본이에요. 전사 전체를 그대로 볼 수 있어요」가 있어요.
 * 문서를 보는 화면에서 GNB 오른쪽의 원문 버튼으로 여닫아요. 버튼과 놓이는 자리는 이 패널 밖에 있어서, 이 스토리에는 패널만 있어요.
 *
 * **동작 규칙**
 * - 주소의 문서를 만든 원문을 불러와요. 주소의 번호는 숫자인지 확인하지 않고 그대로 요청하고, 잘못된 주소인지는 서버가 판단해요.
 *   같은 기준을 화면에도 두면 기준이 두 곳에 생기기 때문이에요.
 * - 발화는 서버가 준 순서 그대로 놓여요. 줄마다 왼쪽에 말하기 시작한 시각을 `02:40`처럼, 오른쪽에 「참여자 N: 문장」을 보여 줘요.
 *   녹음이 한 시간을 넘은 뒤의 발화는 시각이 `1:02:03`처럼 바뀌고, 시각 칸이 글자만큼 넓어져요.
 * - 말한 사람은 이름 대신 서버가 준 번호로 「참여자 N」이라고 적어요. 화자를 알 수 없는 구간은 「참여자 N:」 없이 문장만 보여 줘요.
 * - 만든 날짜는 원문 응답에 없어 문서 상세에서 가져와요. 문서 상세를 받는 중이거나 받지 못했으면 날짜만 빼고 원문은 그대로 보여 줘요.
 * - 녹음 문서에서는 날짜 자리에 녹음을 시작한 날을, 맨 아래에 「이 녹음의 전사 전체예요.」를 둬요. 녹음 문서를 열면 패널이 열려 있어요.
 * - 패널의 폭과 높이의 한계는 패널을 놓는 자리가 정해요. 시안의 폭은 400px이에요.
 *   패널이 자리보다 길어지면 발화 목록만 세로로 스크롤하고, 제목 · 날짜 줄과 맨 아래 문구는 그대로 보여요.
 * - 패널 안에는 닫기 버튼이 없어요. 패널을 여닫는 버튼은 GNB에 두기 때문이에요.
 * - 제목 「원문」의 오른쪽 끝에 「복사」가 있어요. 누르면 보이는 발화 줄을 `02:40 참여자 1: 문장`처럼 한 줄씩 이어 복사하고, 3초 동안 「복사됨」으로 바뀌어요.
 * - 원문을 받기 전에는 불러오는 중 모습이 패널 전체를 대신 그려요. 제목 「원문」은 그대로 두고, 날짜 줄과 발화 줄 자리를 회색 덩어리로 채워요.
 *   덩어리 사이 간격을 실제 발화 줄과 같게 둬서, 원문을 받은 뒤 내용이 크게 움직이지 않아요.
 * - 원문을 불러오지 못하면 제목 아래에 「원문을 불러오지 못했어요. 다시 시도해 주세요.」와 `다시 시도`를 보여 줘요.
 *   `다시 시도`를 누르면 화면을 새로고침하지 않고 원문만 다시 불러와요. 새로고침하면 진행 중인 녹음이 사라지기 때문이에요.
 * - 잘못된 주소(400) · 볼 수 없는 문서(403) · 문서나 원문이 없을 때(404)는 원문이 없어졌거나 볼 수 없게 된 것이라,
 *   보던 원문이 있어도 불러오지 못했다고 알려요.
 * - 서버 오류나 네트워크 문제는 받은 원문이 없을 때만 불러오지 못했다고 알려요. 다른 탭에 다녀와 원문을 다시 받다가 실패하면 보던 원문을 그대로 둬요.
 * - 로그인이 풀렸으면(401) 로그인 화면으로 보내요. 보내는 동안에는 불러오는 중 모습이에요.
 */
const meta = {
  title: "Document/TranscriptPanel",
  component: TranscriptPanel,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36104",
    },
  },
  // 문서 mock 상태를 읽으므로, 다른 스토리에서 「문서를 확인했어요」를 누른 기록이 남지 않게 스토리를 열 때마다 처음으로 되돌려요
  loaders: [
    () => {
      resetDocumentMockState();
    },
  ],
  decorators: [
    (Story, { parameters }) => {
      // 문서 화면 오른쪽 자리의 폭(400px)이에요. 높이는 자리를 줄이는 스토리만 줘요
      const slot = (
        <div style={{ width: "25rem", height: parameters.slotHeight }}>
          <Story />
        </div>
      );

      return (
        <MemoryRouter initialEntries={[parameters.path ?? DOCUMENT_PATH]}>
          <ToastProvider>
            <Routes>
              <Route path={PATH_ROUTE.DOCUMENT} element={slot} />
              <Route path={PATH_ROUTE.RECORDING_DOCUMENT} element={slot} />
            </Routes>
          </ToastProvider>
        </MemoryRouter>
      );
    },
  ],
} satisfies Meta<typeof TranscriptPanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 신규 녹음 원문 API만 미지원일 때예요. 기존 문서의 원문 오류에는 이 안내를 쓰지 않아요. */
export const UnsupportedRecordingTranscript: Story = {
  parameters: {
    path: RECORDING_DOCUMENT_PATH,
    docs: { story: { inline: false, iframeHeight: 300 } },
    msw: { handlers: { recordingTranscript: http.get(
      "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript",
      () => HttpResponse.json({ code: "REQUEST_PATH_NOT_FOUND" }, { status: 404 }),
    ) } },
  },
};

/**
 * 원문을 받은 기본 모습이에요. 문서 101을 만든 32분 회의의 발화 12줄이 서버가 준 순서로 놓여요.
 * `08:20`의 「네, 좋아요.」는 화자를 알 수 없는 구간이라 문장만 보여요.
 */
export const Default: Story = {};

/** 머리의 「복사」를 누른 직후예요. 보이는 발화 줄을 한 줄씩 이어 복사하고, 버튼이 3초 동안 「복사됨」으로 바뀐 뒤 「복사」로 돌아와요. */
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
    // 원문을 받기 전에는 복사 버튼이 없어 보일 때까지 기다려요
    const copyButton = await canvas.findByRole("button", { name: "복사" });

    await userEvent.click(copyButton);
  },
};

/**
 * 패널을 놓는 자리가 패널보다 짧을 때예요. 발화 목록만 세로로 스크롤되고, 제목 · 날짜 줄과 맨 아래 문구는 그대로 보여요.
 * 이 스토리는 자리 높이를 480px로 줄였어요.
 */
export const ScrollingLines: Story = {
  parameters: { slotHeight: "30rem" /* 480px */ },
};

/**
 * 한 시간이 넘는 회의의 원문이에요.
 * 한 시간 전 발화는 `59:10`처럼, 한 시간을 넘은 발화는 `1:00:24`처럼 보여요.
 * 시각 칸이 글자만큼 넓어져서, 그 줄의 문장은 조금 오른쪽에서 시작해요.
 * 녹음 길이 칩은 「1시간 22분」이에요.
 */
export const OverOneHour: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
    msw: {
      handlers: {
        transcript: http.get(TRANSCRIPT_REQUEST, () =>
          HttpResponse.json(overOneHourTranscriptResponse),
        ),
      },
    },
  },
};

/**
 * 문서 상세를 받지 못했을 때예요. 만든 날짜만 빠지고 녹음 길이와 발화는 그대로 보여요.
 * 문서 상세를 받는 중에도 같은 모습이에요.
 */
export const DocumentDetailLoadFailed: Story = {
  parameters: {
    docs: ISOLATED_DOCS,
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

/**
 * 원문을 불러오는 동안이에요. 제목 「원문」은 그대로 두고, 날짜 줄과 발화 다섯 줄 자리를 회색 덩어리로 채워요.
 * 이 스토리는 응답이 오지 않게 했지만, 요청 제한 시간(10초)이 지나면 불러오지 못한 모습으로 바뀌어요.
 */
export const Loading: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3800-37616",
    },
    // 덩어리만 그려져 원문 전체보다 짧아 높이를 따로 둬요
    docs: { story: { inline: false, iframeHeight: 480 } },
    msw: {
      handlers: {
        transcript: http.get(TRANSCRIPT_REQUEST, async () => {
          await delay("infinite");
          return HttpResponse.json(transcriptResponse);
        }),
      },
    },
  },
};

/**
 * 원문을 불러오지 못했을 때예요.
 * 잘못된 주소 · 볼 수 없는 문서 · 문서나 원문이 없을 때 · 서버 오류 · 네트워크 문제가 모두 이 모습이에요.
 * 이 스토리에서는 `다시 시도`를 눌러도 계속 실패해요.
 */
export const LoadFailed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3800-37745",
    },
    docs: { story: { inline: false, iframeHeight: 240 } },
    msw: {
      handlers: {
        transcript: http.get(
          TRANSCRIPT_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};

/** 녹음 문서 화면에 놓인 패널이에요. 그 녹음의 전사 전체와 녹음을 시작한 날을 보여 주고, 맨 아래 문구가 「이 녹음의 전사 전체예요.」예요. 예: 문서 탭 「녹음별」 탭에서 녹음 행을 눌렀을 때 */
export const RecordingDocument: Story = {
  parameters: {
    path: RECORDING_DOCUMENT_PATH,
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5373-3234",
    },
  },
};
