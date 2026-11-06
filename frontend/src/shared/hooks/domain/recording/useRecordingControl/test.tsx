import { createHttpError, HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { setupSentryEvents } from "@provider/sentry/mock";
import { theme } from "@provider/themeProvider";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, screen, waitFor } from "@testing-library/react";
import {
  clearRecordingStartProof,
  getRecordingControlProof,
  getRecordingResumeProof,
  getRecordingStartProof,
  saveRecordingResumeProof,
} from "@utils/recordingControlProof";
import { http, HttpResponse, type PathParams } from "msw";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  PostRecordingEndResponseDto,
  type PostRecordingEndRequestDto,
} from "@api/dto/recording";
import {
  recordingAudioChunkCompleteResponse,
  recordingAudioChunkReserveResponse,
  recordingAudioSaveResponse,
  recordingEndResponse,
  recordingHeartbeatResponse,
  recordingPauseResponse,
  recordingResumeResponse,
  recordingStartResponse,
} from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import {
  type PcmCollectorParams,
  UnsupportedPcmCollectorError,
} from "@store/recordingStore/pcmCollector";
import {
  deleteRecordingChunks,
  readRecordingChunks,
  saveRecordingChunk,
} from "@store/recordingStore/recordingChunkArchive";

import useRecordingControl from ".";

const hoisted = vi.hoisted(() => {
  /** 수집기 호출과 서버 요청을 한 줄에 모아 순서를 봐요 */
  const events: string[] = [];

  /** 조각 하나의 프레임 수(5초) */
  const CHUNK_FRAMES = 80_000;

  /** 종료 요청은 가짜 타이머 아래에서 실패를 주입하기 위해 네트워크 대신 함수로 바꿔요 */
  const endRecordingApi =
    vi.fn<
      (params: {
        body: PostRecordingEndRequestDto;
      }) => Promise<PostRecordingEndResponseDto>
    >();

  /**
   * 실제 AudioWorklet 대신 쓰는 수집기. `feed`로 소리가 들어온 것처럼 프레임을 더하고,
   * `lose`로 마이크 트랙이 끝나거나 processor가 죽은 것처럼 알려요
   */
  class FakePcmCollector {
    static instances: FakePcmCollector[] = [];

    isCollecting = false;
    isLive = true;
    isClosed = false;
    private frames = 0;

    private constructor(private readonly params: PcmCollectorParams) {}

    static async create(params: PcmCollectorParams) {
      const collector = new FakePcmCollector(params);
      FakePcmCollector.instances.push(collector);

      return collector;
    }

    start() {
      this.isCollecting = true;
      events.push("start");
    }

    async flush() {
      events.push("flush");
      this.isCollecting = false;
      if (this.frames > 0) {
        this.params.onChunk(new ArrayBuffer(this.frames * 2));
        this.frames = 0;
      }
    }

    close() {
      this.isClosed = true;
    }

    feed(frames: number) {
      if (!this.isCollecting) return;

      this.frames += frames;
      while (this.frames >= CHUNK_FRAMES) {
        this.params.onChunk(new ArrayBuffer(CHUNK_FRAMES * 2));
        this.frames -= CHUNK_FRAMES;
      }
    }

    lose() {
      this.isLive = false;
      this.params.onLost();
    }
  }

  return { events, CHUNK_FRAMES, FakePcmCollector, endRecordingApi };
});
const { events, CHUNK_FRAMES, FakePcmCollector, endRecordingApi } = hoisted;

// 이 파일은 PCM 경로를 보므로 기본값과 상관없이 PCM으로 고정해요
vi.mock("@constants/recording", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@constants/recording")>()),
  RECORDING_AUDIO_MODE: "PCM16_CHUNKS_V1",
}));

// vi.mock은 파일 맨 위로 올라가 아래 구조 분해보다 먼저 실행되므로 hoisted 객체로 읽어요
vi.mock("@store/recordingStore/pcmCollector", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("@store/recordingStore/pcmCollector")
  >()),
  default: hoisted.FakePcmCollector,
}));

vi.mock(
  "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/end",
  () => ({ endRecordingApi: hoisted.endRecordingApi }),
);

const WORKSPACE_ID = 1;
const RECORDING_ID = 10;
const START_PATH = "*/api/v1/workspaces/:workspaceId/recordings";
const RECORDING_PATH =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId";
const RESERVE_PATH = `${RECORDING_PATH}/audio-chunks`;
/** BE 문서 예시: 3,200바이트(1,600프레임) 무음 PCM의 SHA-256 */
const SHORT_SILENCE_FRAMES = 1_600;
const SHORT_SILENCE_CHECKSUM = "WjEigd9L2N+7TUqUrQv0TQG7jPztEga5DiG0ygVozbE=";
/** 5초 무음 조각의 SHA-256. mock 예약 응답의 PUT 헤더와 같은 값이에요 */
const SILENT_CHUNK_CHECKSUM =
  recordingAudioChunkReserveResponse.requiredHeaders["x-amz-checksum-sha256"];
const EMPTY_SEAL = {
  lastSequence: 0,
  totalFrameCount: 0,
  pendingDescriptors: [],
};
const endedRecordingError = (code: string) =>
  createHttpError({ type: HTTP_ERROR_TYPE.conflict, code });

interface StartBody {
  requestId: string;
  audioMode?: string;
}

/** 조각 예약 요청 본문. 최초 탭 증명과 descriptor가 한 단계에 평평하게 담겨요 */
interface ReserveBody {
  tabId: string;
  controlToken: string;
  sequence: number;
  sampleOffset: number;
  frameCount: number;
  byteLength: number;
  checksumSha256: string;
}

const renderRecordingControl = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <DialogProvider>
          <MemoryRouter>
            <ToastProvider>{children}</ToastProvider>
          </MemoryRouter>
        </DialogProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return renderHook(() => useRecordingControl(), { wrapper }).result;
};

/** 서버 요청을 경로의 마지막 마디로 `events`에 함께 모아요. 조각 PUT은 번호라 `put`으로 적고, 첫 요청 앞의 CSRF 토큰 조회는 빼요 */
const collectRequests = () => {
  mockServer.events.on("request:start", ({ request }) => {
    const last = new URL(request.url).pathname.split("/").pop() ?? "";
    if (last === "csrf") return;

    events.push(request.method === "PUT" ? "put" : last);
  });
};

