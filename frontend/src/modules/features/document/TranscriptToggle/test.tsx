import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { useTranscriptPanelStore } from "@store/transcriptPanelStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import TranscriptToggle from ".";

const ME_REQUEST = `*${AUTH_ME_API_PATH}`;
/** 원문이 닫혀 있을 때의 버튼 이름 */
const OPEN_LABEL = "원문 보기";
/** 원문이 열려 있을 때의 버튼 이름 */
const CLOSE_LABEL = "원문 닫기";

/** 문서 보기 화면의 주소 */
const DOCUMENT_PATH = "/workspace/1/documents/101";
/** 녹음 문서 화면의 주소 */
const RECORDING_DOCUMENT_PATH = "/workspace/1/recordings/42/document";

interface RenderToggleParams {
  /** 버튼을 놓을 화면의 주소. 기본은 문서 보기예요 */
  pathname?: string;
  /** 화면에 들어올 때마다 바뀌는 방문 기록의 키. 같은 키면 같은 방문으로 봐요 */
  visitKey?: string;
}

const renderToggle = ({
  pathname = DOCUMENT_PATH,
  visitKey = "first-visit",
}: RenderToggleParams = {}) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[{ pathname, key: visitKey }]}>
          <TranscriptToggle />
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  /**
   * 회원 정보 요청이 끝날 때까지 기다려요.
   * 회원 정보를 받기 전과 닫아 둔 회원은 버튼이 같은 모습이라, 버튼만 봐서는 요청이 끝났는지 알 수 없어요
   */
  const waitForMember = () =>
    waitFor(() => expect(queryClient.isFetching()).toBe(0));

  return { waitForMember };
};

/** 새로고침하고 다시 들어온 것처럼 화면과 이번 방문에서 정한 값을 지워요. 브라우저에 남긴 값은 그대로 둬요 */
const startNewVisit = () => {
  cleanup();
  useTranscriptPanelStore.setState({
    isOpenThisVisit: null,
    recordingDocumentChoice: null,
  });
};

describe("TranscriptToggle", () => {
  beforeEach(() => {
    // 열림 여부를 브라우저에 남기므로 테스트마다 지워요
    localStorage.clear();
  });

  afterEach(() => {
    // 전역 저장소라 이번 방문에서 정한 값이 테스트끼리 새지 않도록 처음 상태로 되돌려요
    useTranscriptPanelStore.setState({
      isOpenThisVisit: null,
      recordingDocumentChoice: null,
    });
  });

  it("원문을 여닫은 적이 없는 회원에게는 열린 상태의 '원문 닫기' 버튼을 둔다 (DOC-R3)", async () => {
    renderToggle();

    expect(
      await screen.findByRole("button", { name: CLOSE_LABEL }),
    ).toHaveAttribute("aria-expanded", "true");
  });

  it("회원 정보를 받기 전에는 닫힌 상태의 '원문 보기' 버튼을 둔다", () => {
    renderToggle();

    expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("누르면 닫고, 다시 누르면 연다", async () => {
    renderToggle();

    fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));

    expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
      "aria-expanded",
      "false",
    );

    fireEvent.click(screen.getByRole("button", { name: OPEN_LABEL }));

    expect(screen.getByRole("button", { name: CLOSE_LABEL })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  it("닫아 둔 회원이 다시 들어오면 닫힌 상태로 시작한다 (DOC-R3)", async () => {
    renderToggle();
    fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));

    startNewVisit();
    const { waitForMember } = renderToggle();
    await waitForMember();

    expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("닫았다가 다시 열어 둔 회원이 다시 들어오면 열린 상태로 시작한다 (DOC-R3)", async () => {
    renderToggle();
    fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));
    fireEvent.click(screen.getByRole("button", { name: OPEN_LABEL }));

    startNewVisit();
    renderToggle();

    expect(
      await screen.findByRole("button", { name: CLOSE_LABEL }),
    ).toBeInTheDocument();
  });

  it("다른 회원이 닫아 둔 상태는 따르지 않는다 (DOC-R3)", async () => {
    renderToggle();
    fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));

    startNewVisit();
    mockServer.use(
      http.get(ME_REQUEST, () =>
        HttpResponse.json({
          ...meResponse,
          memberId: meResponse.memberId + 1,
        }),
      ),
    );
    renderToggle();

    expect(
      await screen.findByRole("button", { name: CLOSE_LABEL }),
    ).toBeInTheDocument();
  });

  it("회원 정보를 받지 못해도 누르면 연다", async () => {
    mockServer.use(
      http.get(ME_REQUEST, () => new HttpResponse(null, { status: 500 })),
    );
    const { waitForMember } = renderToggle();
    await waitForMember();

    fireEvent.click(screen.getByRole("button", { name: OPEN_LABEL }));

    expect(screen.getByRole("button", { name: CLOSE_LABEL })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  it("브라우저에 남길 수 없어도 열린 상태로 시작하고 누르면 닫는다", async () => {
    const blockStorage = () => {
      throw new Error("저장소를 쓸 수 없어요");
    };
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(blockStorage);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(blockStorage);
    renderToggle();

    fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));

    expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  describe("녹음 문서 화면 (STT-R39)", () => {
    it("문서 보기에서 원문을 닫아 둔 회원에게도 열린 상태로 시작한다", async () => {
      renderToggle();
      fireEvent.click(await screen.findByRole("button", { name: CLOSE_LABEL }));

      startNewVisit();
      const { waitForMember } = renderToggle({
        pathname: RECORDING_DOCUMENT_PATH,
      });
      await waitForMember();

      expect(screen.getByRole("button", { name: CLOSE_LABEL })).toHaveAttribute(
        "aria-expanded",
        "true",
      );
    });

    it("녹음 문서에서 닫아도 문서 보기의 다음번 상태는 바뀌지 않는다", async () => {
      renderToggle({ pathname: RECORDING_DOCUMENT_PATH });
      fireEvent.click(screen.getByRole("button", { name: CLOSE_LABEL }));

      expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
        "aria-expanded",
        "false",
      );

      startNewVisit();
      renderToggle();

      expect(
        await screen.findByRole("button", { name: CLOSE_LABEL }),
      ).toBeInTheDocument();
    });

    it("녹음 문서에 다시 들어오면 닫아 둔 원문이 다시 열려 있다", () => {
      renderToggle({ pathname: RECORDING_DOCUMENT_PATH });
      fireEvent.click(screen.getByRole("button", { name: CLOSE_LABEL }));

      cleanup();
      renderToggle({
        pathname: RECORDING_DOCUMENT_PATH,
        visitKey: "second-visit",
      });

      expect(screen.getByRole("button", { name: CLOSE_LABEL })).toHaveAttribute(
        "aria-expanded",
        "true",
      );
    });

    it("같은 방문 안에서는 닫아 둔 상태가 이어진다", () => {
      renderToggle({ pathname: RECORDING_DOCUMENT_PATH });
      fireEvent.click(screen.getByRole("button", { name: CLOSE_LABEL }));

      cleanup();
      renderToggle({ pathname: RECORDING_DOCUMENT_PATH });

      expect(screen.getByRole("button", { name: OPEN_LABEL })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    });
  });
});
