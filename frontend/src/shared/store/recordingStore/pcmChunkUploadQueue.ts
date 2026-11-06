import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import { captureCaughtError, countMetric } from "@provider/sentry";
import { createRepeatedFailureReporter } from "@utils/createRepeatedFailureReporter";
import axios from "axios";

/** PUT과 저장 확인을 동시에 진행하는 조각 수의 상한 */
const MAX_CONCURRENT_TRANSFERS = 2;

/** PCM16은 프레임 하나가 2바이트예요 */
const BYTES_PER_FRAME = 2;

/** 일시 실패 뒤 다시 시도하기까지 기다리는 시간. 마지막 값은 횟수 제한 없이 계속 써요 */
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000];

/** 저장 확인이 PUT된 객체를 찾지 못했을 때의 서버 오류 코드. 같은 바이트를 다시 PUT해야 해요 */
const AUDIO_CHUNK_NOT_STORED = "AUDIO_CHUNK_NOT_STORED";

type PcmChunkStatus = "RESERVED" | "VERIFIED";

/** 봉인할 때 고정하는 조각 정보. 예약 요청 본문의 descriptor와 같은 모양이에요 */
export interface PcmChunkDescriptor {
  /** 1부터 연속 증가하는 조각 번호 */
  sequence: number;
  /** 앞 조각들의 `frameCount` 합. 첫 조각은 0이에요 */
  sampleOffset: number;
  frameCount: number;
  byteLength: number;
  /** PCM 전체의 SHA-256을 패딩 있는 표준 Base64로 인코딩한 값 */
  checksumSha256: string;
}

/** 종료 요청의 `audioSeal`. 서버 DTO의 `RecordingAudioSealInput`과 같은 모양이에요 */
export interface PcmAudioSeal {
  /** 마지막 조각 번호. 조각이 없으면 0 */
  lastSequence: number;
  /** 전체 프레임 수. 일시정지 시간은 빠지고, 조각이 없으면 0 */
  totalFrameCount: number;
  /** 예약 응답을 아직 받지 못한 조각. 응답을 기다리는 조각도 들어가요 */
  pendingDescriptors: PcmChunkDescriptor[];
}

interface ReserveResult {
  status: PcmChunkStatus;
  uploadUrl: string | null;
  requiredHeaders: Record<string, string>;
}

interface UploadParams {
  uploadUrl: string;
  requiredHeaders: Record<string, string>;
  pcm: ArrayBuffer;
}

interface CompleteResult {
  status: PcmChunkStatus;
  verifiedThroughFrame: number;
}

/** 조각의 서버 요청. 도메인 훅이 세션과 탭 증명을 묶어 넘겨요 */
export interface PcmChunkRequests {
  /** 조각 예약. 도메인 훅이 세션과 탭 증명을 붙인 뮤테이션을 넘겨요 */
  reserve: (descriptor: PcmChunkDescriptor) => Promise<ReserveResult>;
  /** 예약 응답의 서명 URL로 PCM을 PUT */
  upload: (params: UploadParams) => Promise<unknown>;
  /** 조각 저장 확인 */
  complete: (sequence: number) => Promise<CompleteResult>;
}

/** 저장이 확인될 때까지 조각을 브라우저에 보관하는 곳. 새로고침해도 남아요 */
interface PcmChunkArchive {
  save: (sequence: number, pcm: ArrayBuffer) => Promise<unknown>;
  remove: (sequence: number) => Promise<unknown>;
}

export interface PcmChunkUploadQueueParams extends PcmChunkRequests {
  /** 첫 조각의 번호. 이어 받은 녹음은 서버가 확인한 다음 번호부터 이어요. 기본은 1이에요 */
  nextSequence?: number;
  /** 첫 조각의 시작 프레임. 기본은 0이에요 */
  nextSampleOffset?: number;
  archive?: PcmChunkArchive;
  /** 조각을 보관하지 못했을 때 불러요. 저장 공간이 모자란 경우예요. 전송은 메모리의 바이트로 이어 가요 */
  onArchiveFailed?: () => void;
  /** 일시 실패로 조각이 밀리기 시작하거나(`true`) 저장 확인을 받아 풀릴 때(`false`) 불러요 */
  onStalledChange?: (isStalled: boolean) => void;
}

/** 번호와 길이는 넣는 즉시 고정하고, 해시는 전송 직전에 계산해 붙여요 */
interface SealedPcmChunk extends Omit<PcmChunkDescriptor, "checksumSha256"> {
  pcm: ArrayBuffer;
}