/** 시작 요청 본문만 남기고 응답은 기본 mock 핸들러에 맡겨요 */
const recordStartBodies = () => {
  const bodies: StartBody[] = [];
  mockServer.use(
    http.post<PathParams, StartBody>(START_PATH, async ({ request }) => {
      bodies.push(await request.clone().json());
    }),
  );

  return bodies;
};

/** 시작 요청을 차례로 응답해요. 응답이 떨어지면 기본 mock 응답으로 돌아가요. `recordStartBodies`보다 먼저 불러요 */
const respondStart = (...responses: (() => Response)[]) =>
  mockServer.use(http.post(START_PATH, () => responses.shift()?.()));

const conflict = (code: string) => () =>
  HttpResponse.json({ code }, { status: 409 });

const endedSession = () =>
  HttpResponse.json({ ...recordingStartResponse, status: "ENDED" });

const recordReserveBodies = () => {
  const bodies: ReserveBody[] = [];
  mockServer.use(
    http.post<PathParams, ReserveBody>(RESERVE_PATH, async ({ request }) => {
      bodies.push(await request.clone().json());
    }),
  );

  return bodies;
};

const HELD_RESPONSES = {
  pause: [`${RECORDING_PATH}/pause`, recordingPauseResponse],
  resume: [`${RECORDING_PATH}/resume`, recordingResumeResponse],
  reserve: [RESERVE_PATH, recordingAudioChunkReserveResponse],
  complete: [
    `${RESERVE_PATH}/:sequence/complete`,
    recordingAudioChunkCompleteResponse,
  ],
} as const;

/** 응답을 붙잡아 뒀다가 `respond`를 부를 때 차례로 열어 줘요 */
const holdResponses = (action: keyof typeof HELD_RESPONSES) => {
  const waiting: (() => void)[] = [];
  const [path, response] = HELD_RESPONSES[action];
  mockServer.use(
    http.post(path, async () => {
      await new Promise<void>((resolve) => waiting.push(resolve));

      return HttpResponse.json(response);
    }),
  );

  return { respond: () => waiting.shift()?.() };
};

/** 열어 준 응답이 큐까지 흘러갈 시간을 줘요 */
const flushRequests = () => new Promise((resolve) => setTimeout(resolve, 50));

const startRecording = async (
  result: ReturnType<typeof renderRecordingControl>,
) => {
  await act(async () => {
    await result.current.startRecording(WORKSPACE_ID);
  });

  return FakePcmCollector.instances[0];
};

/** 시작 결과(`started`, `refused` 등)를 돌려줘요 */
const startRecordingOutcome = async (
  result: ReturnType<typeof renderRecordingControl>,
) => {
  let outcome: Awaited<ReturnType<typeof result.current.startRecording>>;
  await act(async () => {
    outcome = await result.current.startRecording(WORKSPACE_ID);
  });

  return outcome!;
};

const getSessionAudioMode = () =>
  useRecordingStore.getState().session?.audioMode;

const pauseRecording = async (
  result: ReturnType<typeof renderRecordingControl>,
) => {
  await act(async () => {
    await result.current.pauseRecording();
  });
};

const endRecording = async (
  result: ReturnType<typeof renderRecordingControl>,
) => {
  let outcome: Awaited<
    ReturnType<typeof result.current.endRecordingWithoutConfirm>
  >;
  await act(async () => {
    outcome = await result.current.endRecordingWithoutConfirm();
  });

  return outcome!;
};

const getStatus = () => useRecordingStore.getState().status;
const isUploadingChunks = () => useRecordingStore.getState().isUploadingChunks;
const count = (name: string) => events.filter((e) => e === name).length;
/** 종료 요청 본문들. 가짜 종료 함수가 받은 순서대로예요 */
const endBodies = () => endRecordingApi.mock.calls.map(([{ body }]) => body);

