import { AxiosError, type AxiosResponse } from "axios";
import { http, HttpResponse, type PathParams } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  reserveRecordingAudioChunkApi,
  uploadRecordingAudioChunkApi,
} from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks";
import { completeRecordingAudioChunkApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks/[sequence]/complete";
import {
  createHttpError,
  HTTP_ERROR_TYPE,
  type HttpErrorType,
} from "@api/httpClient/error";
import {
  MOCK_AUDIO_STORAGE_ORIGIN,
  recordingAudioChunkCompleteResponse,
  recordingAudioChunkReserveResponse,
} from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { setupSentryEvents } from "@provider/sentry/mock";
import type { RecordingAudioChunkReserveRequest } from "@api/mock/types/recording";

import PcmChunkUploadQueue, {
  type PcmChunkUploadQueueParams,
} from "./pcmChunkUploadQueue";

const SESSION = { workspaceId: 1, recordingId: 10 };
const CONTROL_PROOF = {
  tabId: "6f1c2a4e-1b2d-4c3e-9f80-1a2b3c4d5e6f",
  controlToken: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
};
const RESERVE_PATH =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-chunks";
const COMPLETE_PATH = `${RESERVE_PATH}/:sequence/complete`;
const STORAGE_PATH = `${MOCK_AUDIO_STORAGE_ORIGIN}/*`;

/** 5초 조각의 프레임 수. 16kHz × 5초 */
const CHUNK_FRAMES = 80_000;
/** 5초 무음 PCM의 SHA-256(BE 문서 예시). mock 예약 응답의 PUT 헤더와 같은 값이에요 */
const SILENT_CHUNK_CHECKSUM =
  recordingAudioChunkReserveResponse.requiredHeaders["x-amz-checksum-sha256"];

const silentPcm = (frames: number) => new ArrayBuffer(frames * 2);

/** 열어 주기 전까지 응답을 붙잡아 두는 문 */
const createGate = () => {
  let open!: () => void;
  const opened = new Promise<void>((resolve) => {
    open = resolve;
  });

  return { opened, open };
};

/** 실제 요청 함수를 주입한 큐. 도메인 훅이 뮤테이션의 mutateAsync를 넘기는 모양과 같아요 */
const createQueue = () =>
  new PcmChunkUploadQueue({
    reserve: (descriptor) =>
      reserveRecordingAudioChunkApi({
        ...SESSION,
        body: { ...CONTROL_PROOF, ...descriptor },
      }),
    upload: uploadRecordingAudioChunkApi,
    complete: (sequence) =>
      completeRecordingAudioChunkApi({
        ...SESSION,
        sequence,
        body: CONTROL_PROOF,
      }),
  });

/** 예약 요청 본문만 남기고 응답은 기본 mock 핸들러에 맡겨요 */
const recordReserveBodies = () => {
  const bodies: RecordingAudioChunkReserveRequest[] = [];
  mockServer.use(
    http.post<PathParams, RecordingAudioChunkReserveRequest>(
      RESERVE_PATH,
      async ({ request }) => {
        bodies.push(await request.clone().json());
      },
    ),
  );

  return bodies;
};

describe("PcmChunkUploadQueue", () => {
  it("넣은 순서대로 번호·offset·해시를 고정하고, 앞 예약의 응답을 받은 뒤에 다음 예약을 보낸다", async () => {
    const firstReserve = createGate();
    const bodies: RecordingAudioChunkReserveRequest[] = [];
    mockServer.use(
      http.post<PathParams, RecordingAudioChunkReserveRequest>(
        RESERVE_PATH,
        async ({ request }) => {
          const body = await request.clone().json();
          bodies.push(body);
          if (body.sequence === 1) await firstReserve.opened;
        },
      ),
    );
    const queue = createQueue();

    const first = queue.enqueue(silentPcm(CHUNK_FRAMES));
    const second = queue.enqueue(silentPcm(1_600));

    await vi.waitFor(() => expect(bodies).toHaveLength(1));
    // 두 번째 조각은 더 작아 해시가 먼저 끝나지만, 첫 예약의 응답 전에는 보내지 않아요
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(bodies).toHaveLength(1);

    firstReserve.open();
    await Promise.all([first, second]);

    expect(bodies).toEqual([
      {
        ...CONTROL_PROOF,
        sequence: 1,
        sampleOffset: 0,
        frameCount: CHUNK_FRAMES,
        byteLength: CHUNK_FRAMES * 2,
        checksumSha256: SILENT_CHUNK_CHECKSUM,
      },
      {
        ...CONTROL_PROOF,
        sequence: 2,
        sampleOffset: CHUNK_FRAMES,
        frameCount: 1_600,
        byteLength: 3_200,
        checksumSha256: expect.any(String),
      },
    ]);
  });

  it("PUT과 저장 확인은 조각 2개까지만 동시에 진행한다", async () => {
    const puts = createGate();
    let inflightPuts = 0;
    let maxInflightPuts = 0;
    mockServer.use(
      http.put(STORAGE_PATH, async () => {
        inflightPuts += 1;
        maxInflightPuts = Math.max(maxInflightPuts, inflightPuts);
        await puts.opened;
        inflightPuts -= 1;

        return new HttpResponse(null, { status: 200 });
      }),
    );
    const reserveBodies = recordReserveBodies();
    const queue = createQueue();

    for (let i = 0; i < 3; i += 1) void queue.enqueue(silentPcm(CHUNK_FRAMES));

    // 세 조각 모두 예약이 끝나도 PUT은 두 개만 열려 있어요
    await vi.waitFor(() => expect(reserveBodies).toHaveLength(3));
    await vi.waitFor(() => expect(inflightPuts).toBe(2));
    expect(queue.pendingSequences).toEqual([1, 2, 3]);

    puts.open();
    await queue.drain();

    expect(maxInflightPuts).toBe(2);
    expect(queue.pendingSequences).toEqual([]);
  });

  it("뒤 조각의 저장 확인이 먼저 와도 그 조각만 놓고, watermark는 응답 중 큰 값을 따른다", async () => {
    const firstComplete = createGate();
    mockServer.use(
      // 서버는 1번을 먼저 확인했지만(80,000) 그 응답이 2번 응답(160,000)보다 늦게 도착하는 상황이에요
      http.post(COMPLETE_PATH, async ({ params }) => {
        const sequence = Number(params.sequence);
        if (sequence === 1) await firstComplete.opened;

        return HttpResponse.json({
          ...recordingAudioChunkCompleteResponse,
          sequence,
          verifiedThroughFrame:
            sequence === 1 ? CHUNK_FRAMES : CHUNK_FRAMES * 2,
        });
      }),
    );
    const queue = createQueue();

    void queue.enqueue(silentPcm(CHUNK_FRAMES));
    const second = queue.enqueue(silentPcm(CHUNK_FRAMES));
    let isDrained = false;
    const drained = queue.drain().then(() => {
      isDrained = true;
    });

    await second;
    expect(queue.pendingSequences).toEqual([1]);
    expect(queue.verifiedThroughFrame).toBe(CHUNK_FRAMES * 2);
    expect(isDrained).toBe(false);

    firstComplete.open();
    await drained;

    expect(queue.pendingSequences).toEqual([]);
    expect(queue.verifiedThroughFrame).toBe(CHUNK_FRAMES * 2);
  });

  it("예약 응답이 이미 VERIFIED면 PUT 없이 저장 확인만 보내고 조각을 놓는다", async () => {
    let putCount = 0;
    let completeCount = 0;
    mockServer.use(
      http.post<PathParams, RecordingAudioChunkReserveRequest>(
        RESERVE_PATH,
        async ({ request }) => {
          const { sequence } = await request.json();

          return HttpResponse.json({
            sequence,
            status: "VERIFIED",
            uploadUrl: null,
            expiresAt: null,
            requiredHeaders: {},
          });
        },
      ),
      http.put(STORAGE_PATH, () => {
        putCount += 1;
      }),
      http.post(COMPLETE_PATH, () => {
        completeCount += 1;
      }),
    );
    const queue = createQueue();

    await queue.enqueue(silentPcm(CHUNK_FRAMES));

    expect(putCount).toBe(0);
    expect(completeCount).toBe(1);
    expect(queue.pendingSequences).toEqual([]);
    expect(queue.verifiedThroughFrame).toBe(
      recordingAudioChunkCompleteResponse.verifiedThroughFrame,
    );
  });

  describe("실패 복구", () => {
    const STORAGE_URL = (sequence: number) =>
      `${MOCK_AUDIO_STORAGE_ORIGIN}/audio-chunks/${sequence}`;
    const NOT_STORED = "AUDIO_CHUNK_NOT_STORED";

    /** httpClient가 던지는 모양의 API 실패 */
    const apiError = (type: HttpErrorType, code?: string) =>
      createHttpError({ type, code });
    /** 저장소 PUT이 던지는 axios 실패. 상태가 없으면 응답을 못 받은 거예요 */
    const putError = (status?: number) =>
      new AxiosError(
        "PUT 실패",
        undefined,
        undefined,
        undefined,
        status === undefined ? undefined : ({ status } as AxiosResponse),
      );

    /** 가짜 요청 함수를 주입한 큐. 어떤 요청이 몇 ms에 나갔는지 순서대로 남겨요 */
    const createFakeQueue = () => {
      const calls: Array<[string, number]> = [];
      const reserve = vi.fn<PcmChunkUploadQueueParams["reserve"]>(
        async ({ sequence }) => ({
          status: "RESERVED",
          uploadUrl: STORAGE_URL(sequence),
          requiredHeaders: {},
        }),
      );
      const upload = vi.fn<PcmChunkUploadQueueParams["upload"]>(
        async () => undefined,
      );
      const complete = vi.fn<PcmChunkUploadQueueParams["complete"]>(
        async () => ({
          status: "VERIFIED",
          verifiedThroughSequence: 1,
          verifiedThroughFrame: CHUNK_FRAMES,
        }),
      );
      const queue = new PcmChunkUploadQueue({
        reserve: (descriptor) => {
          calls.push(["reserve", Date.now()]);

          return reserve(descriptor);
        },
        upload: (params) => {
          calls.push(["upload", Date.now()]);

          return upload(params);
        },
        complete: (sequence) => {
          calls.push(["complete", Date.now()]);

          return complete(sequence);
        },
      });

      return { queue, reserve, upload, complete, calls };
    };

    beforeEach(() => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      // 해시 계산이 실제 스레드를 타지 않게 해 가짜 타이머만으로 흐름이 진행되게 해요
      vi.spyOn(crypto.subtle, "digest").mockResolvedValue(new ArrayBuffer(32));
      // 다시 시도하는 실패는 콘솔에 남겨요. 테스트 출력에 섞이지 않게 막아요
      vi.spyOn(console, "error").mockImplementation(() => {});
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("PUT 응답 유실과 412는 저장 확인으로 넘어가고, NOT_STORED면 같은 descriptor로 재예약해 같은 바이트를 다시 PUT한다", async () => {
      const { queue, reserve, upload, complete } = createFakeQueue();
      upload
        .mockRejectedValueOnce(putError())
        .mockRejectedValueOnce(putError(412));
      complete
        .mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.conflict, NOT_STORED))
        .mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.conflict, NOT_STORED));
      const pcm = silentPcm(CHUNK_FRAMES);

      const task = queue.enqueue(pcm);
      await vi.advanceTimersByTimeAsync(1_000);
      await vi.advanceTimersByTimeAsync(2_000);
      await task;

      const [descriptor] = reserve.mock.calls[0];
      expect(reserve.mock.calls).toEqual([
        [descriptor],
        [descriptor],
        [descriptor],
      ]);
      expect(upload.mock.calls.map(([params]) => params.pcm)).toEqual([
        pcm,
        pcm,
        pcm,
      ]);
      expect(complete).toHaveBeenCalledTimes(3);
      expect(queue.pendingSequences).toEqual([]);
      expect(queue.verifiedThroughFrame).toBe(CHUNK_FRAMES);
    });

    it("예약·저장 확인의 5xx와 PUT 403·500은 1, 2, 4, 4초 뒤 재예약으로 다시 시도하고 그동안 바이트를 들고 있다", async () => {
      const { queue, reserve, upload, complete, calls } = createFakeQueue();
      reserve.mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.serverError));
      upload
        .mockRejectedValueOnce(putError(403))
        .mockRejectedValueOnce(putError(500));
      complete.mockRejectedValueOnce(
        apiError(HTTP_ERROR_TYPE.serverError, "AUDIO_STORAGE_UNAVAILABLE"),
      );

      const task = queue.enqueue(silentPcm(CHUNK_FRAMES));
      await vi.advanceTimersByTimeAsync(10_999);
      expect(queue.pendingSequences).toEqual([1]);
      await vi.advanceTimersByTimeAsync(1);
      await task;

      expect(calls).toEqual([
        ["reserve", 0],
        ["reserve", 1_000],
        ["upload", 1_000],
        ["reserve", 3_000],
        ["upload", 3_000],
        ["reserve", 7_000],
        ["upload", 7_000],
        ["complete", 7_000],
        ["complete", 11_000],
      ]);
      expect(queue.pendingSequences).toEqual([]);
    });

    it.each([
      ["충돌", apiError(HTTP_ERROR_TYPE.conflict, "AUDIO_CHUNK_CONFLICT")],
      ["API 403", apiError(HTTP_ERROR_TYPE.forbidden)],
      [
        "접수 마감",
        apiError(HTTP_ERROR_TYPE.conflict, "AUDIO_ACCEPTANCE_CLOSED"),
      ],
    ])(
      "저장 확인이 %s이면 재시도하지 않고 그 조각을 멈춘다",
      async (_, error) => {
        const { queue, complete, calls } = createFakeQueue();
        complete.mockRejectedValue(error);

        await expect(queue.enqueue(silentPcm(CHUNK_FRAMES))).rejects.toBe(
          error,
        );
        await vi.advanceTimersByTimeAsync(10_000);

        expect(calls.map(([name]) => name)).toEqual([
          "reserve",
          "upload",
          "complete",
        ]);
        // 멈춘 조각의 바이트는 시한이 지나거나 큐를 비울 때까지 들고 있어요
        expect(queue.pendingSequences).toEqual([1]);
      },
    );

    it("앞 조각의 예약이 멈추면 뒤 조각의 예약은 보내지 않고 같은 이유로 멈춘다", async () => {
      const { queue, reserve } = createFakeQueue();
      const error = apiError(HTTP_ERROR_TYPE.conflict, "AUDIO_SEQUENCE_GAP");
      reserve.mockRejectedValueOnce(error);

      const first = queue.enqueue(silentPcm(CHUNK_FRAMES));
      const second = queue.enqueue(silentPcm(CHUNK_FRAMES));

      await expect(first).rejects.toBe(error);
      await expect(second).rejects.toBe(error);
      await vi.advanceTimersByTimeAsync(10_000);
      expect(reserve).toHaveBeenCalledTimes(1);
      expect(queue.pendingSequences).toEqual([1, 2]);
    });

    it("재시도를 기다리는 조각은 전송 자리를 놓아 다른 조각의 PUT이 진행된다", async () => {
      const { queue, upload } = createFakeQueue();
      upload.mockRejectedValueOnce(putError(500));

      for (let i = 0; i < 3; i += 1)
        void queue.enqueue(silentPcm(CHUNK_FRAMES));
      await vi.advanceTimersByTimeAsync(0);

      // 1번이 1초를 기다리는 동안 자리를 비워 2·3번 PUT이 모두 나갔어요
      expect(queue.pendingSequences).toEqual([1]);
      await vi.advanceTimersByTimeAsync(1_000);
      await queue.drain();

      expect(upload.mock.calls.map(([params]) => params.uploadUrl)).toEqual(
        [1, 2, 3, 1].map(STORAGE_URL),
      );
      expect(queue.pendingSequences).toEqual([]);
    });

    it("시한이 지나면 남은 조각의 재시도를 멈추고 바이트를 놓으며 drain은 그 이유로 끝난다", async () => {
      const { queue, reserve } = createFakeQueue();
      reserve.mockRejectedValue(apiError(HTTP_ERROR_TYPE.serverError));

      void queue.enqueue(silentPcm(CHUNK_FRAMES));
      queue.setAcceptUntil(new Date(2_500).toISOString());
      const drained = expect(queue.drain()).rejects.toThrow(
        "조각 접수 시한이 지났어요",
      );
      await vi.advanceTimersByTimeAsync(2_500);
      await drained;

      expect(queue.pendingSequences).toEqual([]);
      await vi.advanceTimersByTimeAsync(10_000);
      // 0초와 1초의 예약만 나가고 3초로 잡혀 있던 재시도는 나가지 않아요
      expect(reserve).toHaveBeenCalledTimes(2);
    });

    it("비운 뒤에는 요청을 보내지 않고 새 조각도 바로 멈춘다", async () => {
      const { queue, reserve, calls } = createFakeQueue();
      reserve.mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.network));

      const first = expect(
        queue.enqueue(silentPcm(CHUNK_FRAMES)),
      ).rejects.toThrow("전송 큐를 비웠어요");
      await vi.advanceTimersByTimeAsync(0);
      queue.clear();
      await first;

      await expect(queue.enqueue(silentPcm(CHUNK_FRAMES))).rejects.toThrow(
        "전송 큐를 비웠어요",
      );
      await vi.advanceTimersByTimeAsync(10_000);
      expect(calls).toHaveLength(1);
      expect(queue.pendingSequences).toEqual([]);
    });

    // 버린 조각은 녹음 일부가 서버에 남지 않아 문서에서 빠져요. Sentry는 타이머를 쓰므로 진짜 타이머로 돌린 뒤 모은 이벤트를 봐요
    describe("조각 유실 보고", () => {
      const { flushEvents, flushMetrics } = setupSentryEvents();

      const flushRealEvents = () => {
        vi.useRealTimers();

        return flushEvents();
      };

      it("시한이 지나 남은 조각을 버리면 남은 조각 수를 담아 한 번만 보고한다", async () => {
        const { queue, reserve } = createFakeQueue();
        reserve.mockRejectedValue(apiError(HTTP_ERROR_TYPE.serverError));

        void queue.enqueue(silentPcm(CHUNK_FRAMES));
        void queue.enqueue(silentPcm(CHUNK_FRAMES));
        queue.setAcceptUntil(new Date(2_500).toISOString());
        const drained = expect(queue.drain()).rejects.toThrow(
          "조각 접수 시한이 지났어요",
        );
        await vi.advanceTimersByTimeAsync(2_500);
        await drained;

        // 다시 시도하던 예약의 첫 실패도 따로 보고해요
        const lostEvents = (await flushRealEvents()).filter(
          ({ exception }) =>
            exception?.values?.[0]?.value === "조각 접수 시한이 지났어요",
        );
        expect(lostEvents).toHaveLength(1);
        expect(lostEvents[0].tags).toMatchObject({ feature: "recording" });
        expect(lostEvents[0].extra).toEqual({ lostChunkCount: 2 });
        // Issue는 손실이 난 녹음 수를, 지표는 잃은 조각 수를 세요
        expect(
          (await flushMetrics()).filter(
            ({ name }) => name === "recording.chunks_lost",
          ),
        ).toEqual([
          { name: "recording.chunks_lost", value: 2, attributes: {} },
        ]);
      });

      it("시한 전에 모든 조각을 올렸으면 시한이 지나도 보고하지 않는다", async () => {
        const { queue } = createFakeQueue();

        await queue.enqueue(silentPcm(CHUNK_FRAMES));
        queue.setAcceptUntil(new Date(2_500).toISOString());
        await vi.advanceTimersByTimeAsync(2_500);

        expect(await flushRealEvents()).toHaveLength(0);
      });

      it("의도해 비운 큐의 조각은 보고하지 않는다", async () => {
        const { queue, reserve } = createFakeQueue();
        reserve.mockRejectedValue(apiError(HTTP_ERROR_TYPE.serverError));

        const tasks = [
          queue.enqueue(silentPcm(CHUNK_FRAMES)),
          queue.enqueue(silentPcm(CHUNK_FRAMES)),
        ].map((task) => task.catch(() => undefined));
        await vi.advanceTimersByTimeAsync(0);
        queue.clear();
        await Promise.all(tasks);

        // 다시 시도하던 예약의 첫 실패만 남아요
        const events = await flushRealEvents();
        expect(
          events.map(({ exception }) => exception?.values?.[0]?.value),
        ).toEqual(["HTTP 요청 실패: serverError"]);
      });

      it("해시를 계산하지 못하면 그 조각과 뒤 조각이 함께 멈추고, 그 오류를 한 번만 보고한다", async () => {
        const { queue, reserve } = createFakeQueue();
        vi.spyOn(crypto.subtle, "digest").mockImplementation(async () => {
          throw new DOMException("해시를 계산하지 못했어요", "OperationError");
        });

        const first = queue.enqueue(silentPcm(CHUNK_FRAMES));
        const second = queue.enqueue(silentPcm(CHUNK_FRAMES));
        await expect(first).rejects.toThrow("해시를 계산하지 못했어요");
        await expect(second).rejects.toThrow("해시를 계산하지 못했어요");

        expect(reserve).not.toHaveBeenCalled();
        const events = await flushRealEvents();
        expect(events).toHaveLength(1);
        expect(events[0].tags).toMatchObject({ feature: "recording" });
      });

      // 조각 요청 뮤테이션은 MutationCache가 보고하지 않아(meta.errorReport: caller) 큐가 보고해요
      it("요청을 고쳐야 하는 실패로 멈춘 조각은 큐가 한 번 보고한다", async () => {
        const { queue, complete } = createFakeQueue();
        const error = apiError(HTTP_ERROR_TYPE.forbidden);
        complete.mockRejectedValue(error);

        await expect(queue.enqueue(silentPcm(CHUNK_FRAMES))).rejects.toBe(
          error,
        );

        const events = await flushRealEvents();
        expect(events).toHaveLength(1);
        expect(events[0].tags).toMatchObject({
          source: "caught",
          feature: "recording",
        });
      });

      // 일시 실패는 횟수 제한 없이 다시 시도해, 시도마다 보내면 장애 한 번이 이벤트 수십 건이 돼요
      it("예약이 거듭 일시 실패하면 연속 실패의 첫 번째만 보고하고, 저장이 확인되면 다시 센다", async () => {
        const { queue, reserve } = createFakeQueue();
        reserve
          .mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.serverError))
          .mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.serverError))
          .mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.serverError));

        const first = queue.enqueue(silentPcm(CHUNK_FRAMES));
        await vi.advanceTimersByTimeAsync(7_000);
        await first;
        reserve.mockRejectedValueOnce(apiError(HTTP_ERROR_TYPE.serverError));
        const second = queue.enqueue(silentPcm(CHUNK_FRAMES));
        await vi.advanceTimersByTimeAsync(1_000);
        await second;

        const events = await flushRealEvents();
        expect(reserve).toHaveBeenCalledTimes(6);
        expect(events.map(({ extra }) => extra)).toEqual([
          { consecutiveFailures: 1 },
          { consecutiveFailures: 1 },
        ]);
      });
    });
  });

  describe("이어 붙이기와 보관", () => {
    /** 요청이 모두 바로 성공하는 큐 */
    const createStubQueue = (
      params: Partial<PcmChunkUploadQueueParams> = {},
    ) => {
      const reserved: number[][] = [];
      const queue = new PcmChunkUploadQueue({
        reserve: async ({ sequence, sampleOffset }) => {
          reserved.push([sequence, sampleOffset]);

          return { status: "RESERVED", uploadUrl: "url", requiredHeaders: {} };
        },
        upload: async () => undefined,
        complete: async (sequence) => ({
          status: "VERIFIED",
          verifiedThroughSequence: sequence,
          verifiedThroughFrame: 0,
        }),
        ...params,
      });

      return { queue, reserved };
    };

    it("서버가 확인한 다음 번호와 프레임부터 이어 붙인다", async () => {
      const { queue, reserved } = createStubQueue({
        nextSequence: 4,
        nextSampleOffset: 240_000,
      });

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      queue.enqueue(silentPcm(CHUNK_FRAMES));
      await queue.drain();

      expect(reserved).toEqual([
        [4, 240_000],
        [5, 320_000],
      ]);
    });

    it("넣는 조각을 보관하고 저장이 확인되면 지운다", async () => {
      const save = vi.fn(() => Promise.resolve());
      const removeThrough = vi.fn(() => Promise.resolve());
      const { queue } = createStubQueue({ archive: { save, removeThrough } });
      const pcm = silentPcm(CHUNK_FRAMES);

      queue.enqueue(pcm);
      expect(save).toHaveBeenCalledWith(1, pcm);
      expect(removeThrough).not.toHaveBeenCalled();

      await queue.drain();
      expect(removeThrough).toHaveBeenCalledWith(1);
    });

    it("앞 조각이 비어 있으면 뒤 조각이 확인돼도 그 보관본을 지우지 않는다", async () => {
      const removeThrough = vi.fn(() => Promise.resolve());
      const { queue } = createStubQueue({
        archive: { save: () => Promise.resolve(), removeThrough },
        // 서버는 앞 조각이 비어 있으면 빈틈없이 확인된 번호를 0으로 줘요
        complete: async () => ({
          status: "VERIFIED",
          verifiedThroughSequence: 0,
          verifiedThroughFrame: 0,
        }),
      });

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      await queue.drain();

      expect(removeThrough).toHaveBeenCalledWith(0);
    });

    it("보관이 거절돼도 전송은 이어 가고 거절을 알린다", async () => {
      const onArchiveFailed = vi.fn();
      const { queue, reserved } = createStubQueue({
        archive: {
          save: () => Promise.reject(new Error("QuotaExceededError")),
          removeThrough: () => Promise.resolve(),
        },
        onArchiveFailed,
      });

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      await queue.drain();

      expect(onArchiveFailed).toHaveBeenCalledTimes(1);
      expect(reserved).toHaveLength(1);
    });

    it("일시 실패로 밀리면 알리고, 저장이 확인되면 풀렸다고 알린다", async () => {
      const onStalledChange = vi.fn();
      let reserveCount = 0;
      const { queue } = createStubQueue({
        reserve: async () => {
          reserveCount += 1;
          if (reserveCount === 1) {
            throw createHttpError({ type: HTTP_ERROR_TYPE.network });
          }

          return { status: "RESERVED", uploadUrl: "url", requiredHeaders: {} };
        },
        onStalledChange,
      });

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      await vi.waitFor(() =>
        expect(onStalledChange.mock.calls).toEqual([[true]]),
      );
      expect(queue.hasBacklog).toBe(true);

      await queue.drain();
      expect(onStalledChange.mock.calls).toEqual([[true], [false]]);
      expect(queue.hasBacklog).toBe(false);
    });

    it("끊기지 않아도 조각이 쌓이면 밀린 것으로 본다", () => {
      const { queue } = createStubQueue({
        reserve: () => new Promise(() => {}),
      });

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      queue.enqueue(silentPcm(CHUNK_FRAMES));
      expect(queue.hasBacklog).toBe(false);

      queue.enqueue(silentPcm(CHUNK_FRAMES));
      expect(queue.hasBacklog).toBe(true);
      queue.clear();
    });
  });
});