interface TransferAttemptParams extends Omit<UploadParams, "uploadUrl"> {
  /** PUT할 URL. 없으면 PUT을 건너뛰고 저장 확인만 보내요 */
  uploadUrl: string | null;
  sequence: number;
}

/** 한 번의 PUT·저장 확인 시도 뒤에 할 일 */
type TransferOutcome = "verified" | "reserveAgain" | "completeAgain";

/** 패딩 있는 표준 Base64로 인코딩한 SHA-256. 예약 요청과 S3 체크섬 헤더가 이 형식을 받아요 */
export const sha256Base64 = async (bytes: ArrayBuffer) => {
  const hash = await crypto.subtle.digest("SHA-256", bytes);

  return btoa(String.fromCharCode(...new Uint8Array(hash)));
};

/** 부를 때마다 다음 재시도 간격을 줘요. 1, 2, 4초 뒤로는 4초를 계속 줘요. 종료 요청의 재전송도 같은 간격을 써요 */
export const createBackoff = () => {
  let failures = 0;

  return () =>
    RETRY_DELAYS_MS[Math.min(failures++, RETRY_DELAYS_MS.length - 1)];
};

/** 예약·저장 확인·종료의 일시 실패. 응답을 못 받았거나 5xx, 429면 같은 요청을 다시 보내요 */
export const isTransientApiError = (error: unknown) =>
  isHttpError(error, HTTP_ERROR_TYPE.network) ||
  isHttpError(error, HTTP_ERROR_TYPE.serverError) ||
  isHttpError(error, HTTP_ERROR_TYPE.tooManyRequests);

/** PUT 응답을 못 받았거나 412(이미 객체가 있음)면 저장됐을 수 있어 실패로 단정하지 않아요 */
const isUploadPossiblyStored = (error: unknown) =>
  axios.isAxiosError(error) &&
  (error.response === undefined || error.response.status === 412);

/**
 * 봉인한 PCM 조각을 예약 → PUT → 저장 확인 순서로 올리는 전송 큐.
 *
 * - 넣는 순서대로 번호와 offset을 고정하고, 예약은 한 번에 하나씩 그 순서대로 보내요
 * - PUT과 저장 확인은 조각 2개까지 동시에 진행해요. 조각마다 전송은 한 번이라 같은 조각이 겹치지 않아요
 * - 예약 응답이 이미 `VERIFIED`면 PUT 없이 저장 확인만 보내 watermark를 받아요
 * - 저장 확인에서 `VERIFIED`를 받은 조각만 메모리에서 놓고 `verifiedThroughFrame`을 올려요
 * - 일시 실패는 같은 조각으로 1, 2, 4초 뒤, 그 뒤로는 4초 간격으로 횟수 제한 없이 다시 시도해요.
 *   다시 PUT할 일이 생기면 같은 descriptor로 재예약해 새 URL로 보내요
 * - 요청을 고쳐야 하는 실패(충돌, 권한 거절 같은 4xx)는 그 조각을 멈추고, 앞 조각의 예약이 멈추면 뒤 조각의 예약도 보내지 않아요
 * - `setAcceptUntil`의 시한이 지나거나 `clear`하면 남은 조각을 모두 멈추고 바이트를 놓아요
 * - 멈춘 조각의 이유는 Sentry로 보내요. 시한이 지나 버린 조각은 남은 수를 담아 한 건으로 보내고 그 수를 지표(`recording.chunks_lost`)로도 세요.
 *   `clear`는 의도한 중단(AbortError)이라 보고 기준에서 빠져요
 * - 다시 시도하는 실패는 시도마다 보내지 않고 연속 실패의 첫 번째만 보내요. 조각의 저장이 확인되면 다시 세요.
 *   그래서 조각 요청 뮤테이션은 MutationCache가 보고하지 않아요(`meta.errorReport`)
 * - `seal()`은 종료 요청에 담을 마지막 번호와 예약 응답을 아직 받지 못한 조각을 줘요. 종료 뒤에도 그 조각은 평소대로 예약부터 올려요
 * - 저장 확인을 받을 때까지 `archive`에도 조각을 보관해, 새로고침해도 다시 올릴 수 있어요. 보관에 실패해도 전송은 이어 가요
 * - 서버 요청은 밖에서 받아요. 이 저장소는 브라우저 안의 녹음만 다루고 API 호출은 도메인 훅이 맡아요
 */