describe("useRecordingControl의 PCM 조각 녹음", () => {
  afterEach(() => {
    useRecordingStore.getState().discardRecording();
    clearRecordingStartProof();
    mockServer.events.removeAllListeners();
    events.length = 0;
    FakePcmCollector.instances.length = 0;
    endRecordingApi.mockReset();
    vi.useRealTimers();
  });

  it("시작 요청에 audioMode를 보내고, 시작 응답을 받은 뒤 수집을 시작한다", async () => {
    collectRequests();
    const startBodies = recordStartBodies();
    const result = renderRecordingControl();

    const collector = await startRecording(result);

    expect(startBodies[0]).toMatchObject({ audioMode: "PCM16_CHUNKS_V1" });
    expect(useRecordingStore.getState().session).toEqual({
      workspaceId: WORKSPACE_ID,
      recordingId: RECORDING_ID,
      audioMode: "PCM16_CHUNKS_V1",
    });
    expect(events).toEqual(["recordings", "start"]);
    expect(collector.isCollecting).toBe(true);
  });

  describe("WebM으로 돌아가기", () => {
    it("서버가 조각 저장을 꺼 뒀으면(409 AUDIO_MODE_DISABLED) 거절로 보지 않고, 수집기만 닫은 채 새 증명으로 WebM 녹음을 시작한다", async () => {
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      respondStart(conflict("AUDIO_MODE_DISABLED"));
      const startBodies = recordStartBodies();
      const result = renderRecordingControl();

      const outcome = await startRecordingOutcome(result);

      expect(outcome).toBe("started");
      expect(startBodies.map((body) => body.audioMode)).toEqual([
        "PCM16_CHUNKS_V1",
        undefined,
      ]);
      expect(startBodies[1].requestId).not.toBe(startBodies[0].requestId);
      expect(getSessionAudioMode()).toBe("FINAL_WEBM_V1");
      expect(getStatus()).toBe("recording");
      expect(getUserMedia).toHaveBeenCalledTimes(1);
      expect(FakePcmCollector.instances[0].isClosed).toBe(true);
    });

    it("끝난 녹음 뒤 새 증명으로 다시 보낸 시작이 AUDIO_MODE_DISABLED여도 WebM으로 시작한다", async () => {
      getRecordingStartProof();
      respondStart(endedSession, conflict("AUDIO_MODE_DISABLED"));
      const startBodies = recordStartBodies();
      const result = renderRecordingControl();

      const outcome = await startRecordingOutcome(result);

      expect(outcome).toBe("started");
      expect(startBodies.map((body) => body.audioMode)).toEqual([
        "PCM16_CHUNKS_V1",
        "PCM16_CHUNKS_V1",
        undefined,
      ]);
      expect(getSessionAudioMode()).toBe("FINAL_WEBM_V1");
    });

    it("WebM으로 다시 보낸 시작이 거절되면 지금처럼 거절로 돌려준다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      respondStart(
        conflict("AUDIO_MODE_DISABLED"),
        conflict("ACTIVE_RECORDING_ALREADY_EXISTS"),
      );
      const result = renderRecordingControl();

      expect(await startRecordingOutcome(result)).toBe("refused");
      expect(getStatus()).toBe("idle");
    });

    it("16kHz 수집기를 만들 수 없는 브라우저면 받아 둔 마이크로 WebM 녹음을 시작하고, 다음 시작은 다시 PCM부터 시도한다", async () => {
      vi.spyOn(FakePcmCollector, "create").mockRejectedValueOnce(
        new UnsupportedPcmCollectorError(),
      );
      const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
      const startBodies = recordStartBodies();
      const result = renderRecordingControl();

      const outcome = await startRecordingOutcome(result);

      expect(outcome).toBe("started");
      expect(startBodies).toHaveLength(1);
      expect(startBodies[0]).not.toHaveProperty("audioMode");
      expect(getSessionAudioMode()).toBe("FINAL_WEBM_V1");
      expect(getUserMedia).toHaveBeenCalledTimes(1);
      expect(useRecordingStore.getState().analyser).not.toBeNull();

      useRecordingStore.getState().discardRecording();
      await startRecordingOutcome(result);

      expect(startBodies[1]).toMatchObject({ audioMode: "PCM16_CHUNKS_V1" });
      expect(getSessionAudioMode()).toBe("PCM16_CHUNKS_V1");
    });
  });

  describe("켜기 전 WebM으로 만든 증명이 남은 경우", () => {
    it("409 AUDIO_MODE_CONFLICT면 새 증명으로 한 번 더 PCM 시작을 요청한다", async () => {
      const leftoverRequestId = getRecordingStartProof().requestId;
      respondStart(conflict("AUDIO_MODE_CONFLICT"));
      const startBodies = recordStartBodies();
      const result = renderRecordingControl();

      const outcome = await startRecordingOutcome(result);

      expect(outcome).toBe("started");
      expect(startBodies.map((body) => body.audioMode)).toEqual([
        "PCM16_CHUNKS_V1",
        "PCM16_CHUNKS_V1",
      ]);
      expect(startBodies[0].requestId).toBe(leftoverRequestId);
      expect(startBodies[1].requestId).not.toBe(leftoverRequestId);
      expect(getSessionAudioMode()).toBe("PCM16_CHUNKS_V1");
    });

    it("옛 녹음이 살아 있어 다시 보낸 시작도 409면 거절로 돌려주고 더 보내지 않는다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      getRecordingStartProof();
      respondStart(
        conflict("AUDIO_MODE_CONFLICT"),
        conflict("ACTIVE_RECORDING_ALREADY_EXISTS"),
        conflict("AUDIO_MODE_CONFLICT"),
      );
      const startBodies = recordStartBodies();
      const result = renderRecordingControl();

      expect(await startRecordingOutcome(result)).toBe("refused");
      expect(startBodies).toHaveLength(2);
      expect(getStatus()).toBe("idle");
    });
  });

  it("일시정지는 남은 소리를 봉인해 큐에 넣은 뒤 서버에 알리고, 조각은 세션과 최초 탭 증명을 실어 올린다", async () => {
    collectRequests();
    const reserveBodies = recordReserveBodies();
    const result = renderRecordingControl();
    const collector = await startRecording(result);
    collector.feed(SHORT_SILENCE_FRAMES);

    await pauseRecording(result);

    expect(getStatus()).toBe("paused");
    expect(events.indexOf("flush")).toBeLessThan(events.indexOf("pause"));
    await waitFor(() => expect(events).toContain("complete"));
    expect(reserveBodies).toEqual([
      {
        ...getRecordingControlProof(),
        sequence: 1,
        sampleOffset: 0,
        frameCount: SHORT_SILENCE_FRAMES,
        byteLength: SHORT_SILENCE_FRAMES * 2,
        checksumSha256: SHORT_SILENCE_CHECKSUM,
      },
    ]);
  });

  it("이어서 녹음은 서버 재개가 성공한 뒤에 수집을 이어 가고, 일시정지 중에 들어온 소리로는 조각을 만들지 않는다", async () => {
    collectRequests();
    const reserveBodies = recordReserveBodies();
    const { respond } = holdResponses("resume");
    const result = renderRecordingControl();
    const collector = await startRecording(result);
    collector.feed(SHORT_SILENCE_FRAMES);
    await pauseRecording(result);

    collector.feed(80_000);
    act(() => void result.current.resumeRecording());
    await waitFor(() => expect(events).toContain("resume"));

    expect(getStatus()).toBe("paused");
    expect(collector.isCollecting).toBe(false);

    respond();
    await waitFor(() => expect(getStatus()).toBe("recording"));
    collector.feed(80_000);
    await waitFor(() => expect(reserveBodies).toHaveLength(2));

    expect(collector.isCollecting).toBe(true);
    expect(
      reserveBodies.map(({ sequence, sampleOffset }) => [
        sequence,
        sampleOffset,
      ]),
    ).toEqual([
      [1, 0],
      [2, SHORT_SILENCE_FRAMES],
    ]);
  });

  describe("이어서 녹음이 실패하면", () => {
    const respondResume = (resolver: () => Response) =>
      mockServer.use(http.post(`${RECORDING_PATH}/resume`, resolver));

    const resumeAfterPause = async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      await pauseRecording(result);

      let isResumed: boolean | undefined;
      await act(async () => {
        isResumed = await result.current.resumeRecording();
      });

      return { collector, isResumed };
    };

    it.each([
      ["서버 오류(500)", () => new HttpResponse(null, { status: 500 })],
      ["네트워크 문제", () => HttpResponse.error()],
    ])("%s면 일시정지로 남고 오류 토스트로 알린다", async (_, resolver) => {
      respondResume(resolver);

      const { collector, isResumed } = await resumeAfterPause();

      // 마이크는 받았으므로 마이크 알림을 띄우지 않게 true로 답해요
      expect(isResumed).toBe(true);
      expect(getStatus()).toBe("paused");
      expect(collector.isCollecting).toBe(false);
      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_RESUME_FAILED.message),
      ).toBeInTheDocument();
    });

    it("서버가 이미 끝낸 녹음이라 거절되면 생존 신호로 마저 처리하고 오류 토스트는 띄우지 않는다", async () => {
      respondResume(() =>
        HttpResponse.json({ code: "RECORDING_ALREADY_ENDED" }, { status: 409 }),
      );
      collectRequests();

      await resumeAfterPause();

      await waitFor(() => expect(events).toContain("heartbeat"));
      expect(
        screen.queryByText(TOAST_MESSAGES.RECORDING_RESUME_FAILED.message),
      ).not.toBeInTheDocument();
    });
  });

  it("일시정지와 이어서 녹음을 연타해도 앞 요청의 응답을 받은 뒤 다음을 처리한다", async () => {
    collectRequests();
    const { respond } = holdResponses("pause");
    const result = renderRecordingControl();
    await startRecording(result);
    const results: boolean[] = [];

    act(() => {
      void result.current.pauseRecording();
      void result.current.resumeRecording().then((isResumed) => {
        results.push(isResumed);
      });
      void result.current.pauseRecording();
    });
    await waitFor(() => expect(events).toContain("pause"));

    // 첫 일시정지의 응답 전이라 이어서 녹음은 아직 보내지 않아요
    expect(events).toEqual(["recordings", "start", "flush", "pause"]);

    respond();
    await waitFor(() =>
      expect(events.filter((e) => e === "pause")).toHaveLength(2),
    );
    respond();
    await waitFor(() => expect(results).toEqual([true]));

    expect(events).toEqual([
      "recordings",
      "start",
      "flush",
      "pause",
      "resume",
      "start",
      "flush",
      "pause",
    ]);
    expect(getStatus()).toBe("paused");
  });

  it("수집기가 멈추면 저절로 일시정지하고, 이어서 녹음하면 마이크와 수집기를 다시 받은 뒤 서버 재개 뒤에 이어 간다", async () => {
    collectRequests();
    const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
    const result = renderRecordingControl();
    const collector = await startRecording(result);

    act(() => collector.lose());

    expect(getStatus()).toBe("paused");
    expect(useRecordingStore.getState().isMicrophoneLost).toBe(true);
    expect(events).toEqual(["recordings", "start", "flush"]);

    // 독은 끊김을 보면 서버에 일시정지를 알린 뒤 다시 시도로 이어서 녹음해요
    act(() => void result.current.syncPause());
    act(() => void result.current.resumeRecording());
    await waitFor(() => expect(getStatus()).toBe("recording"));

    expect(getUserMedia).toHaveBeenCalledTimes(2);
    expect(FakePcmCollector.instances).toHaveLength(2);
    expect(collector.isClosed).toBe(true);
    expect(FakePcmCollector.instances[1].isCollecting).toBe(true);
    expect(events).toEqual([
      "recordings",
      "start",
      "flush",
      "pause",
      "resume",
      "start",
    ]);
  });

  describe("끝내기", () => {
    const endResponse = () =>
      new PostRecordingEndResponseDto(recordingEndResponse);

    it("마지막 조각을 봉인하고 예약 응답을 기다리는 조각까지 audioSeal에 담아 끝낸 뒤, 남은 조각을 올리는 동안 새 녹음을 열지 않는다", async () => {
      collectRequests();
      const { respond } = holdResponses("reserve");
      endRecordingApi.mockResolvedValue(endResponse());
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      collector.feed(CHUNK_FRAMES);
      await waitFor(() => expect(count("audio-chunks")).toBe(1));
      collector.feed(SHORT_SILENCE_FRAMES);
      const proof = getRecordingControlProof();

      expect(await endRecording(result)).toBe("uploaded");

      expect(endBodies()).toEqual([
        {
          ...proof,
          audioSeal: {
            lastSequence: 2,
            totalFrameCount: CHUNK_FRAMES + SHORT_SILENCE_FRAMES,
            pendingDescriptors: [
              {
                sequence: 1,
                sampleOffset: 0,
                frameCount: CHUNK_FRAMES,
                byteLength: CHUNK_FRAMES * 2,
                checksumSha256: SILENT_CHUNK_CHECKSUM,
              },
              {
                sequence: 2,
                sampleOffset: CHUNK_FRAMES,
                frameCount: SHORT_SILENCE_FRAMES,
                byteLength: SHORT_SILENCE_FRAMES * 2,
                checksumSha256: SHORT_SILENCE_CHECKSUM,
              },
            ],
          },
        },
      ]);
      expect(useRecordingStore.getState()).toMatchObject({
        status: "idle",
        session: null,
        isUploadingChunks: true,
      });
      expect(await result.current.startRecording(WORKSPACE_ID)).toBe(
        "uploading",
      );

      // 종료 뒤에도 봉인에 담은 조각은 평소대로 예약부터 올려요
      respond();
      await waitFor(() => expect(count("audio-chunks")).toBe(2));
      respond();
      await waitFor(() => expect(isUploadingChunks()).toBe(false));
      expect(count("complete")).toBe(2);
      expect(count("recordings")).toBe(1);
    });

    it("음성이 없으면 lastSequence 0과 빈 목록의 audioSeal로 끝내고 바로 올리기를 마친다", async () => {
      endRecordingApi.mockResolvedValue(endResponse());
      const result = renderRecordingControl();
      await startRecording(result);
      const proof = getRecordingControlProof();

      expect(await endRecording(result)).toBe("uploaded");

      expect(endBodies()).toEqual([{ ...proof, audioSeal: EMPTY_SEAL }]);
      await waitFor(() => expect(isUploadingChunks()).toBe(false));
    });

    it("일시정지 중에 끝내면 새 조각 없이 봉인만 보낸다", async () => {
      collectRequests();
      endRecordingApi.mockResolvedValue(endResponse());
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      collector.feed(SHORT_SILENCE_FRAMES);
      await pauseRecording(result);
      await waitFor(() => expect(count("complete")).toBe(1));
      const proof = getRecordingControlProof();

      expect(await endRecording(result)).toBe("uploaded");

      expect(endBodies()).toEqual([
        {
          ...proof,
          audioSeal: {
            lastSequence: 1,
            totalFrameCount: SHORT_SILENCE_FRAMES,
            pendingDescriptors: [],
          },
        },
      ]);
      expect(count("audio-chunks")).toBe(1);
    });

    it("종료 응답을 못 받으면 같은 봉인으로 1, 2, 4, 4초 뒤 다시 보내고, 서버가 먼저 끝냈다고 답하면 끝낸 것으로 본다", async () => {
      const result = renderRecordingControl();
      await startRecording(result);
      vi.useFakeTimers();
      vi.setSystemTime(0);
      const calledAt: number[] = [];
      endRecordingApi.mockImplementation(async () => {
        calledAt.push(Date.now());
        throw calledAt.length < 5
          ? createHttpError({ type: HTTP_ERROR_TYPE.network })
          : endedRecordingError("RECORDING_ALREADY_ENDED");
      });

      let ending!: ReturnType<typeof result.current.endRecordingWithoutConfirm>;
      act(() => {
        ending = result.current.endRecordingWithoutConfirm();
      });
      await vi.advanceTimersByTimeAsync(10_999);

      // 끝내는 중 표시를 유지해 확인 창과 생존 신호가 겹치지 않아요
      expect(useRecordingStore.getState().isEnding).toBe(true);
      expect(calledAt).toEqual([0, 1_000, 3_000, 7_000]);

      await vi.advanceTimersByTimeAsync(1);
      expect(await ending).toBe("notUploaded");
      expect(calledAt).toEqual([0, 1_000, 3_000, 7_000, 11_000]);
      expect(endBodies().map(({ audioSeal }) => audioSeal)).toEqual(
        Array<typeof EMPTY_SEAL>(5).fill(EMPTY_SEAL),
      );
      expect(useRecordingStore.getState()).toMatchObject({
        status: "idle",
        isEnding: false,
      });
    });

    it("서버가 먼저 끝낸 녹음은 예약한 조각만 마저 올리고, 예약하지 못한 조각은 서버 거절로 멈춘다", async () => {
      collectRequests();
      const { respond } = holdResponses("complete");
      endRecordingApi.mockRejectedValue(
        endedRecordingError("RECORDING_ALREADY_ENDED"),
      );
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      collector.feed(CHUNK_FRAMES);
      await waitFor(() => expect(count("put")).toBe(1));
      // 종료 뒤의 새 예약은 서버가 거절해요
      mockServer.use(
        http.post(RESERVE_PATH, () =>
          HttpResponse.json(
            { code: "RECORDING_ALREADY_ENDED" },
            { status: 409 },
          ),
        ),
      );
      collector.feed(SHORT_SILENCE_FRAMES);

      expect(await endRecording(result)).toBe("notUploaded");

      // 1번은 예약 응답을 받았으니 봉인에는 2번만 담겨요
      expect(
        endBodies()[0].audioSeal?.pendingDescriptors.map((d) => d.sequence),
      ).toEqual([2]);
      expect(useRecordingStore.getState()).toMatchObject({
        status: "idle",
        isUploadingChunks: true,
      });
      respond();
      await waitFor(() => expect(isUploadingChunks()).toBe(false));
      expect(count("audio-chunks")).toBe(2);
      expect(count("put")).toBe(1);
      expect(count("complete")).toBe(1);
    });

    it("생존 신호가 연결 끊김으로 서버가 끝낸 녹음이라 답하면 수신 창이 닫혀 남은 조각을 올리지 않고 지우고, 일부 소리를 올리지 못했다고도 알린다", async () => {
      collectRequests();
      holdResponses("complete");
      mockServer.use(
        http.post(`${RECORDING_PATH}/heartbeat`, () =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            status: "ENDED",
            endedAt: new Date().toISOString(),
            endReason: "CONNECTION_EXPIRED",
          }),
        ),
      );
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      collector.feed(CHUNK_FRAMES);
      await waitFor(() => expect(count("put")).toBe(1));
      collector.feed(SHORT_SILENCE_FRAMES);

      await act(() => result.current.sendHeartbeat());

      expect(useRecordingStore.getState()).toMatchObject({
        status: "idle",
        session: null,
        isUploadingChunks: false,
      });
      expect(collector.isClosed).toBe(true);
      expect(events).not.toContain("flush");
      expect(endRecordingApi).not.toHaveBeenCalled();
      expect(
        await screen.findByText(
          TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message,
        ),
      ).toBeInTheDocument();
      expect(
        await screen.findByText(
          TOAST_MESSAGES.RECORDING_CHUNKS_NOT_UPLOADED.message,
        ),
      ).toBeInTheDocument();
      expect(count("audio-chunks")).toBe(1);
    });

    it.each([
      [
        "409 RECORDING_ALREADY_DISCARDED",
        endedRecordingError("RECORDING_ALREADY_DISCARDED"),
      ],
      [
        "403 WORKSPACE_ACCESS_DENIED",
        createHttpError({
          type: HTTP_ERROR_TYPE.forbidden,
          code: "WORKSPACE_ACCESS_DENIED",
        }),
      ],
      ["404", createHttpError({ type: HTTP_ERROR_TYPE.notFound })],
      [
        "그 밖의 4xx",
        createHttpError({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_REQUEST",
        }),
      ],
    ])(
      "종료가 %s로 거절되면 조각을 버리고 더는 올리지 않는다",
      async (_, error) => {
        collectRequests();
        const { respond } = holdResponses("reserve");
        endRecordingApi.mockRejectedValue(error);
        const result = renderRecordingControl();
        const collector = await startRecording(result);
        collector.feed(CHUNK_FRAMES);
        await waitFor(() => expect(count("audio-chunks")).toBe(1));

        expect(await endRecording(result)).toBe("notUploaded");

        expect(useRecordingStore.getState()).toMatchObject({
          status: "idle",
          isUploadingChunks: false,
        });
        respond();
        await flushRequests();
        expect(count("put")).toBe(0);
        expect(endRecordingApi).toHaveBeenCalledTimes(1);
      },
    );

    it("봉인이 거절되면 봉인 없이 한 번 더 끝낸다", async () => {
      endRecordingApi
        .mockRejectedValueOnce(endedRecordingError("AUDIO_SEAL_CONFLICT"))
        .mockResolvedValueOnce(endResponse());
      const result = renderRecordingControl();
      await startRecording(result);
      const proof = getRecordingControlProof();

      expect(await endRecording(result)).toBe("uploaded");

      expect(endBodies()).toEqual([
        { ...proof, audioSeal: EMPTY_SEAL },
        { ...proof, audioSeal: undefined },
      ]);
    });

    // 다시 보낼 때마다 보내면 끝내는 동안의 장애 한 번이 이벤트 여러 건이 돼요
    describe("실패 보고", () => {
      const { flushEvents } = setupSentryEvents();

      it("종료 요청을 다시 보내는 동안에는 첫 실패와 포기한 실패만 보고한다", async () => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        const result = renderRecordingControl();
        await startRecording(result);
        vi.useFakeTimers();
        let attempts = 0;
        endRecordingApi.mockImplementation(async () => {
          attempts += 1;
          throw attempts < 4
            ? createHttpError({ type: HTTP_ERROR_TYPE.network })
            : createHttpError({
                type: HTTP_ERROR_TYPE.forbidden,
                code: "RECORDING_CONTROL_DENIED",
              });
        });

        let ending!: ReturnType<
          typeof result.current.endRecordingWithoutConfirm
        >;
        act(() => {
          ending = result.current.endRecordingWithoutConfirm();
        });
        await vi.advanceTimersByTimeAsync(7_000);
        expect(await ending).toBe("notUploaded");
        // Sentry는 타이머로 보내므로 진짜 타이머로 돌린 뒤 모은 이벤트를 봐요
        vi.useRealTimers();

        const events = await flushEvents();
        expect(attempts).toBe(4);
        expect(events.map(({ extra }) => extra)).toEqual([
          { consecutiveFailures: 1 },
          { consecutiveFailures: 4, isGivingUp: true },
        ]);
      });
    });
  });

  // 오류가 아닌 결과(연결 끊김으로 서버가 끝냄 등)는 Issue로 셀 수 없어 지표로 세요
  describe("Sentry 지표", () => {
    const { flushMetrics } = setupSentryEvents();

    const metricsNamed = async (name: string) =>
      (await flushMetrics())
        .filter((metric) => metric.name === name)
        .map(({ value, attributes }) => ({ value, attributes }));

    const respondHeartbeat = (response: () => Response) =>
      mockServer.use(http.post(`${RECORDING_PATH}/heartbeat`, response));

    it.each([
      { name: "시작하면", responses: [], result: "started" },
      {
        name: "남은 녹음이 있어 거절되면",
        responses: [conflict("ACTIVE_RECORDING_ALREADY_EXISTS")],
        result: "refused",
      },
    ])(
      "녹음을 $name 시작 결과($result)를 recording.start로 센다",
      async ({ responses, result: expected }) => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        respondStart(...responses);
        const result = renderRecordingControl();

        expect(await startRecordingOutcome(result)).toBe(expected);

        expect(await metricsNamed("recording.start")).toEqual([
          { value: 1, attributes: { result: expected } },
        ]);
      },
    );

    it.each([
      {
        name: "끝내면",
        end: () =>
          endRecordingApi.mockResolvedValue(
            new PostRecordingEndResponseDto(recordingEndResponse),
          ),
        isUploaded: true,
      },
      {
        name: "끝냈지만 서버가 이미 버린 녹음이면",
        end: () =>
          endRecordingApi.mockRejectedValue(
            endedRecordingError("RECORDING_ALREADY_DISCARDED"),
          ),
        isUploaded: false,
      },
    ])(
      "사용자가 $name 오디오를 넘겼는지와 함께 userEnded로 센다",
      async ({ end, isUploaded }) => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        end();
        const result = renderRecordingControl();
        await startRecording(result);

        await endRecording(result);

        expect(await metricsNamed("recording.end")).toEqual([
          {
            value: 1,
            attributes: {
              reason: "userEnded",
              uploaded: isUploaded,
              audio_mode: "PCM16_CHUNKS_V1",
            },
          },
        ]);
      },
    );

    const endedHeartbeat = (endReason: string | null) => () =>
      HttpResponse.json({
        ...recordingHeartbeatResponse,
        status: "ENDED",
        endedAt: new Date().toISOString(),
        endReason,
      });

    it.each([
      {
        name: "연결 끊김으로 끝낸",
        response: endedHeartbeat("CONNECTION_EXPIRED"),
        reason: "connectionExpired",
        isUploaded: true,
      },
      {
        name: "최대 길이에 닿아 끝낸",
        response: endedHeartbeat("MAX_DURATION"),
        reason: "maxDuration",
        isUploaded: true,
      },
      {
        name: "다른 탭에서 끝낸",
        response: endedHeartbeat("USER_ENDED"),
        reason: "endedByServer",
        isUploaded: true,
      },
      {
        name: "버린",
        response: () =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            status: "DISCARDED",
          }),
        reason: "discarded",
        isUploaded: false,
      },
      {
        name: "더는 볼 수 없는(404)",
        response: () =>
          HttpResponse.json({ code: "RECORDING_NOT_FOUND" }, { status: 404 }),
        reason: "unreachable",
        isUploaded: false,
      },
      {
        name: "이 탭의 제어를 거절한(403 RECORDING_CONTROL_DENIED)",
        response: () =>
          HttpResponse.json(
            { code: "RECORDING_CONTROL_DENIED" },
            { status: 403 },
          ),
        reason: "controlLost",
        isUploaded: false,
      },
    ])(
      "생존 신호가 서버가 $name 녹음이라 알리면 $reason으로 한 번만 센다",
      async ({ response, reason, isUploaded }) => {
        vi.spyOn(console, "error").mockImplementation(() => {});
        respondHeartbeat(response);
        const result = renderRecordingControl();
        await startRecording(result);

        await act(() => result.current.sendHeartbeat());
        // 녹음을 닫았으니 다음 주기의 생존 신호는 보내지 않아 다시 세지 않아요
        await act(() => result.current.sendHeartbeat());

        expect(await metricsNamed("recording.end")).toEqual([
          {
            value: 1,
            attributes: {
              reason,
              uploaded: isUploaded,
              audio_mode: "PCM16_CHUNKS_V1",
            },
          },
        ]);
      },
    );

    it("이어서 녹음이 서버가 버린 녹음이라 거절되면 discarded로 센다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      mockServer.use(
        http.post(`${RECORDING_PATH}/resume`, () =>
          HttpResponse.json(
            { code: "RECORDING_ALREADY_DISCARDED" },
            { status: 409 },
          ),
        ),
      );
      const result = renderRecordingControl();
      await startRecording(result);
      await pauseRecording(result);

      await act(async () => {
        await result.current.resumeRecording();
      });

      expect(await metricsNamed("recording.end")).toEqual([
        {
          value: 1,
          attributes: {
            reason: "discarded",
            uploaded: false,
            audio_mode: "PCM16_CHUNKS_V1",
          },
        },
      ]);
    });

    it("생존 신호가 녹음 중이라 답하면 세지 않는다", async () => {
      const result = renderRecordingControl();
      await startRecording(result);

      await act(() => result.current.sendHeartbeat());

      expect(await metricsNamed("recording.end")).toEqual([]);
    });
  });

  describe("연결이 끊긴 동안 (STT-R53)", () => {
    const isConnectionFlagged = () =>
      useRecordingStore.getState().isHeartbeatFailing;

    afterEach(() => deleteRecordingChunks(RECORDING_ID));

    it.each([
      ["네트워크 실패", () => HttpResponse.error()],
      ["5xx", () => new HttpResponse(null, { status: 503 })],
    ])("신호가 %s여도 녹음을 이어 가고, 닿으면 끊김 표시를 지운다", async (_, fail) => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      mockServer.use(http.post(`${RECORDING_PATH}/heartbeat`, fail));
      const result = renderRecordingControl();
      await startRecording(result);

      await act(() => result.current.sendHeartbeat());
      await act(() => result.current.sendHeartbeat());

      expect(getStatus()).toBe("recording");
      expect(isConnectionFlagged()).toBe(true);

      mockServer.resetHandlers();
      await act(() => result.current.sendHeartbeat());

      expect(getStatus()).toBe("recording");
      expect(isConnectionFlagged()).toBe(false);
    });

    it("저장이 확인되기 전까지 조각을 브라우저에 보관하고, 확인되면 지운다", async () => {
      const { respond } = holdResponses("complete");
      const result = renderRecordingControl();
      const collector = await startRecording(result);

      collector.feed(CHUNK_FRAMES);
      await waitFor(async () =>
        expect(await readRecordingChunks(RECORDING_ID)).toHaveLength(1),
      );

      respond();
      await waitFor(async () =>
        expect(await readRecordingChunks(RECORDING_ID)).toEqual([]),
      );
    });

    it("다른 탭이 끝내 조각 예약이 거절되면 다음 주기를 기다리지 않고 멈춘다 (STT-R28)", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      mockServer.use(
        http.post(RESERVE_PATH, () =>
          HttpResponse.json(
            { code: "RECORDING_ALREADY_ENDED" },
            { status: 409 },
          ),
        ),
        http.post(`${RECORDING_PATH}/heartbeat`, () =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            status: "ENDED",
            endReason: "USER_ENDED",
            endedAt: new Date().toISOString(),
          }),
        ),
      );
      const result = renderRecordingControl();
      const collector = await startRecording(result);

      collector.feed(CHUNK_FRAMES);

      await waitFor(() => expect(getStatus()).toBe("idle"));
      expect(endRecordingApi).not.toHaveBeenCalled();
    });

    it("끊긴 채 끝내면 쌓인 조각을 먼저 올린 뒤 봉인에 미루지 않고 끝낸다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      endRecordingApi.mockResolvedValue(
        new PostRecordingEndResponseDto(recordingEndResponse),
      );
      mockServer.use(
        http.post(RESERVE_PATH, () => new HttpResponse(null, { status: 503 })),
      );
      const result = renderRecordingControl();
      const collector = await startRecording(result);
      collector.feed(CHUNK_FRAMES);
      await waitFor(() =>
        expect(useRecordingStore.getState().isChunkTransferStalled).toBe(true),
      );

      let ended!: Promise<unknown>;
      act(() => {
        ended = result.current.endRecordingWithoutConfirm();
      });
      await flushRequests();
      expect(endRecordingApi).not.toHaveBeenCalled();

      mockServer.resetHandlers();
      await act(async () => {
        await ended;
      });

      expect(endBodies()).toEqual([
        expect.objectContaining({
          audioSeal: expect.objectContaining({
            lastSequence: 1,
            totalFrameCount: CHUNK_FRAMES,
            pendingDescriptors: [],
          }),
        }),
      ]);
    }, 10_000);
  });

  describe("새로고침 뒤 이어 가기 (STT-R26)", () => {
    const RECONNECT_PATH = `${RECORDING_PATH}/reconnect`;
    const AUDIO_SAVE_PATH = `${RECORDING_PATH}/audio-save`;
    const SESSION = { workspaceId: WORKSPACE_ID, recordingId: RECORDING_ID };

    /** 새로고침 전에 녹음 중이었던 것처럼 증명을 남겨 둬요 */
    const leaveResumeProof = () => {
      getRecordingStartProof();
      saveRecordingResumeProof({ ...SESSION, audioMode: "PCM16_CHUNKS_V1" });
    };

    const archiveChunk = (sequence: number) =>
      saveRecordingChunk({
        ...SESSION,
        sequence,
        pcm: new ArrayBuffer(CHUNK_FRAMES * 2),
      });

    /** 서버가 앞의 조각 하나만 저장했다고 답하게 해요 */
    const respondVerifiedThroughFirst = () =>
      mockServer.use(
        http.get(AUDIO_SAVE_PATH, () =>
          HttpResponse.json({
            ...recordingAudioSaveResponse,
            audioSaveStatus: "CAPTURING",
            verifiedThroughSequence: 1,
            verifiedThroughFrame: CHUNK_FRAMES,
          }),
        ),
      );

    const resume = async (
      result: ReturnType<typeof renderRecordingControl>,
    ) => {
      let isResumed!: boolean;
      await act(async () => {
        isResumed = await result.current.resumeReloadedRecording();
      });

      return isResumed;
    };

    afterEach(() => deleteRecordingChunks(RECORDING_ID));

    it("이전 증명으로 이어 받고, 이후 조각과 끝내기에는 새 증명을 쓴다", async () => {
      leaveResumeProof();
      const previous = getRecordingControlProof();
      respondVerifiedThroughFirst();
      const reconnectBodies: unknown[] = [];
      mockServer.use(
        http.post(RECONNECT_PATH, async ({ request }) => {
          reconnectBodies.push(await request.clone().json());
        }),
      );
      const reserveBodies = recordReserveBodies();
      const result = renderRecordingControl();

      expect(await resume(result)).toBe(true);

      expect(reconnectBodies).toEqual([
        expect.objectContaining({
          previousTabId: previous?.tabId,
          previousControlToken: previous?.controlToken,
        }),
      ]);
      const current = getRecordingControlProof();
      expect(current?.tabId).not.toBe(previous?.tabId);
      expect(getStatus()).toBe("recording");

      FakePcmCollector.instances[0].feed(CHUNK_FRAMES);
      await waitFor(() => expect(reserveBodies).toHaveLength(1));
      expect(reserveBodies[0]).toMatchObject({
        tabId: current?.tabId,
        controlToken: current?.controlToken,
      });
    });

    it("서버가 확인한 다음부터 보관한 조각을 먼저 올리고, 새 소리를 뒤에 이어 붙인다", async () => {
      leaveResumeProof();
      respondVerifiedThroughFirst();
      await archiveChunk(1);
      await archiveChunk(2);
      const reserveBodies = recordReserveBodies();
      const result = renderRecordingControl();

      await resume(result);
      FakePcmCollector.instances[0].feed(CHUNK_FRAMES);

      await waitFor(() => expect(reserveBodies).toHaveLength(2));
      expect(
        reserveBodies.map(({ sequence, sampleOffset }) => [
          sequence,
          sampleOffset,
        ]),
      ).toEqual([
        [2, CHUNK_FRAMES],
        [3, CHUNK_FRAMES * 2],
      ]);
    });

    it("서버 시간으로 경과 시간을 잇는다", async () => {
      leaveResumeProof();
      respondVerifiedThroughFirst();
      mockServer.use(
        http.post(RECONNECT_PATH, () =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            elapsedMillis: 90_000,
          }),
        ),
      );
      const result = renderRecordingControl();

      await resume(result);

      expect(useRecordingStore.getState().accumulatedMs).toBe(90_000);
    });

    it("연결 끊김으로 끝난 녹음이면 토스트로 알리고 증명과 조각을 지운다", async () => {
      leaveResumeProof();
      await archiveChunk(1);
      mockServer.use(
        http.post(RECONNECT_PATH, () =>
          HttpResponse.json({
            ...recordingHeartbeatResponse,
            status: "ENDED",
            endReason: "CONNECTION_EXPIRED",
          }),
        ),
      );
      const result = renderRecordingControl();

      await resume(result);

      expect(getStatus()).toBe("idle");
      expect(getRecordingResumeProof()).toBeNull();
      expect(
        await screen.findByText(
          TOAST_MESSAGES.RECORDING_CONNECTION_LOST.message,
        ),
      ).toBeInTheDocument();
      await waitFor(async () =>
        expect(await readRecordingChunks(RECORDING_ID)).toEqual([]),
      );
    });

    it("다른 탭이 먼저 이어 받았으면(403) 알리고, 그 탭이 올릴 조각은 남겨 둔다", async () => {
      leaveResumeProof();
      await archiveChunk(1);
      mockServer.use(
        http.post(RECONNECT_PATH, () =>
          HttpResponse.json(
            { code: "RECORDING_CONTROL_DENIED" },
            { status: 403 },
          ),
        ),
      );
      const result = renderRecordingControl();

      await resume(result);

      expect(getStatus()).toBe("idle");
      expect(getRecordingResumeProof()).toBeNull();
      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_TAKEN_OVER.message),
      ).toBeInTheDocument();
      expect(await readRecordingChunks(RECORDING_ID)).toHaveLength(1);
    });

    it("응답을 받지 못하면 증명을 두고 다시 눌렀을 때 같은 새 증명으로 보낸다", async () => {
      vi.spyOn(console, "error").mockImplementation(() => {});
      leaveResumeProof();
      respondVerifiedThroughFirst();
      const tokens: string[] = [];
      mockServer.use(
        http.post<PathParams, { controlToken: string }>(
          RECONNECT_PATH,
          async ({ request }) => {
            tokens.push((await request.clone().json()).controlToken);
            if (tokens.length === 1) return HttpResponse.error();
          },
        ),
      );
      const result = renderRecordingControl();

      await resume(result);
      expect(getStatus()).toBe("idle");
      expect(getRecordingResumeProof()).not.toBeNull();
      expect(
        await screen.findByText(TOAST_MESSAGES.RECORDING_RESUME_FAILED.message),
      ).toBeInTheDocument();

      await resume(result);
      expect(getStatus()).toBe("recording");
      expect(tokens[0]).toBe(tokens[1]);
    });

    it("마이크를 받지 못하면 일시정지로 남고 서버에도 알린다", async () => {
      leaveResumeProof();
      respondVerifiedThroughFirst();
      collectRequests();
      vi.spyOn(navigator.mediaDevices, "getUserMedia").mockRejectedValueOnce(
        new DOMException("거절", "NotAllowedError"),
      );
      const result = renderRecordingControl();

      expect(await resume(result)).toBe(false);

      expect(getStatus()).toBe("paused");
      await waitFor(() => expect(count("pause")).toBe(1));
    });
  });
});
