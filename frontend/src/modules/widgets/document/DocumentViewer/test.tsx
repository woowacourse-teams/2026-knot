import { GetMeResponseDto } from "@api/dto/auth";
import {
  GetDocumentConfirmationsResponseDto,
  GetDocumentResponseDto,
  GetDocumentsResponseDto,
} from "@api/dto/document";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import {
  documentConfirmationsResponse,
  documentDetailsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import { mockServer } from "@api/mock/server";
import { findDocuments } from "@api/mock/state/document";
import type {
  DocumentConfirmationsResponse,
  DocumentsResponse,
} from "@api/mock/types/document";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  waitForElementToBeRemoved,
  within,
} from "@testing-library/react";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { delay, http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import DocumentViewer from ".";

const expected = new GetDocumentResponseDto(documentDetailsResponse[0]);
const WORKSPACE_ID = "1";
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const DOCUMENT_REQUEST =
  "*/api/v1/workspaces/:workspaceId/documents/:documentId";
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const CONFIRMATIONS_REQUEST = `${DOCUMENT_REQUEST}/confirmations`;
const MY_CONFIRMATION_REQUEST = `${CONFIRMATIONS_REQUEST}/me`;

const expectedConfirmations = new GetDocumentConfirmationsResponseDto(
  documentConfirmationsResponse[0],
);
const expectedMe = new GetMeResponseDto(meResponse);

// 서버 정렬을 믿지 않는지 보려고 순서를 섞고, 확인하지 않고 나간 사람(EXCLUDED)을 끼워 넣은 응답이에요
const [confirmedItem, , pendingItem] = documentConfirmationsResponse[0].items;
const shuffledConfirmationsResponse = {
  ...documentConfirmationsResponse[0],
  items: [
    pendingItem,
    {
      memberId: 9,
      nickname: "루루",
      profileImageUrl: null,
      confirmedAt: null,
      state: "EXCLUDED",
    },
    confirmedItem,
  ],
} satisfies DocumentConfirmationsResponse;
const [pendingMember, excludedMember, confirmedMember] =
  new GetDocumentConfirmationsResponseDto(shuffledConfirmationsResponse).items;

const LOAD_FAILED_NOTICE = "목록을 불러오지 못했어요";
const MY_LABEL = "(나)";
const COPIED_DURATION_MS = 3000;
const CONFIRM_BUTTON_NAME = "문서를 확인했어요";
const CONFIRMED_BUTTON_NAME = "확인했어요";
const STEPPER_NAME = "같은 녹음의 문서";

// 여는 문서와 같은 녹음에서 나온 문서들. 스테퍼는 서버가 준 이 순서대로 넘겨요
const recordingDocuments = new GetDocumentsResponseDto(
  documentsResponse,
).items.filter(
  ({ recordingSessionId }) =>
    recordingSessionId === expected.recordingSessionId,
);
const [firstRecordingDocument] = recordingDocuments;
const lastRecordingDocument = recordingDocuments[recordingDocuments.length - 1];

/** 그 문서를 열었을 때 보이는 제목. 제목 줄은 문서 상세 응답을 그려서 상세 DTO에서 가져와요 */
const getDocumentTitle = (documentId: number) => {
  const documentDetail = documentDetailsResponse.find(
    ({ id }) => id === documentId,
  );

  if (documentDetail === undefined) {
    throw new Error(`문서 상세 mock이 없는 문서예요: ${documentId}`);
  }

  return new GetDocumentResponseDto(documentDetail).title;
};

const getDocumentPath = (documentId: number) =>
  getRouterPath({
    routeKey: "DOCUMENT",
    params: { workspaceId: WORKSPACE_ID, documentId: String(documentId) },
  });

const writeText = vi.fn<(text: string) => Promise<void>>();

/** 녹음 직후 정리 화면에서 문서 보기로 넘어왔다는 주소 쿼리. 이 쿼리가 있을 때만 스테퍼가 보여요 */
const FROM_RECORDING_SEARCH = `?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`;

// 이동을 확인할 수 있게 로그인 경로에 표시만 하는 화면을 둬요
const renderViewerAt = (initialEntry: string) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: PATH_ROUTE.DOCUMENT, element: <DocumentViewer /> },
      { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
    ],
    { initialEntries: [initialEntry] },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { router };
};