export default class PcmChunkUploadQueue {
  private readonly reserve: PcmChunkUploadQueueParams["reserve"];
  private readonly upload: PcmChunkUploadQueueParams["upload"];
  private readonly complete: PcmChunkUploadQueueParams["complete"];
  private readonly archive: PcmChunkUploadQueueParams["archive"];
  private readonly onArchiveFailed: PcmChunkUploadQueueParams["onArchiveFailed"];
  private readonly onStalledChange: PcmChunkUploadQueueParams["onStalledChange"];

  private nextSequence: number;
  private nextSampleOffset: number;
  /** 일시 실패가 이어져 조각이 밀리고 있는지 */
  private isStalled = false;
  /** 저장 확인 전까지 들고 있는 조각 */
  private readonly pending = new Map<number, ArrayBuffer>();
  /** 예약 응답을 아직 받지 못한 조각의 descriptor. 해시를 전송 직전에 계산해 붙이므로 Promise로 들고 있어요 */
  private readonly unreserved = new Map<number, Promise<PcmChunkDescriptor>>();
  /** 예약을 순서대로 하나씩 보내는 사슬. 앞 예약이 멈추면 거부된 채 남아 뒤 예약도 함께 멈춰요 */
  private reserveChain: Promise<unknown> = Promise.resolve();
  private readonly inflight = new Set<Promise<void>>();
  private transferring = 0;
  private readonly transferWaiters: Array<() => void> = [];
  private verifiedFrame = 0;
  /** 큐 전체를 멈출 때 abort해요. 재시도를 기다리던 조각도 그 이유로 바로 깨어나요 */
  private readonly stop = new AbortController();
  private acceptUntilTimer: ReturnType<typeof setTimeout> | undefined;
  /** 다시 시도하는 예약, PUT, 저장 확인의 실패. 연속 실패의 첫 번째만 Sentry로 보내요 */
  private readonly retriedFailures = createRepeatedFailureReporter({
    feature: "recording",
    action: "녹음 조각 올리기",
  });

  constructor({
    reserve,
    upload,
    complete,
    nextSequence = 1,
    nextSampleOffset = 0,
    archive,
    onArchiveFailed,
    onStalledChange,
  }: PcmChunkUploadQueueParams) {
    this.reserve = reserve;
    this.upload = upload;
    this.complete = complete;
    this.nextSequence = nextSequence;
    this.nextSampleOffset = nextSampleOffset;
    this.archive = archive;
    this.onArchiveFailed = onArchiveFailed;
    this.onStalledChange = onStalledChange;
  }

  /** 첫 조각부터 빈틈없이 저장이 확인된 끝 프레임(그 프레임은 제외) */
  get verifiedThroughFrame() {
    return this.verifiedFrame;
  }

  /** 아직 저장 확인을 받지 못해 들고 있는 조각 번호 */
  get pendingSequences() {
    return [...this.pending.keys()];
  }

  /** 끊겨서 조각이 쌓여 있는지. 종료 뒤 수신 창으로 미루지 않고 먼저 올려야 해요 */
  get hasBacklog() {
    return this.isStalled || this.pending.size > MAX_CONCURRENT_TRANSFERS;
  }

  /** 조각을 봉인해 전송을 예약해요. 돌려주는 Promise는 그 조각의 저장이 확인되면 끝나고, 조각이 멈추면 그 이유로 거부돼요 */
  enqueue(pcm: ArrayBuffer) {
    const frameCount = pcm.byteLength / BYTES_PER_FRAME;
    const chunk: SealedPcmChunk = {
      sequence: this.nextSequence,
      sampleOffset: this.nextSampleOffset,
      frameCount,
      byteLength: pcm.byteLength,
      pcm,
    };
    this.nextSequence += 1;
    this.nextSampleOffset += frameCount;
    // 멈춘 뒤에 들어온 조각은 올릴 수 없어 들고 있지 않아요
    if (!this.stopReason) {
      this.pending.set(chunk.sequence, pcm);
      this.archive?.save(chunk.sequence, pcm).catch(() => this.onArchiveFailed?.());
    }

    const task: Promise<void> = this.transfer(chunk).finally(() => {
      this.inflight.delete(task);
    });
    this.inflight.add(task);
    // 호출자가 결과를 받지 않아도 멈춘 조각이 unhandled rejection이 되지 않게 해요.
    // 뒤 조각은 앞 조각과 같은 오류 객체로 멈추므로 SDK가 한 번만 보내요
    task.catch((error: unknown) => {
      captureCaughtError({ error, feature: "recording" });
    });

    return task;
  }

