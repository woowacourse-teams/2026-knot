import { DOCUMENTS_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents";
import { documentsResponse } from "@api/mock/responses/document";
import { resetDocumentMockState } from "@api/mock/state/document";
import type { DocumentsResponse } from "@api/mock/types/document";
import { ToastProvider } from "@provider/context/toastContext";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import TopicDocumentList from ".";

const WORKSPACE_ID = 1;
const DOCUMENTS_REQUEST = `*${DOCUMENTS_API_PATH(WORKSPACE_ID)}`;
const EMPTY_DOCUMENTS_RESPONSE = {
  topics: [],
  items: [],
  nextCursor: null,
} satisfies DocumentsResponse;

/**
 * 문서 탭 「주제별」 탭의 목록이에요. 워크스페이스의 문서를 폴더(주제)별로 모아 보여 줘요.
 * 제목 「문서」와 탭은 문서 탭 섹션(`DocumentArchive`)이 그리고, 이 컴포넌트는 탭이 가리키는 목록만 그려요.
 *
 * **동작 규칙**
 * - 주소의 워크스페이스 문서를 다음 페이지가 없을 때까지 이어 받아 한 번에 보여 줘요. 폴더별로 묶으려면 문서 전체가 있어야 하기 때문이에요.
 *   100개씩 20번(2,000개)까지만 받고, 그보다 많으면 그때까지 받은 문서만 보여 줘요. 다음 페이지가 끝나지 않는 오류에서 요청이 멈추지 않게 하려는 한계예요.
 * - 폴더는 이름순, 폴더 안의 문서는 최근 회의순이에요. 최근에 시작한 녹음에서 나온 문서가 위에 오고, 문서가 만들어진 시각은 순서에 쓰지 않아요.
 *   같은 녹음에서 나온 문서끼리는 서버가 준 순서 그대로예요. 확인 여부나 문서 상태로 묶거나 정렬하지 않아요.
 *   한글 이름의 폴더가 영문 이름의 폴더보다 위에 와요.
 * - 폴더 이름 옆의 숫자는 서버가 센 그 폴더의 전체 문서 수예요. 받은 행을 세지 않아서, 문서를 다 받지 못했을 때도 전체 수가 보여요.
 * - 행에는 제목 · 한 줄 요약 · 만든 날짜 · 녹음 길이가 있어요. 요약이 없는 문서는 빈 줄을 남기지 않고 제목 한 줄만 보여 줘요. 글이 길면 한 줄에서 말줄임해요.
 *   확인 여부와 문서 상태는 행에 보여 주지 않아요.
 * - 행을 누르면 그 문서의 문서 보기 화면으로 가요.
 * - 불러오는 중 · 문서 없음 · 불러오기 실패일 때는 목록 자리만 바꿔요.
 *   불러오는 중의 회색 덩어리는 실제 목록과 간격 · 높이가 같아서, 불러온 뒤 내용이 크게 움직이지 않아요.
 * - 서버 오류나 네트워크 문제로 못 불러오면 「목록을 불러오지 못했어요. 다시 시도해 주세요.」와 `다시 시도`를 보여 줘요.
 *   이어 받는 도중 한 번이라도 실패하면, 그때까지 받은 문서도 보여 주지 않고 같은 안내를 보여 줘요.
 *   `다시 시도`를 누르면 화면을 새로고침하지 않고 처음부터 다시 받아요. 새로고침하면 진행 중인 녹음이 사라지기 때문이에요.
 * - 주소의 워크스페이스 번호는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 서버가 거절하면 같은 불러오기 실패 안내를 보여 줘요.
 *   같은 기준을 화면에도 두면 기준이 두 곳에 생기기 때문이에요.
 * - 로그인이 풀렸으면 로그인 화면으로, 워크스페이스 멤버가 아니거나 없는 워크스페이스면 마지막으로 본 다른 워크스페이스 홈이나 워크스페이스 선택 화면으로 보내고 오류 토스트로 알려요.
 */
const meta = {
  title: "Document/TopicDocumentList",
  component: TopicDocumentList,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5327-2221",
    },
  },
  // 다른 스토리에서 누른 문서 확인 기록이 목록 응답에 남지 않게, 스토리를 열 때마다 mock 상태를 처음으로 되돌려요
  loaders: [
    () => {
      resetDocumentMockState();
    },
  ],
  decorators: [
    (Story) => (
      <MemoryRouter
        initialEntries={[
          getRouterPath({
            routeKey: "DOCUMENTS_BY_TOPIC",
            params: { workspaceId: String(WORKSPACE_ID) },
          }),
        ]}
      >
        <ToastProvider>
          <Routes>
            <Route path={PATH_ROUTE.DOCUMENTS_BY_TOPIC} element={<Story />} />
            <Route
              path={PATH_ROUTE.DOCUMENT}
              element={<p>문서 보기 화면으로 이동했어요.</p>}
            />
          </Routes>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof TopicDocumentList>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 문서를 불러온 기본 모습이에요. 폴더 3개(사용자 인터뷰 · 주간 회의 · 회원 관리)에 문서 7개가 이름순 · 최근 회의순으로 있어요.
 * 「홈 개편 논의」와 「첫 사용자 인터뷰」는 요약이 없어 제목 한 줄만 있어요. 행을 누르면 그 문서의 문서 보기로 가요.
 */
export const Default: Story = {};

/**
 * 워크스페이스에 문서가 하나도 없을 때예요. 예: 아직 한 번도 녹음하지 않은 워크스페이스.
 * 목록 자리에 「아직 문서가 없어요」를 보여 줘요.
 */
export const NoDocuments: Story = {
  parameters: {
    // 문서 페이지에서는 스토리를 한 화면에 함께 그려 응답이 섞이므로, 응답을 덮는 스토리는 따로 그려요
    docs: { story: { inline: false, iframeHeight: 400 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36187",
    },
    msw: {
      handlers: {
        documents: http.get(DOCUMENTS_REQUEST, () =>
          HttpResponse.json(EMPTY_DOCUMENTS_RESPONSE),
        ),
      },
    },
  },
};

/**
 * 문서 목록을 불러오는 동안이에요. 목록 자리를 폴더 3개 · 행 4개 모양의 회색 덩어리로 채워요.
 * 요청 제한 시간(10초)이 지나면 불러오기 실패 모습으로 바뀌어요.
 */
export const Loading: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3812-39476",
    },
    docs: { story: { inline: false, iframeHeight: 600 } },
    msw: {
      handlers: {
        documents: http.get(DOCUMENTS_REQUEST, async () => {
          await delay("infinite");
          return HttpResponse.json(documentsResponse);
        }),
      },
    },
  },
};

/**
 * 문서 목록을 불러오지 못했을 때예요. 서버 오류, 네트워크 문제, 잘못된 주소(400), 이어 받는 도중의 실패가 모두 이 모습이에요.
 * 이 스토리에서는 「다시 시도」를 눌러도 계속 실패해요.
 */
export const LoadFailed: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 260 } },
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2115-8596",
    },
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
