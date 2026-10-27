import { DOCUMENT_RECORDINGS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentRecordings";
import { documentRecordingsResponse } from "@api/mock/responses/document";
import type { DocumentRecordingsResponse } from "@api/mock/types/document";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingDocumentList from ".";

const WORKSPACE_ID = 1;
const DOCUMENT_RECORDINGS_REQUEST = `*${DOCUMENT_RECORDINGS_API_PATH(WORKSPACE_ID)}`;
const EMPTY_RESPONSE = {
  items: [],
  nextCursor: null,
} satisfies DocumentRecordingsResponse;

const [firstRecording, ...restRecordings] = documentRecordingsResponse.items;

/** 첫 페이지에 녹음 하나만 주고 다음 페이지가 있다고 답하는 응답 */
const FIRST_PAGE = {
  items: [firstRecording],
  nextCursor: String(firstRecording.recordingId),
} satisfies DocumentRecordingsResponse;

const SECOND_PAGE = {
  items: restRecordings,
  nextCursor: null,
} satisfies DocumentRecordingsResponse;

const LONG_TITLE_RESPONSE = {
  ...documentRecordingsResponse,
  items: documentRecordingsResponse.items.map((recording, index) =>
    index === 0
      ? {
          ...recording,
          title:
            "알림 정책 정리 회의와 다음 분기 알림 채널 우선순위, 실패 알림 문구 개선안까지 함께 논의한 긴 회의",
          documents: recording.documents.map((document, documentIndex) =>
            documentIndex === 0
              ? {
                  ...document,
                  title:
                    "알림 발송 채널 검토와 푸시 · 메일 · 인앱 알림의 우선순위를 정하기 위한 긴 제목의 문서",
                  summary:
                    "푸시와 메일 중 무엇을 먼저 붙일지는 아직 정하지 못했고, 인앱 알림은 다음 분기로 미루기로 했으며 실패 알림 문구는 따로 정리하기로 했어요.",
                }
              : document,
          ),
        }
      : recording,
  ),
} satisfies DocumentRecordingsResponse;

/** 커서 유무로 첫 페이지와 다음 페이지를 나눠 답하는 핸들러 */
const twoPageHandler = (
  respondSecondPage: () => Promise<Response> | Response,
) =>
  http.get(DOCUMENT_RECORDINGS_REQUEST, ({ request }) => {
    const cursor = new URL(request.url).searchParams.get("cursor");

    return cursor === null
      ? HttpResponse.json(FIRST_PAGE)
      : respondSecondPage();
  });

/**
 * 문서 탭 「녹음별」 탭의 목록이에요. 문서가 1개 이상 나온 녹음을 녹음 시작 최신순으로 묶어 보여 줘요.
 * 제목 「문서」와 탭은 문서 탭 섹션(`DocumentArchive`)이 그리고, 이 컴포넌트는 탭이 가리키는 목록만 그려요.
 *
 * **동작 규칙**
 * - 녹음마다 카드 하나예요. 머리 행에 마이크 배지 · 표시 제목 · 녹음 시작 날짜 · 녹음 길이 칩과 「녹음 보기」가 있고, 그 아래에 그 녹음에서 나온 문서를 모두 펼쳐 둬요. 접거나 나누지 않아요.
 *   머리 행에 문서 수는 없어요. 몇 개인지는 아래 문서 행으로 봐요.
 *   표시 제목은 서버가 만들어요. AI 녹음 제목이 없으면 「OO 님의 녹음」이 와요. 긴 제목과 요약은 한 줄에서 말줄임해요.
 * - 문서 행에는 제목과 한 줄 요약만 있어요. 요약이 없는 문서는 제목 한 줄만 보여 줘요. 주제 · 날짜 · 녹음 길이 · 확인 상태는 보여 주지 않아요.
 * - 머리 행을 누르면 그 녹음의 녹음 문서로, 문서 행을 누르면 그 문서의 문서 보기로 가요.
 * - 녹음 20개씩 받고, 목록 끝까지 내리면 다음 20개를 이어 받아요. 그동안 목록 끝에 불러오는 중 표시가 보여요.
 *   다음 20개를 받지 못하면 이미 보인 녹음은 그대로 두고 목록 끝에서만 「녹음을 더 불러오지 못했어요」와 `다시 시도`를 보여 줘요.
 * - 불러오는 중 · 문서가 나온 녹음 없음 · 불러오기 실패일 때는 목록 자리만 바꿔요. `다시 시도`는 화면을 새로고침하지 않고 처음부터 다시 받아요.
 * - 로그인이 풀렸으면 로그인 화면으로, 워크스페이스 멤버가 아니거나 없는 워크스페이스면 다른 워크스페이스 홈이나 워크스페이스 선택 화면으로 보내고 오류 토스트로 알려요.
 */
const meta = {
  title: "Document/RecordingDocumentList",
  component: RecordingDocumentList,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2402",
    },
  },
  decorators: [
    (Story) => (
      <MemoryRouter
        initialEntries={[
          getRouterPath({
            routeKey: "DOCUMENTS_BY_RECORDING",
            params: { workspaceId: String(WORKSPACE_ID) },
          }),
        ]}
      >
        <ToastProvider>
          <Routes>
            <Route
              path={PATH_ROUTE.DOCUMENTS_BY_RECORDING}
              element={<Story />}
            />
            <Route
              path={PATH_ROUTE.DOCUMENT}
              element={<p>문서 보기 화면으로 이동했어요.</p>}
            />
            <Route
              path={PATH_ROUTE.RECORDING_DOCUMENT}
              element={<p>녹음 문서 화면으로 이동했어요.</p>}
            />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RecordingDocumentList>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 목록을 불러온 기본 모습이에요. 문서가 나온 녹음 6개가 녹음 시작 최신순으로 있고, 「회원 탈퇴 정책 논의」에는 문서가 두 개예요.
 * 「유월 님의 녹음」은 AI 녹음 제목이 없어 서버가 대체 이름을 준 녹음이고, 「홈 개편 논의」 · 「첫 사용자 인터뷰」는 요약이 없어 제목 한 줄만 있어요.
 */
export const Default: Story = {};

/**
 * 녹음 제목 · 문서 제목 · 요약이 길 때예요. 한 줄에서 말줄임하고, 「녹음 보기」와 화살표는 밀려나지 않아요.
 */
export const LongTitle: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 520 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5329-58732",
    },
    msw: {
      handlers: {
        documentRecordings: http.get(DOCUMENT_RECORDINGS_REQUEST, () =>
          HttpResponse.json(LONG_TITLE_RESPONSE),
        ),
      },
    },
  },
};