  /** 넣어 둔 조각이 모두 끝날 때까지 기다려요. 저장 확인을 못 받고 멈춘 조각이 있으면 그 첫 이유로 거부돼요 */
  async drain() {
    let failure: { reason: unknown } | undefined;
    while (this.inflight.size > 0) {
      await Promise.all(
        [...this.inflight].map((task) =>
          task.catch((reason: unknown) => {
            failure ??= { reason };
          }),
        ),
      );
    }
    if (failure) throw failure.reason;
  }

  /** 종료 요청의 `audioSeal`. 응답을 기다리는 조각도 담아요. 서버는 이미 예약한 같은 descriptor를 통과시켜요 */
  async seal(): Promise<PcmAudioSeal> {
    return {
      lastSequence: this.nextSequence - 1,
      totalFrameCount: this.nextSampleOffset,
      pendingDescriptors: await Promise.all(this.unreserved.values()),
    };
  }

  /** 종료 뒤 서버가 조각을 받아 주는 시한(ISO 8601). 지나면 남은 조각의 재시도를 멈추고 바이트를 놓아요 */
  setAcceptUntil(acceptUntil: string) {
    clearTimeout(this.acceptUntilTimer);
    this.acceptUntilTimer = setTimeout(
      () => {
        const lostChunkCount = this.pending.size;
        const reason = new Error("조각 접수 시한이 지났어요");
        this.stopAll(reason);
        // 조각들의 거부보다 먼저 보내 남은 조각 수를 실어요. 조각들은 같은 오류 객체로 멈춰 다시 보내지 않아요
        if (lostChunkCount > 0) {
          captureCaughtError({
            error: reason,
            feature: "recording",
            extra: { lostChunkCount },
          });
          // Issue는 손실이 난 녹음 수를, 지표는 잃은 조각 수를 세요
          countMetric({
            name: "recording.chunks_lost",
            attributes: {},
            value: lostChunkCount,
          });
        }
      },
      Math.max(0, new Date(acceptUntil).getTime() - Date.now()),
    );
  }

  /** 모든 조각을 멈추고 들고 있던 바이트를 놓아요. 그 뒤로는 요청을 보내지 않아요 */
  clear() {
    // 의도한 중단이라 취소(AbortError)로 멈춰 Sentry 보고 기준에서 빠져요
    this.stopAll(new DOMException("전송 큐를 비웠어요", "AbortError"));
  }

  private stopAll(reason: Error) {
    clearTimeout(this.acceptUntilTimer);
    this.pending.clear();
    this.unreserved.clear();
    this.stop.abort(reason);
  }

  private get stopReason() {
    return this.stop.signal.aborted
      ? (this.stop.signal.reason as Error)
      : undefined;
  }

  private assertRunning() {
    if (this.stopReason) throw this.stopReason;
  }

  private async transfer(chunk: SealedPcmChunk) {
    const { pcm, ...descriptor } = chunk;
    const described = sha256Base64(pcm).then((checksumSha256) => ({
      ...descriptor,
      checksumSha256,
    }));
    this.unreserved.set(descriptor.sequence, described);
    // 앞 조각이 멈춰 이 조각의 예약을 보내지 않으면 해시 실패를 기다리는 곳이 없어 unhandled rejection이 돼요
    described.catch(() => undefined);
    const nextDelay = createBackoff();
    const reserveChunk = async () => this.reserve(await described);

    // 첫 예약은 앞 조각의 예약이 끝난 뒤에만 보내요. 재예약은 이미 번호가 잡혀 있어 사슬을 타지 않아요
    const firstReservation = this.reserveChain.then(() =>
      this.retrying(reserveChunk, nextDelay),
    );
    this.reserveChain = firstReservation;
    let reservation: ReserveResult;
    try {
      reservation = await firstReservation;
    } finally {
      // 응답을 받았거나 멈춘 조각은 더는 봉인에 담지 않아요
      this.unreserved.delete(descriptor.sequence);
    }
    // `VERIFIED` 예약은 URL이 없어 PUT을 건너뛰고 저장 확인만 보내요
    let uploadUrl = reservation.uploadUrl;

    for (;;) {
      const outcome = await this.attemptTransfer({
        uploadUrl,
        requiredHeaders: reservation.requiredHeaders,
        pcm,
        sequence: descriptor.sequence,
      });
      if (outcome === "verified") return;

      await this.wait(nextDelay());
      if (outcome === "reserveAgain") {
        reservation = await this.retrying(reserveChunk, nextDelay);
        uploadUrl = reservation.uploadUrl;
      } else {
        // PUT은 이미 끝났으니 저장 확인만 다시 보내요
        uploadUrl = null;
      }
    }
  }