/** 문서 탭 · 홈 · 탐색에서 연 것처럼 쿼리 없이 문서를 열어요 */
const renderViewer = (documentId: string) =>
  renderViewerAt(
    getRouterPath({
      routeKey: "DOCUMENT",
      params: { workspaceId: WORKSPACE_ID, documentId },
    }),
  );

/** 녹음 직후 정리 화면에서 넘어온 것처럼 쿼리를 붙여 문서를 열어요 */
const renderViewerFromRecording = (documentId: number) =>
  renderViewerAt(`${getDocumentPath(documentId)}${FROM_RECORDING_SEARCH}`);

const findStepper = () => screen.findByRole("group", { name: STEPPER_NAME });

/** 스테퍼의 이전 · 다음 버튼. 버튼에 이름이 없어 놓인 순서로 찾아요 */
const getStepButtons = (stepper: HTMLElement) => {
  const [previousButton, nextButton] = within(stepper).getAllByRole("button");

  return { previousButton, nextButton };
};

/** 확인 수 문구에 포인터를 올려 확인한 사람 팝오버를 열어요 */
const hoverConfirmCount = async () => {
  const confirmCount = await screen.findByText(
    `${expected.confirmationSummary.confirmedCount}명 확인했어요`,
  );

  fireEvent.pointerEnter(confirmCount);

  return confirmCount;
};

const findMemberRows = async () => {
  const memberList = await screen.findByRole("list", {
    name: "문서 확인 현황",
  });

  return within(memberList).getAllByRole("listitem");
};

/** 팝오버 줄 가운데 내 이름이 있는 줄 */
const getMyRow = (rows: HTMLElement[]) => {
  const myRow = rows.find(
    (row) => within(row).queryByText(expectedMe.nickname) !== null,
  );

  if (myRow === undefined) {
    throw new Error("확인 대상 mock에 내 항목이 없어요");
  }

  return myRow;
};

const queryConfirmButton = () =>
  screen.queryByRole("button", { name: CONFIRM_BUTTON_NAME });

const click = async (element: HTMLElement) => {
  await act(async () => {
    fireEvent.click(element);
  });
};

const advanceTimers = async (ms: number) => {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
};