/**
 * 문서가 나온 녹음이 하나도 없을 때예요. 예: 아직 한 번도 녹음하지 않은 워크스페이스.
 * 목록 자리에 「아직 문서가 나온 녹음이 없어요」를 보여 줘요.
 */
export const NoRecordings: Story = {
  parameters: {
    // 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 응답을 덮는 스토리는 따로 그려요
    docs: { story: { inline: false, iframeHeight: 400 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2663",
    },
    msw: {
      handlers: {
        documentRecordings: http.get(DOCUMENT_RECORDINGS_REQUEST, () =>
          HttpResponse.json(EMPTY_RESPONSE),
        ),
      },
    },
  },
};

/**
 * 목록을 불러오는 동안이에요. 목록 자리를 녹음 카드 3개(문서 행 3 · 1 · 1) 모양의 회색 덩어리로 채워요.
 * 요청 제한 시간(10초)이 지나면 불러오기 실패 모습으로 바뀌어요.
 */
export const Loading: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 720 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5329-58638",
    },
    msw: {
      handlers: {
        documentRecordings: http.get(DOCUMENT_RECORDINGS_REQUEST, async () => {
          await delay("infinite");
          return HttpResponse.json(documentRecordingsResponse);
        }),
      },
    },
  },
};

/**
 * 목록을 불러오지 못했을 때예요. 서버 오류, 네트워크 문제, 잘못된 주소(400)가 모두 이 모습이에요.
 * 이 스토리에서는 「다시 시도」를 눌러도 계속 실패해요.
 */
export const LoadFailed: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 260 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2845",
    },
    msw: {
      handlers: {
        documentRecordings: http.get(
          DOCUMENT_RECORDINGS_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};

/**
 * 첫 페이지(녹음 하나)를 보고 목록 끝까지 내려 다음 페이지를 받는 동안이에요. 마지막 카드 아래에 불러오는 중 표시가 보여요.
 * 이 스토리에서는 다음 페이지가 오지 않아 표시가 계속 남아요.
 */
export const LoadingMore: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 520 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5329-58732",
    },
    msw: {
      handlers: {
        documentRecordings: twoPageHandler(async () => {
          await delay("infinite");
          return HttpResponse.json(SECOND_PAGE);
        }),
      },
    },
  },
};

/**
 * 다음 페이지를 받지 못했을 때예요. 이미 보인 녹음은 그대로 두고, 마지막 카드 아래에 「녹음을 더 불러오지 못했어요」와 `다시 시도`를 보여 줘요.
 * 이 스토리에서는 「다시 시도」를 눌러도 계속 실패해요.
 */
export const LoadMoreFailed: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 560 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5329-58819",
    },
    msw: {
      handlers: {
        documentRecordings: twoPageHandler(
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
};