  /** 전송 자리를 잡고 PUT과 저장 확인을 한 번 시도해요. 멈춤 조건은 던지고, 다시 할 일은 돌려줘요 */
  private async attemptTransfer({
    uploadUrl,
    requiredHeaders,
    pcm,
    sequence,
  }: TransferAttemptParams): Promise<TransferOutcome> {
    await this.acquireTransferSlot();
    try {
      if (uploadUrl !== null) {
        this.assertRunning();
        try {
          await this.upload({ uploadUrl, requiredHeaders, pcm });
        } catch (error) {
          // 403(URL 만료)을 포함한 나머지 PUT 실패는 재예약해 새 URL로 다시 보내요
          if (!isUploadPossiblyStored(error)) {
            this.reportRetriedFailure(error);

            return "reserveAgain";
          }
        }
      }

      // 이미 VERIFIED인 조각도 저장 확인을 보내 watermark를 받아요. 서버는 같은 결과를 다시 돌려줘요
      this.assertRunning();
      try {
        const completed = await this.complete(sequence);
        if (completed.status !== "VERIFIED") return "reserveAgain";

        this.pending.delete(sequence);
        void this.archive?.remove(sequence).catch(() => undefined);
        this.retriedFailures.reset();
        this.setStalled(false);
        // 뒤 조각의 응답이 먼저 와도 watermark가 줄지 않게 큰 값만 받아요
        this.verifiedFrame = Math.max(
          this.verifiedFrame,
          completed.verifiedThroughFrame,
        );

        return "verified";
      } catch (error) {
        if (isHttpError(error) && error.code === AUDIO_CHUNK_NOT_STORED) {
          this.retriedFailures.report({ error });

          return "reserveAgain";
        }
        if (isTransientApiError(error)) {
          this.reportRetriedFailure(error);

          return "completeAgain";
        }
        throw error;
      }
    } finally {
      this.releaseTransferSlot();
    }
  }

  /** 일시 실패면 간격을 두고 같은 요청을 반복해요. 그 밖의 실패는 그대로 던져 조각을 멈춰요 */
  private async retrying<T>(
    request: () => Promise<T>,
    nextDelay: () => number,
  ) {
    for (;;) {
      this.assertRunning();
      try {
        return await request();
      } catch (error) {
        if (!isTransientApiError(error)) throw error;
        this.reportRetriedFailure(error);
      }
      await this.wait(nextDelay());
    }
  }

  private reportRetriedFailure(error: unknown) {
    this.retriedFailures.report({ error });
    this.setStalled(true);
  }

  private setStalled(isStalled: boolean) {
    if (this.isStalled === isStalled) return;

    this.isStalled = isStalled;
    this.onStalledChange?.(isStalled);
  }

  /** 재시도 간격을 기다려요. 그 사이 큐가 멈추면 그 이유로 바로 깨어나요 */
  private wait(ms: number) {
    this.assertRunning();
    const { signal } = this.stop;

    return new Promise<void>((resolve, reject) => {
      const onAbort = () => {
        clearTimeout(timer);
        reject(signal.reason);
      };
      const timer = setTimeout(() => {
        signal.removeEventListener("abort", onAbort);
        resolve();
      }, ms);
      signal.addEventListener("abort", onAbort, { once: true });
    });
  }

  private async acquireTransferSlot() {
    if (this.transferring < MAX_CONCURRENT_TRANSFERS) {
      this.transferring += 1;

      return;
    }

    // 자리가 날 때까지 기다려요. 깨어나면 자리를 넘겨받은 상태라 수를 다시 세지 않아요
    await new Promise<void>((resolve) => this.transferWaiters.push(resolve));
  }

  private releaseTransferSlot() {
    const next = this.transferWaiters.shift();
    if (next) next();
    else this.transferring -= 1;
  }
}
