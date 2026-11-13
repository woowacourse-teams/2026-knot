import { GetRecordingResponseDto } from "@api/dto/recording";
import { GetMeResponseDto } from "@api/dto/auth";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { delay, http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingCard from ".";
import { useRecordingStore } from "@store/recordingStore";
import {
  getRecordingControlProof,
  saveRecordingResumeProof,
  clearRecordingStartProof,
} from "@utils/recordingControlProof";
import { recordingDetailsResponse } from "@api/mock/responses/recording";

import { MemoryRouter } from "react-router";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

const expected = new GetMeResponseDto(meResponse);
const GUIDE_TEXT =
  "녹음을 끝내면 대화를 주제별 문서로 정리해 문서 탭에 저장해요.";

const renderCard = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <RecordingCard />
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
};

describe("RecordingCard", () => {
  it("회원 정보 응답의 닉네임으로 녹음 제목을 보여준다", async () => {
    renderCard();

    expect(
      await screen.findByRole("heading", {
        name: `${expected.nickname} 님의 녹음`,
      }),
    ).toBeInTheDocument();
  });

  it("응답 전에는 제목과 안내 대신 불러오는 중으로 표시한다", () => {
    mockServer.use(
      http.get(`*${AUTH_ME_API_PATH}`, async () => {
        await delay("infinite");
        return HttpResponse.json(meResponse);
      }),
    );
    renderCard();

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByText(GUIDE_TEXT)).not.toBeInTheDocument();
  });

  it("회원 정보를 못 받으면 닉네임 없이 녹음으로 보여준다", async () => {
    mockServer.use(
      http.get(
        `*${AUTH_ME_API_PATH}`,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    renderCard();

    expect(
      await screen.findByRole("heading", { name: "녹음" }),
    ).toBeInTheDocument();
  });
});

describe("참가자 수 설정", () => {
  const session = {
    workspaceId: 1,
    recordingId: 101,
    audioMode: "FINAL_WEBM_V1" as const,
  };
  const base = recordingDetailsResponse[0].detail;
  afterEach(() => {
    useRecordingStore.setState({
      status: "idle",
      session: null,
      isEnding: false,
    });
    clearRecordingStartProof();
  });
  const start = (status: "recording" | "paused" = "recording") => {
    saveRecordingResumeProof(session);
    useRecordingStore.setState({ status, session });
  };
  it("자동 감지에서 1명으로 저장하고 1명에서 자동 감지로 돌아가며 제어 증명을 보낸다", async () => {
    start();
    let saved: number | null = null;
    const bodies: unknown[] = [];
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId", () =>
        HttpResponse.json({
          ...base,
          status: "RECORDING",
          speakerCount: saved,
        }),
      ),
      http.patch(
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speaker-count",
        async ({ request }) => {
          const body = (await request.json()) as {
            speakerCount: number | null;
          };
          bodies.push(body);
          saved = body.speakerCount;
          return HttpResponse.json({ recordingId: 101, speakerCount: saved });
        },
      ),
    );
    renderCard();
    const more = await screen.findByRole("button", {
      name: "참가자 수 늘리기",
    });
    await waitFor(() => expect(more).toBeEnabled());
    expect(screen.getByText("자동 감지")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "참가자 수 줄이기" }),
    ).toBeDisabled();
    fireEvent.click(more);
    await screen.findByText("1명");
    await waitFor(() => expect(more).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "참가자 수 줄이기" }));
    await screen.findByText("자동 감지");
    expect(bodies).toEqual([
      { ...getRecordingControlProof(), speakerCount: 1 },
      { ...getRecordingControlProof(), speakerCount: null },
    ]);
  });
  it("일시정지 중에도 설정하며 10명에서는 늘리기를 막는다", async () => {
    start("paused");
    const response = { ...base, status: "PAUSED" as const, speakerCount: 10 };
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId", () =>
        HttpResponse.json(response),
      ),
    );
    renderCard();
    await screen.findByText(
      `${new GetRecordingResponseDto(response).speakerCount}명`,
    );
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "참가자 수 줄이기" }),
      ).toBeEnabled(),
    );
    expect(
      screen.getByRole("button", { name: "참가자 수 늘리기" }),
    ).toBeDisabled();
  });
  it("저장이 거절되면 서버의 마지막 값과 오류를 유지한다", async () => {
    start();
    const response = { ...base, status: "RECORDING" as const, speakerCount: 2 };
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId", () =>
        HttpResponse.json(response),
      ),
      http.patch(
        "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speaker-count",
        () =>
          HttpResponse.json(
            {
              code: "RECORDING_CONTROL_CONFLICT",
              message: "제어권이 없습니다.",
            },
            { status: 409 },
          ),
      ),
    );
    renderCard();
    const more = await screen.findByRole("button", {
      name: "참가자 수 늘리기",
    });
    await waitFor(() => expect(more).toBeEnabled());
    fireEvent.click(more);
    await screen.findByRole("alert");
    expect(
      screen.getByText(
        `${new GetRecordingResponseDto(response).speakerCount}명`,
      ),
    ).toBeInTheDocument();
  });
  it("녹음 종료 요청 중에는 참가자 수를 변경하지 못한다", async () => {
    start();
    useRecordingStore.setState({ isEnding: true });
    const response = { ...base, status: "RECORDING" as const, speakerCount: 2 };
    mockServer.use(
      http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId", () =>
        HttpResponse.json(response),
      ),
    );
    renderCard();
    await screen.findByText(
      `${new GetRecordingResponseDto(response).speakerCount}명`,
    );
    expect(
      screen.getByRole("button", { name: "참가자 수 늘리기" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "참가자 수 줄이기" }),
    ).toBeDisabled();
  });

  describe("저장 결과 사용 기록", () => {
    const SPEAKER_COUNT_REQUEST =
      "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speaker-count";
    const recordingTarget = {
      recording_id: session.recordingId,
      workspace_id: session.workspaceId,
    };

    // 앞 테스트에서도 사용 기록이 남을 수 있어, 테스트마다 기록을 비우고 시작해요
    beforeEach(() => {
      vi.mocked(captureAnalyticsEvent).mockClear();
    });

    /** 참가자 수 저장 결과만 골라 그 속성을 보낸 순서대로 모아요 */
    const getSavedProperties = () =>
      vi
        .mocked(captureAnalyticsEvent)
        .mock.calls.map(([event]) => event)
        .filter(({ event }) => event === "speaker_count_saved")
        .map(({ properties }) => properties);

    /** 저장된 참가자 수가 `speakerCount`인 녹음 중 녹음을 보여 주고, 늘리기 버튼을 누를 수 있을 때까지 기다려요 */
    const renderWithSpeakerCount = async (speakerCount: number | null) => {
      start();
      mockServer.use(
        http.get(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId",
          () =>
            HttpResponse.json({ ...base, status: "RECORDING", speakerCount }),
        ),
      );
      renderCard();
      const more = await screen.findByRole("button", {
        name: "참가자 수 늘리기",
      });
      await waitFor(() => expect(more).toBeEnabled());

      return { more };
    };

    it("수를 정해 저장하면 정한 수를, 자동 감지로 돌려 저장하면 자동으로 저장했다고 보낸다", async () => {
      let saved: number | null = null;
      mockServer.use(
        http.get(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId",
          () =>
            HttpResponse.json({
              ...base,
              status: "RECORDING",
              speakerCount: saved,
            }),
        ),
        http.patch(SPEAKER_COUNT_REQUEST, async ({ request }) => {
          saved = ((await request.json()) as { speakerCount: number | null })
            .speakerCount;
          return HttpResponse.json({ recordingId: 101, speakerCount: saved });
        }),
      );
      start();
      renderCard();
      const more = await screen.findByRole("button", {
        name: "참가자 수 늘리기",
      });
      await waitFor(() => expect(more).toBeEnabled());

      fireEvent.click(more);
      await screen.findByText("1명");
      await waitFor(() => expect(more).toBeEnabled());
      fireEvent.click(screen.getByRole("button", { name: "참가자 수 줄이기" }));
      await screen.findByText("자동 감지");

      await waitFor(() =>
        expect(getSavedProperties()).toEqual([
          {
            result: "saved",
            is_auto: false,
            speaker_count: 1,
            ...recordingTarget,
          },
          { result: "saved", is_auto: true, ...recordingTarget },
        ]),
      );
    });

    it("저장이 거절되면 실패와 저장하려던 수를 보낸다", async () => {
      mockServer.use(
        http.patch(SPEAKER_COUNT_REQUEST, () =>
          HttpResponse.json(
            {
              code: "RECORDING_CONTROL_CONFLICT",
              message: "제어권이 없습니다.",
            },
            { status: 409 },
          ),
        ),
      );
      const { more } = await renderWithSpeakerCount(2);

      fireEvent.click(more);

      await screen.findByRole("alert");
      expect(getSavedProperties()).toEqual([
        {
          result: "failed",
          is_auto: false,
          speaker_count: 3,
          ...recordingTarget,
        },
      ]);
    });

    it("저장에서 로그인이 풀렸으면(401) 보내지 않는다", async () => {
      let isPatched = false;
      mockServer.use(
        http.patch(SPEAKER_COUNT_REQUEST, () => {
          isPatched = true;
          return new HttpResponse(null, { status: 401 });
        }),
      );
      const { more } = await renderWithSpeakerCount(2);

      fireEvent.click(more);

      await waitFor(() => expect(isPatched).toBe(true));
      await screen.findByRole("alert");
      expect(getSavedProperties()).toEqual([]);
    });
  });
});
