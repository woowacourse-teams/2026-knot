import { FEEDBACK_RELAY_PATH } from "@api/fetch/relay/f";
import { mockServer } from "@api/mock/server";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import FeedbackPanel from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const FEEDBACK_REQUEST = `*${FEEDBACK_RELAY_PATH}`;
const FAILED_MESSAGE =
  "보내지 못했어요. 적은 내용은 그대로 있으니 다시 보내 주세요.";

const renderPanel = () => {
  const onClose = vi.fn();
  const queryClient = new QueryClient();

  const rendered = render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ToastProvider>
            <FeedbackPanel onClose={onClose} />
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { ...rendered, onClose };
};

const getSendButton = () => screen.getByRole("button", { name: /보내기/ });
const getContentField = () => screen.getByRole("textbox", { name: "내용" });

/** 보낸 요청의 본문을 남기고, 정해 둔 응답을 돌려줘요 */
const captureFeedbackRequests = (
  createResponse: () => Response | Promise<Response> = () =>
    new HttpResponse(null, { status: 201 }),
) => {
  const bodies: unknown[] = [];
  mockServer.use(
    http.post(FEEDBACK_REQUEST, async ({ request }) => {
      bodies.push(await request.json());

      return createResponse();
    }),
  );

  return bodies;
};