describe("DocumentViewer", () => {
  beforeEach(() => {
    writeText.mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    writeText.mockReset();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  it("문서를 불러오는 동안에는 불러오는 중이라고 알린다", () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, async () => {
        await delay("infinite");
        return HttpResponse.json(documentDetailsResponse[0]);
      }),
    );
    renderViewer(String(expected.id));

    expect(
      screen.getByRole("region", { name: "문서를 불러오고 있어요" }),
    ).toHaveAttribute("aria-busy", "true");
  });

  it("문서 제목과 본문의 구역 제목·목록 항목을 보여준다", async () => {
    renderViewer(String(expected.id));

    expect(
      await screen.findByRole("heading", { level: 2, name: expected.title }),
    ).toBeInTheDocument();
    // 결정 있는 문서: 구역 제목(##) 4개와 미결정 항목 2개
    expect(screen.getAllByRole("heading", { level: 4 })).toHaveLength(4);
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
  });

  it("복사 버튼 · 확인 수 · 확인 버튼이 그려져도 문서 상세는 한 번만 요청한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        requestCount += 1;
        return HttpResponse.json(documentDetailsResponse[0]);
      }),
    );
    renderViewer(String(expected.id));

    // 문서를 받은 뒤에 그려지는 컴포넌트들이 같은 문서를 다시 요청하지 않는지 보려고,
    // 그 뒤에 나가는 확인 대상 조회가 끝날 때까지 기다린 다음 세요
    await hoverConfirmCount();
    await findMemberRows();

    expect(requestCount).toBe(1);
  });

  it("문서를 받은 직후에 다른 탭에 다녀와도 문서를 다시 받는다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        requestCount += 1;
        return HttpResponse.json(documentDetailsResponse[0]);
      }),
    );
    renderViewer(String(expected.id));

    await screen.findByRole("heading", { level: 2, name: expected.title });
    expect(requestCount).toBe(1);

    // TanStack Query는 탭이 다시 보이게 된 것을 visibilitychange 이벤트로 알아요
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });

    await waitFor(() => expect(requestCount).toBe(2));
  });

  it("문서를 보는 동안 문서가 없어지면(404) 다시 불러올 때 문서를 불러오지 못했다고 알린다", async () => {
    renderViewer(String(expected.id));
    await screen.findByRole("heading", { level: 2, name: expected.title });

    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => new HttpResponse(null, { status: 404 })),
    );
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });

    expect(
      await screen.findByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).toBeInTheDocument();
  });

  it("문서를 보는 동안 다시 불러오기가 서버 오류로 실패하면 받은 문서를 계속 보여준다", async () => {
    let failedRequestCount = 0;
    renderViewer(String(expected.id));
    await screen.findByRole("heading", { level: 2, name: expected.title });

    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        failedRequestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    act(() => {
      window.dispatchEvent(new Event("visibilitychange"));
    });
    await waitFor(() => expect(failedRequestCount).toBe(1));

    expect(
      screen.getByRole("heading", { level: 2, name: expected.title }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).not.toBeInTheDocument();
  });

  it("문서 머리에 경로 · 만든 날짜 · 녹음 길이를 보여준다", async () => {
    renderViewer(String(expected.id));

    await screen.findByRole("heading", { level: 2, name: expected.title });

    // 경로는 위 단계 「문서」와 지금 문서의 제목이에요. 그래서 제목은 경로와 제목 줄 두 곳에 보여요
    expect(screen.getByText("문서")).toBeInTheDocument();
    expect(screen.getAllByText(expected.title)).toHaveLength(2);
    expect(
      screen.getByText(formatDate(expected.createdAt)),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        formatDurationFromSeconds(expected.recordingDurationSeconds),
      ),
    ).toBeInTheDocument();
  });

  it("확인한 사람 수를 보여주고, 포인터를 올리면 확인 대상의 이름을 보여준다", async () => {
    renderViewer(String(expected.id));

    await hoverConfirmCount();

    const rows = await findMemberRows();

    expect(rows).toHaveLength(expectedConfirmations.items.length);
    expectedConfirmations.items.forEach(({ nickname }, index) => {
      expect(within(rows[index]).getByText(nickname)).toBeInTheDocument();
    });
  });

  it("응답의 순서와 관계없이 확인한 사람을 먼저 보여주고, 확인하지 않고 나간 사람은 보여주지 않는다", async () => {
    mockServer.use(
      http.get(CONFIRMATIONS_REQUEST, () =>
        HttpResponse.json(shuffledConfirmationsResponse),
      ),
    );
    renderViewer(String(expected.id));

    await hoverConfirmCount();

    const rows = await findMemberRows();

    expect(rows).toHaveLength(2);
    expect(
      within(rows[0]).getByText(confirmedMember.nickname),
    ).toBeInTheDocument();
    expect(
      within(rows[1]).getByText(pendingMember.nickname),
    ).toBeInTheDocument();
    expect(screen.queryByText(excludedMember.nickname)).not.toBeInTheDocument();
  });

  it("확인 대상을 불러오지 못하면 팝오버에 알리고, 포인터를 다시 올리면 다시 불러와 보여준다", async () => {
    mockServer.use(
      http.get(
        CONFIRMATIONS_REQUEST,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    renderViewer(String(expected.id));

    const confirmCount = await hoverConfirmCount();

    expect(await screen.findByText(LOAD_FAILED_NOTICE)).toBeInTheDocument();

    // 서버가 돌아온 뒤 팝오버를 닫았다가 다시 열어요
    mockServer.resetHandlers();
    fireEvent.pointerLeave(confirmCount);
    await waitForElementToBeRemoved(() =>
      screen.queryByText(LOAD_FAILED_NOTICE),
    );
    fireEvent.pointerEnter(confirmCount);

    expect(await findMemberRows()).toHaveLength(
      expectedConfirmations.items.length,
    );
  });

  it("팝오버에서 아직 확인하지 않은 내 이름 옆에 「(나)」를 붙이고, 다른 사람에게는 붙이지 않는다", async () => {
    renderViewer(String(expected.id));

    await hoverConfirmCount();

    const rows = await findMemberRows();
    const myRow = getMyRow(rows);

    expect(await within(myRow).findByText(MY_LABEL)).toBeInTheDocument();
    rows
      .filter((row) => row !== myRow)
      .forEach((row) => {
        expect(within(row).queryByText(MY_LABEL)).not.toBeInTheDocument();
      });
  });

  it("내가 확인했으면 확인한 사람 줄의 내 이름 옆에 「(나)」를 붙인다", async () => {
    const [, myConfirmedResponse] = documentConfirmationsResponse;
    const confirmedCount = new GetDocumentConfirmationsResponseDto(
      myConfirmedResponse,
    ).items.filter(({ state }) => state === "CONFIRMED").length;
    mockServer.use(
      http.get(CONFIRMATIONS_REQUEST, () =>
        HttpResponse.json(myConfirmedResponse),
      ),
    );
    renderViewer(String(expected.id));

    await hoverConfirmCount();

    const rows = await findMemberRows();
    const myRow = getMyRow(rows);

    expect(await within(myRow).findByText(MY_LABEL)).toBeInTheDocument();
    // 확인한 사람은 위에 모아 그리므로, 내 줄이 그 안에 있어야 해요
    expect(rows.indexOf(myRow)).toBeLessThan(confirmedCount);
  });

  it("내 회원 정보를 받지 못해도 「(나)」 없이 확인 대상을 모두 보여준다", async () => {
    mockServer.use(
      http.get(
        `*${AUTH_ME_API_PATH}`,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    renderViewer(String(expected.id));

    await hoverConfirmCount();

    expect(await findMemberRows()).toHaveLength(
      expectedConfirmations.items.length,
    );
    expect(screen.queryByText(MY_LABEL)).not.toBeInTheDocument();
  });

  it("복사를 누르면 제목과 본문을 마크다운으로 복사하고, 3초 동안 복사됨을 보여준다", async () => {
    renderViewer(String(expected.id));
    await screen.findByRole("heading", { level: 2, name: expected.title });
    vi.useFakeTimers();

    await click(screen.getByRole("button", { name: "복사" }));

    // 본문에는 제목이 없어서 제목을 `#` 제목으로 앞에 붙여요
    expect(writeText).toHaveBeenCalledWith(
      `# ${expected.title}\n\n${expected.content}`,
    );
    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await advanceTimers(COPIED_DURATION_MS - 1);

    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await advanceTimers(1);

    expect(screen.getByRole("button", { name: "복사" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "복사됨" }),
    ).not.toBeInTheDocument();
  });

  it("아직 확인하지 않았으면 확인 버튼을 보여주고, 누르면 누를 수 없는 「확인했어요」로 바뀌고 확인 수가 늘어난다", async () => {
    renderViewer(String(expected.id));

    fireEvent.click(
      await screen.findByRole("button", { name: CONFIRM_BUTTON_NAME }),
    );

    expect(
      await screen.findByRole("button", { name: CONFIRMED_BUTTON_NAME }),
    ).toBeDisabled();
    expect(queryConfirmButton()).not.toBeInTheDocument();
    expect(
      screen.getByText(
        `${expected.confirmationSummary.confirmedCount + 1}명 확인했어요`,
      ),
    ).toBeInTheDocument();
  });

  it("이미 확인한 문서를 열면 누를 수 없는 「확인했어요」를 보여준다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json({
          ...documentDetailsResponse[0],
          myConfirmationState: "CONFIRMED",
        }),
      ),
    );
    renderViewer(String(expected.id));

    expect(
      await screen.findByRole("button", { name: CONFIRMED_BUTTON_NAME }),
    ).toBeDisabled();
    expect(queryConfirmButton()).not.toBeInTheDocument();
  });

  it("확인 대상이 아니면 확인 버튼도 「확인했어요」도 보여주지 않는다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json({
          ...documentDetailsResponse[0],
          myConfirmationState: "NOT_REQUIRED",
        }),
      ),
    );
    renderViewer(String(expected.id));

    await screen.findByRole("heading", { level: 2, name: expected.title });

    expect(queryConfirmButton()).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: CONFIRMED_BUTTON_NAME }),
    ).not.toBeInTheDocument();
  });

  it("확인 대상이 아니라는 응답(409)을 받으면 문서를 다시 불러와 확인 버튼을 없앤다", async () => {
    // 확인 요청을 받기 전에는 확인이 필요하다고, 받은 뒤에는 확인 대상이 아니라고 답해요.
    // 요청 순서로 나누면 409와 관계없는 문서 조회가 바뀐 응답을 먼저 받아, 409 처리가 없어도 통과해요
    let isConfirmRequested = false;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json(
          isConfirmRequested
            ? {
                ...documentDetailsResponse[0],
                myConfirmationState: "NOT_REQUIRED",
              }
            : documentDetailsResponse[0],
        ),
      ),
      http.put(MY_CONFIRMATION_REQUEST, () => {
        isConfirmRequested = true;

        return HttpResponse.json(
          {
            code: "CONFIRMATION_NOT_REQUIRED",
            message: "확인 대상이 아닙니다.",
          },
          { status: 409 },
        );
      }),
    );
    renderViewer(String(expected.id));

    fireEvent.click(
      await screen.findByRole("button", { name: CONFIRM_BUTTON_NAME }),
    );

    await waitForElementToBeRemoved(queryConfirmButton);
  });

  it("확인 요청이 서버 오류로 실패하면 버튼을 남겨 다시 누를 수 있게 한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.put(MY_CONFIRMATION_REQUEST, async () => {
        requestCount += 1;
        // 응답이 바로 오면 누를 수 없는 상태가 그려지기 전에 지나가서, 잠깐 늦게 답해요
        await delay(100);
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderViewer(String(expected.id));

    const confirmButton = await screen.findByRole("button", {
      name: CONFIRM_BUTTON_NAME,
    });
    fireEvent.click(confirmButton);

    // 응답을 기다리는 동안에는 누를 수 없고, 실패하면 다시 누를 수 있어요
    await waitFor(() => expect(confirmButton).toBeDisabled());
    await waitFor(() => expect(confirmButton).toBeEnabled());

    fireEvent.click(confirmButton);

    await waitFor(() => expect(requestCount).toBe(2));
  });

  it("확인 요청에서 로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.put(
        MY_CONFIRMATION_REQUEST,
        () => new HttpResponse(null, { status: 401 }),
      ),
    );
    renderViewer(String(expected.id));

    fireEvent.click(
      await screen.findByRole("button", { name: CONFIRM_BUTTON_NAME }),
    );

    expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
  });

  it("없는 문서면 문서를 불러오지 못했다고 알리고, 다시 시도를 누르면 다시 조회한다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        requestCount += 1;
        return new HttpResponse(null, { status: 404 });
      }),
    );
    renderViewer(String(expected.id));

    expect(
      await screen.findByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    await waitFor(() => expect(requestCount).toBe(2));
    expect(
      screen.getByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).toBeInTheDocument();
  });

  it.each([400, 403])(
    "%i 응답도 문서를 불러오지 못했다고 알린다",
    async (status) => {
      mockServer.use(
        http.get(DOCUMENT_REQUEST, () => new HttpResponse(null, { status })),
      );
      renderViewer(String(expected.id));

      expect(
        await screen.findByRole("heading", {
          name: "문서를 불러오지 못했어요",
        }),
      ).toBeInTheDocument();
    },
  );

  it("주소의 문서 id가 숫자가 아니어도 확인하지 않고 그대로 요청하고, 서버가 거절하면 문서를 불러오지 못했다고 알린다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => {
        requestCount += 1;
        // 서버는 숫자가 아닌 문서 id를 잘못된 요청으로 거절해요
        return new HttpResponse(null, { status: 400 });
      }),
    );
    renderViewer("abc");

    expect(
      await screen.findByRole("heading", {
        name: "문서를 불러오지 못했어요",
      }),
    ).toBeInTheDocument();
    expect(requestCount).toBe(1);
  });

  it("서버 오류면 문서를 불러오지 못했다고 알리고, 다시 시도를 누르면 다시 불러와 본문을 보여준다", async () => {
    mockServer.use(
      http.get(
        DOCUMENT_REQUEST,
        () => new HttpResponse(null, { status: 500 }),
        { once: true },
      ),
    );
    renderViewer(String(expected.id));

    expect(
      await screen.findByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(
      await screen.findByRole("heading", { level: 2, name: expected.title }),
    ).toBeInTheDocument();
  });

  it("네트워크가 끊겨도 문서를 불러오지 못했다고 알리고, 다시 시도를 누르면 다시 불러와 본문을 보여준다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => HttpResponse.error(), { once: true }),
    );
    renderViewer(String(expected.id));

    expect(
      await screen.findByRole("heading", { name: "문서를 불러오지 못했어요" }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

    expect(
      await screen.findByRole("heading", { level: 2, name: expected.title }),
    ).toBeInTheDocument();
  });

  it("정리 화면에서 넘어오지 않은 문서에는 스테퍼를 그리지 않고 같은 녹음의 문서도 받지 않으며, 본문 아래 줄에 확인 버튼만 남긴다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return HttpResponse.json(documentsResponse);
      }),
    );
    renderViewer(String(expected.id));

    const confirmButton = await screen.findByRole("button", {
      name: CONFIRM_BUTTON_NAME,
    });
    // 스테퍼가 그려진다면 같은 녹음의 문서를 받아 그릴 시간을 줘요
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

    const viewer = screen.getByRole("region", { name: expected.title });
    const confirmBar = viewer.lastElementChild as HTMLElement;

    expect(
      screen.queryByRole("group", { name: STEPPER_NAME }),
    ).not.toBeInTheDocument();
    expect(requestCount).toBe(0);
    expect(within(confirmBar).getAllByRole("button")).toEqual([confirmButton]);
  });

  it("정리 화면에서 넘어오지 않았고 확인 대상도 아니면 본문 아래 줄을 남기지 않는다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json({
          ...documentDetailsResponse[0],
          myConfirmationState: "NOT_REQUIRED",
        }),
      ),
    );
    renderViewer(String(expected.id));

    const viewer = await screen.findByRole("region", { name: expected.title });
    // 스테퍼가 그려진다면 같은 녹음의 문서를 받아 그릴 시간을 줘요
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

    expect(viewer.lastElementChild).toBeEmptyDOMElement();
    expect(viewer.lastElementChild).not.toBeVisible();
  });

  it("같은 녹음에서 나온 문서 가운데 지금 문서가 몇 번째인지 보여준다", async () => {
    const position =
      recordingDocuments.findIndex(({ id }) => id === expected.id) + 1;

    renderViewerFromRecording(expected.id);

    expect(await findStepper()).toHaveTextContent(
      `${position} / ${recordingDocuments.length}`,
    );
  });

  it("다음을 누르면 같은 녹음의 다음 문서로 가고(정리 화면 쿼리는 남겨요), 뒤로 가면 앞 문서로 돌아온다", async () => {
    const [first, second] = recordingDocuments;
    const { router } = renderViewerFromRecording(first.id);

    await click(getStepButtons(await findStepper()).nextButton);

    expect(
      await screen.findByRole("heading", {
        level: 2,
        name: getDocumentTitle(second.id),
      }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(getDocumentPath(second.id));
    expect(router.state.location.search).toBe(FROM_RECORDING_SEARCH);

    await act(async () => {
      await router.navigate(-1);
    });

    expect(
      await screen.findByRole("heading", {
        level: 2,
        name: getDocumentTitle(first.id),
      }),
    ).toBeInTheDocument();
  });

  it("이전을 누르면 같은 녹음의 앞 문서로 가고 정리 화면 쿼리는 남긴다", async () => {
    const [first, second] = recordingDocuments;
    const { router } = renderViewerFromRecording(second.id);

    await click(getStepButtons(await findStepper()).previousButton);

    expect(
      await screen.findByRole("heading", {
        level: 2,
        name: getDocumentTitle(first.id),
      }),
    ).toBeInTheDocument();
    expect(router.state.location.search).toBe(FROM_RECORDING_SEARCH);
  });

  it("첫 문서에서는 이전을, 마지막 문서에서는 다음을 누를 수 없다", async () => {
    const { router } = renderViewerFromRecording(firstRecordingDocument.id);

    const firstButtons = getStepButtons(await findStepper());

    expect(firstButtons.previousButton).toBeDisabled();
    expect(firstButtons.nextButton).toBeEnabled();

    await act(async () => {
      await router.navigate(
        `${getDocumentPath(lastRecordingDocument.id)}${FROM_RECORDING_SEARCH}`,
      );
    });
    await screen.findByRole("heading", {
      level: 2,
      name: getDocumentTitle(lastRecordingDocument.id),
    });

    const lastButtons = getStepButtons(await findStepper());

    expect(lastButtons.previousButton).toBeEnabled();
    expect(lastButtons.nextButton).toBeDisabled();
  });

  it("스테퍼로 다른 문서로 넘어가면 앞 문서의 「복사됨」 표시가 남지 않는다", async () => {
    const [first, second] = recordingDocuments;
    renderViewerFromRecording(first.id);

    // 두 문서를 한 번씩 열어 둬요. 받아 둔 문서는 다시 넘길 때 불러오는 중 화면 없이 바로 바뀌어요
    await click(getStepButtons(await findStepper()).nextButton);
    await screen.findByRole("heading", {
      level: 2,
      name: getDocumentTitle(second.id),
    });
    await click(getStepButtons(await findStepper()).previousButton);
    await screen.findByRole("heading", {
      level: 2,
      name: getDocumentTitle(first.id),
    });

    await click(screen.getByRole("button", { name: "복사" }));
    expect(screen.getByRole("button", { name: "복사됨" })).toBeInTheDocument();

    await click(getStepButtons(await findStepper()).nextButton);
    await screen.findByRole("heading", {
      level: 2,
      name: getDocumentTitle(second.id),
    });

    expect(
      screen.queryByRole("button", { name: "복사됨" }),
    ).not.toBeInTheDocument();
  });

  it("문서가 하나뿐인 녹음이면 1 / 1을 보여주고 이전 · 다음을 누를 수 없다", async () => {
    const onlyDocument = documentsResponse.items.find(
      ({ id }) => id === expected.id,
    );
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () =>
        HttpResponse.json({
          topics: [],
          items: onlyDocument === undefined ? [] : [onlyDocument],
          nextCursor: null,
        } satisfies DocumentsResponse),
      ),
    );
    renderViewerFromRecording(expected.id);

    const stepper = await findStepper();
    const { previousButton, nextButton } = getStepButtons(stepper);

    expect(stepper).toHaveTextContent("1 / 1");
    expect(previousButton).toBeDisabled();
    expect(nextButton).toBeDisabled();
  });

  it("확인 대상이 아니어도 스테퍼는 보여준다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json({
          ...documentDetailsResponse[0],
          myConfirmationState: "NOT_REQUIRED",
        }),
      ),
    );
    renderViewerFromRecording(expected.id);

    expect(await findStepper()).toBeInTheDocument();
    expect(queryConfirmButton()).not.toBeInTheDocument();
  });

  it("같은 녹음의 문서를 불러오지 못하면 스테퍼만 그리지 않고 문서는 그대로 보여준다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderViewerFromRecording(expected.id);

    await screen.findByRole("heading", { level: 2, name: expected.title });
    await waitFor(() => expect(requestCount).toBe(1));

    expect(
      screen.queryByRole("group", { name: STEPPER_NAME }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: expected.title }),
    ).toBeInTheDocument();
  });

  it("확인 대상이 아니고 같은 녹음의 문서도 불러오지 못하면 본문 아래 줄을 남기지 않는다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () =>
        HttpResponse.json({
          ...documentDetailsResponse[0],
          myConfirmationState: "NOT_REQUIRED",
        }),
      ),
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderViewerFromRecording(expected.id);

    const viewer = await screen.findByRole("region", { name: expected.title });
    await waitFor(() => expect(requestCount).toBe(1));

    // 아래 줄은 문서 보기의 마지막 줄이에요. 스테퍼도 확인 버튼도 없으면 구분선과 여백까지 감춰요
    expect(viewer.lastElementChild).toBeEmptyDOMElement();
    expect(viewer.lastElementChild).not.toBeVisible();
  });

  it("같은 녹음의 문서 목록에 지금 문서가 없으면 스테퍼를 그리지 않는다", async () => {
    let requestCount = 0;
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, () => {
        requestCount += 1;

        return HttpResponse.json({
          topics: [],
          items: documentsResponse.items.filter(
            ({ id, recordingSessionId }) =>
              recordingSessionId === expected.recordingSessionId &&
              id !== expected.id,
          ),
          nextCursor: null,
        } satisfies DocumentsResponse);
      }),
    );
    renderViewerFromRecording(expected.id);

    await screen.findByRole("heading", { level: 2, name: expected.title });
    await waitFor(() => expect(requestCount).toBe(1));

    expect(
      screen.queryByRole("group", { name: STEPPER_NAME }),
    ).not.toBeInTheDocument();
  });

  it("같은 녹음의 문서를 여러 번에 나눠 받아도 요청마다 녹음 id를 붙이고, 받은 문서를 모두 센다", async () => {
    const requestedRecordingIds: (string | null)[] = [];

    // 요청한 size와 관계없이 1개씩만 줘서 여러 번 받게 해요
    mockServer.use(
      http.get(DOCUMENTS_REQUEST, ({ request }) => {
        const { searchParams } = new URL(request.url);
        const recordingSessionId = searchParams.get("recordingSessionId");

        requestedRecordingIds.push(recordingSessionId);

        return HttpResponse.json(
          findDocuments({
            cursor: searchParams.get("cursor"),
            size: 1,
            recordingSessionId: Number(recordingSessionId),
            myConfirmation: null,
          }),
        );
      }),
    );
    renderViewerFromRecording(firstRecordingDocument.id);

    expect(await findStepper()).toHaveTextContent(
      `1 / ${recordingDocuments.length}`,
    );
    expect(requestedRecordingIds).toEqual(
      recordingDocuments.map(() => String(expected.recordingSessionId)),
    );
  });

  it("로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
    mockServer.use(
      http.get(DOCUMENT_REQUEST, () => new HttpResponse(null, { status: 401 })),
    );
    renderViewer(String(expected.id));

    expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
  });
});