describe("FeedbackPanel", () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.mocked(captureAnalyticsEvent).mockClear();
  });

  describe("유형과 내용", () => {
    it("유형을 고르기 전에는 「보내기」가 꺼져 있다", () => {
      renderPanel();

      expect(getSendButton()).toBeDisabled();
    });

    it("유형 하나를 고르면 내용이 비어 있어도 「보내기」가 켜진다", () => {
      renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));

      expect(getContentField()).toHaveValue("");
      expect(getSendButton()).toBeEnabled();
    });

    it("유형 칩은 불편해요, 오류가 있어요, 아이디어가 있어요, 좋았어요 순서다", () => {
      renderPanel();

      expect(
        screen
          .getAllByRole("radio")
          .map((radio) => radio.parentElement?.textContent),
      ).toEqual(["불편해요", "오류가 있어요", "아이디어가 있어요", "좋았어요"]);
    });

    it("입력창 안내 문구는 「(선택)」으로 시작한다", () => {
      renderPanel();

      expect(getContentField().getAttribute("placeholder")).toMatch(
        /^\(선택\)/,
      );
    });

    it("고른 유형과 적은 내용은 패널을 닫았다 다시 열어도 그대로다", () => {
      const { unmount } = renderPanel();
      fireEvent.click(screen.getByRole("radio", { name: "아이디어가 있어요" }));
      fireEvent.change(getContentField(), {
        target: { value: "표로 내보내기" },
      });

      unmount();
      renderPanel();

      expect(
        screen.getByRole("radio", { name: "아이디어가 있어요" }),
      ).toBeChecked();
      expect(getContentField()).toHaveValue("표로 내보내기");
    });

    it("열면 유형 칩으로 포커스를 옮긴다", () => {
      renderPanel();

      expect(screen.getByRole("radio", { name: "불편해요" })).toHaveFocus();
    });

    it("X를 누르면 패널을 닫는다", () => {
      const { onClose } = renderPanel();

      fireEvent.click(screen.getByRole("button", { name: "닫기" }));

      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  describe("보내기", () => {
    it("고른 유형, 앞뒤 공백을 지운 내용, 보고 있는 화면 주소를 보낸다", async () => {
      const bodies = captureFeedbackRequests();
      renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));
      fireEvent.change(getContentField(), {
        target: { value: "  표가 깨져요 " },
      });
      fireEvent.click(getSendButton());

      await waitFor(() => expect(bodies).toHaveLength(1));
      expect(bodies[0]).toEqual({
        type: "bug",
        content: "표가 깨져요",
        pageUrl: window.location.href,
      });
    });

    it("보내는 동안에는 「보내기」가 로딩이고, X와 유형 · 내용을 막는다", async () => {
      captureFeedbackRequests(async () => {
        await delay("infinite");
        return new HttpResponse(null, { status: 201 });
      });
      renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "좋았어요" }));
      fireEvent.click(getSendButton());

      await waitFor(() =>
        expect(getSendButton()).toHaveAttribute("aria-busy", "true"),
      );
      expect(screen.getByRole("button", { name: "닫기" })).toBeDisabled();
      expect(screen.getByRole("radio", { name: "좋았어요" })).toBeDisabled();
      expect(getContentField()).toBeDisabled();
    });

    it("보내면 패널을 닫고 완료 토스트를 띄운 뒤 적은 내용을 비운다", async () => {
      captureFeedbackRequests();
      const { onClose, unmount } = renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "불편해요" }));
      fireEvent.change(getContentField(), { target: { value: "느려요" } });
      fireEvent.click(getSendButton());

      expect(
        await screen.findByText(TOAST_MESSAGES.FEEDBACK_SENT.message),
      ).toBeInTheDocument();
      expect(onClose).toHaveBeenCalledTimes(1);

      unmount();
      renderPanel();

      expect(screen.getByRole("radio", { name: "불편해요" })).not.toBeChecked();
      expect(getContentField()).toHaveValue("");
    });

    it("보낸 뒤에만 PostHog `survey sent`에 유형 문구, 점수, 내용을 보낸다", async () => {
      captureFeedbackRequests();
      const { onClose } = renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));
      fireEvent.change(getContentField(), { target: { value: "표가 깨져요" } });
      fireEvent.click(getSendButton());

      await waitFor(() => expect(onClose).toHaveBeenCalled());
      expect(captureAnalyticsEvent).toHaveBeenCalledTimes(1);
      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "survey sent",
        properties: {
          feedback_type: "오류가 있어요",
          feedback_score: 1,
          feedback_content: "표가 깨져요",
        },
      });
    });

    it.each([
      { label: "불편해요", score: 2 },
      { label: "아이디어가 있어요", score: 3 },
      { label: "좋았어요", score: 4 },
    ])(
      "「$label」은 점수 $score로 보내고, 내용을 적지 않았으면 내용은 넣지 않는다",
      async ({ label, score }) => {
        captureFeedbackRequests();
        const { onClose } = renderPanel();

        fireEvent.click(screen.getByRole("radio", { name: label }));
        fireEvent.click(getSendButton());

        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(captureAnalyticsEvent).toHaveBeenCalledWith({
          event: "survey sent",
          properties: { feedback_type: label, feedback_score: score },
        });
      },
    );

    it("보내지 못하면 패널을 닫지 않고 안내 문구와 「다시 보내기」를 보여 주며 적은 내용은 남긴다", async () => {
      captureFeedbackRequests(() => new HttpResponse(null, { status: 502 }));
      const { onClose } = renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));
      fireEvent.change(getContentField(), { target: { value: "표가 깨져요" } });
      fireEvent.click(getSendButton());

      expect(await screen.findByRole("alert")).toHaveTextContent(
        FAILED_MESSAGE,
      );
      expect(getSendButton()).toHaveTextContent("다시 보내기");
      expect(getContentField()).toHaveValue("표가 깨져요");
      expect(onClose).not.toHaveBeenCalled();
      expect(captureAnalyticsEvent).not.toHaveBeenCalled();
    });

    it("「다시 보내기」로 보내면 패널을 닫는다", async () => {
      let attempt = 0;
      captureFeedbackRequests(
        () => new HttpResponse(null, { status: attempt++ === 0 ? 502 : 201 }),
      );
      const { onClose } = renderPanel();

      fireEvent.click(screen.getByRole("radio", { name: "오류가 있어요" }));
      fireEvent.click(getSendButton());
      await screen.findByRole("alert");
      fireEvent.click(screen.getByRole("button", { name: "다시 보내기" }));

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    });
  });
});
