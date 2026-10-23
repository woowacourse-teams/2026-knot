import { create } from "zustand";
import { markRecordingStart } from "@utils/recordingStartTrace";

import type { RecordingAudioMode } from "@/shared/types/recording";

import Microphone from "./microphone";
import {
  isRecordingAudioTypeSupported,
  UnsupportedRecordingAudioTypeError,
} from "./microphoneConnection";
import PcmChunkUploadQueue, {
  type PcmAudioSeal,
  type PcmChunkUploadQueueParams,
} from "./pcmChunkUploadQueue";
import PcmCollector from "./pcmCollector";
import RecordedAudio from "./recordedAudio";

/** 녹음이 없는 상태·녹음 중·일시정지. 한 번에 하나만 녹음해요. */
export type RecordingStatus = "idle" | "recording" | "paused";

/** 서버에 열어 둔 녹음 세션. 일시정지·재개·종료 요청 경로에 들어가요 */
export interface RecordingSession {
  workspaceId: number;
  recordingId: number;
  /** 오디오 저장 방식. PCM이면 녹음 중 조각을 바로 올리고, WebM이면 끝낸 뒤 파일 하나를 올려요 */
  audioMode: RecordingAudioMode;
}

interface ConnectMicrophoneParams {
  /** 녹음할 오디오 저장 방식. PCM이면 16kHz 수집기까지 만들어요. 기본은 WebM이에요 */
  audioMode?: RecordingAudioMode;
}

interface DiscardRecordingParams {
  /**
   * 끝낸 PCM 녹음의 남은 조각을 서버가 받아 주는 시한(ISO 8601). 주면 큐를 남겨 그때까지 뒤에서 올리고,
   * 주지 않으면 큐도 비워요
   */
  acceptUntil?: string;
}

interface StartRecordingParams {
  workspaceId: number;
  recordingId: number;
  /** PCM 조각의 예약·PUT·저장 확인 요청. 도메인 훅이 세션과 최초 탭 증명을 묶어 넘겨요. PCM으로 마이크를 받았을 때만 써요 */
  chunkRequests?: PcmChunkUploadQueueParams;
}

interface RecordingState {
  status: RecordingStatus;
  /** 서버 녹음 세션. 녹음 중·일시정지일 때만 값이 있어요 */
  session: RecordingSession | null;
  /** 지난 녹음 구간들의 길이를 더한 값(ms). 일시정지할 때마다 쌓여요 */
  accumulatedMs: number;
  /** 지금 구간을 시작하거나 이어 간 시각. 녹음 중일 때만 값이 있어요 */
  resumedAt: number | null;
  /** 마이크 소리 크기를 읽는 분석기. 마이크를 받고 있을 때만 값이 있어요 */
  analyser: AnalyserNode | null;
  /** 녹음 중에 마이크가 끊겨 저절로 일시정지했는지. 사용자가 알림을 확인하면 다시 `false`가 돼요 */
  isMicrophoneLost: boolean;
  /** 마지막으로 마이크를 받지 못한 오류 이름(`NotAllowedError` 등). 받았으면 `null`이에요. 시작하지 못한 이유를 사용 기록으로 보낼 때 읽어요 */
  microphoneErrorName: string | null;
  /** 녹음을 끝내는 중인지. 종료 요청을 보낼 때부터 업로드를 마치고 녹음을 비울 때까지 `true`예요 */
  isEnding: boolean;
  /** 끝낸 PCM 녹음의 조각을 아직 올리는 중인지. 큐가 비거나 멈추거나 시한이 지나면 `false`가 돼요 */
  isUploadingChunks: boolean;
}

interface RecordingActions {
  /**
   * 녹음을 시작하기 전에 마이크를 받아요. 받지 못하면 `false`를 돌려주고 아무것도 바꾸지 않아요.
   * 브라우저가 서버가 받는 형식으로 녹음할 수 없으면 권한을 묻지 않고 `UnsupportedRecordingAudioTypeError`를 던져요.
   * PCM이면 받은 마이크로 16kHz 수집기를 만들고, 만들지 못하면 마이크는 둔 채 `UnsupportedPcmCollectorError`를 던져요
   */
  connectMicrophone: (params?: ConnectMicrophoneParams) => Promise<boolean>;
  /** 받아 둔 마이크로 녹음을 시작해요. 서버에 녹음을 연 뒤에 불러요 */
  startRecording: (params: StartRecordingParams) => void;
  /** 수집을 멈춰요. PCM이면 남은 소리를 마지막 조각으로 봉인해 큐에 넣을 때까지 기다려요 */
  pauseRecording: () => Promise<void>;
  /**
   * 끊긴 마이크나 멈춘 PCM 수집기를 다시 받아요. 받지 못하면 `false`를 돌려주고 상태는 바꾸지 않아요.
   * PCM 녹음은 서버 재개가 성공한 뒤에 수집을 이어 가므로, 서버에 알리기 전에 따로 불러요
   */
  ensureMicrophone: () => Promise<boolean>;
  /** 이어서 녹음해요. 마이크가 끊겼다면 다시 받고, 받지 못하면 `false`를 돌려주고 일시정지를 유지해요 */
  resumeRecording: () => Promise<boolean>;
  /** 종료 요청을 보내기 전에 끝내는 중으로 표시해 끝내기가 겹치지 않게 해요. 수집과 시간은 그대로 둬요 */
  beginEnding: () => void;
  /** 종료 요청이 실패해 녹음을 이어 갈 때 끝내는 중 표시를 풀어요 */
  cancelEnding: () => void;
  /** 녹음한 시간을 서버가 알려 준 누적 시간(ms)으로 맞춰요. 녹음 중이면 그 값에서 이어서 재요 */
  syncElapsedTime: (elapsedMs: number) => void;
  /** 수집을 멈추고 지금까지 녹음한 오디오를 파일 하나로 돌려줘요. PCM이면 남은 소리를 마지막 조각으로 봉인해요. 녹음은 비우지 않아요 */
  stopRecording: () => Promise<Blob>;
  /** PCM 수집을 멈추고 마지막 조각을 봉인한 뒤 종료 요청에 담을 `audioSeal`을 돌려줘요. 녹음은 비우지 않아요 */
  sealRecording: () => Promise<PcmAudioSeal>;
  /** PCM 수집기만 닫고 마이크는 둬요. 서버가 조각 저장을 꺼 뒀을 때 받아 둔 마이크로 WebM 녹음을 시작하려고 써요 */
  closePcmCollector: () => void;
  /** 녹음을 버리고 마이크를 꺼요. PCM 큐는 `acceptUntil`을 주면 남겨 끝까지 올리고, 없으면 함께 비워요 */
  discardRecording: (params?: DiscardRecordingParams) => void;
  /** 마이크가 끊겼다는 표시(`isMicrophoneLost`)를 지워요 */
  clearMicrophoneLost: () => void;
}

const IDLE_STATE: RecordingState = {
  status: "idle",
  session: null,
  accumulatedMs: 0,
  resumedAt: null,
  analyser: null,
  isMicrophoneLost: false,
  microphoneErrorName: null,
  isEnding: false,
  isUploadingChunks: false,
};

/** 진행 중인 구간을 누적 시간에 더해 시간 표시를 멈춰요 */
const freezeElapsed = ({
  accumulatedMs,
  resumedAt,
}: Pick<RecordingState, "accumulatedMs" | "resumedAt">) => ({
  accumulatedMs:
    resumedAt === null
      ? accumulatedMs
      : accumulatedMs + (Date.now() - resumedAt),
  resumedAt: null,
});

/**
 * 진행 중인 녹음을 담는 전역 저장소.
 *
 * 녹음은 독의 마이크로 시작하지만 다른 화면으로 옮겨 가도 계속되고, 녹음 화면과 독이 서로 다른 구획이라
 * 한곳에 두고 함께 읽어요.
 *
 * 마이크 권한은 녹음 화면에 들어가기 전에 받아요. 받지 못하면 상태를 바꾸지 않아, 부른 쪽이 그 자리에서
 * 안내를 띄울 수 있어요. 녹음 중에 마이크가 빠지거나 PCM 수집기가 멈추면 저절로 일시정지하고 `isMicrophoneLost`로 알려요.
 *
 * WebM 녹음은 녹음기의 조각을 모아 뒀다가 끝낼 때 파일 하나로 묶어요. PCM 녹음은 수집기가 봉인한 5초 조각을
 * 전송 큐가 바로 올리고, 일시정지하면 남은 소리를 마지막 조각으로 봉인해요. 끝낸 뒤에도 큐는 녹음과 따로 남아
 * 시한까지 남은 조각을 올리고, 그동안은 `isUploadingChunks`로 알려요.
 *
 * 여기는 브라우저 안의 녹음만 다루고, 서버 녹음 세션 요청은 `@hooks/domain/recording/useRecordingControl`이 맡아요.
 * 화면은 도메인 훅으로 읽고, 테스트는 녹음이 테스트끼리 새지 않도록 여기서 직접 초기화해요.
 */
export const useRecordingStore = create<RecordingState & RecordingActions>()((
  set,
  get,
) => {
  const recordedAudio = new RecordedAudio();
  let pcmCollector: PcmCollector | null = null;
  let chunkQueue: PcmChunkUploadQueue | null = null;
  /** 마지막 일시정지의 봉인. 수집기를 바꾸기 전에 기다려 마지막 조각을 잃지 않아요 */
  let pcmFlushed: Promise<void> = Promise.resolve();

  const handleLost = () => {
    void get().pauseRecording();
    set({ analyser: null, isMicrophoneLost: true });
  };

  const microphone = new Microphone({
    onData: (chunk) => recordedAudio.append(chunk),
    onLost: handleLost,
  });

  /** 받아 둔 마이크로 PCM 수집기를 만들어요. 봉인한 조각은 큐로 넘겨요 */
  const createPcmCollector = (stream: MediaStream) =>
    PcmCollector.create({
      stream,
      // 올리지 못한 조각은 큐가 들고 있다가 drain으로 알려 주므로 여기선 삼켜요. 복구는 #794에서 붙여요
      onChunk: (pcm) => void chunkQueue?.enqueue(pcm).catch(() => undefined),
      onLost: handleLost,
    });

  return {
    ...IDLE_STATE,

    connectMicrophone: async ({ audioMode = "FINAL_WEBM_V1" } = {}) => {
      if (!isRecordingAudioTypeSupported()) {
        throw new UnsupportedRecordingAudioTypeError();
      }

      const connection = await microphone.connect();
      set({ microphoneErrorName: microphone.errorName });
      if (!connection) return false;
      // 수집기를 만들지 못해도 받아 둔 마이크로 WebM 녹음을 할 수 있게 파형은 먼저 둬요
      set({ analyser: connection.analyser });

      if (audioMode === "PCM16_CHUNKS_V1") {
        pcmCollector?.close();
        pcmCollector = await createPcmCollector(connection.stream);
        markRecordingStart("pcm-ready");
      }

      return true;
    },

    // 이미 녹음이 있으면 새로 시작하지 않는다.
    startRecording: ({ workspaceId, recordingId, chunkRequests }) => {
      if (get().status !== "idle") return;

      recordedAudio.clear();
      chunkQueue = null;
      if (pcmCollector && chunkRequests) {
        chunkQueue = new PcmChunkUploadQueue(chunkRequests);
        pcmCollector.start();
      } else {
        microphone.start();
      }
      set({
        status: "recording",
        session: {
          workspaceId,
          recordingId,
          audioMode: chunkQueue ? "PCM16_CHUNKS_V1" : "FINAL_WEBM_V1",
        },
        accumulatedMs: 0,
        resumedAt: Date.now(),
      });
    },

    pauseRecording: async () => {
      const { status, resumedAt } = get();
      if (status !== "recording" || resumedAt === null) return;

      microphone.pause();
      set({ status: "paused", ...freezeElapsed(get()) });

      if (pcmCollector) {
        // 봉인한 뒤 다시 시작하기 전까지 들어온 소리는 수집기가 버려요
        pcmFlushed = pcmCollector.flush();
        await pcmFlushed;
      }
    },

    ensureMicrophone: async () => {
      if (microphone.isLive() && (pcmCollector?.isLive ?? true)) return true;

      const connection = await microphone.connect();
      set({ microphoneErrorName: microphone.errorName });
      if (!connection) return false;

      if (pcmCollector) {
        // 멈춘 수집기의 마지막 봉인이 끝난 뒤에 닫아야 그 조각을 잃지 않아요
        await pcmFlushed;
        pcmCollector.close();
        pcmCollector = await createPcmCollector(connection.stream);
      } else {
        microphone.start();
      }
      set({ analyser: connection.analyser });

      return true;
    },

    resumeRecording: async () => {
      if (get().status !== "paused") return get().status === "recording";

      const isReady = await get().ensureMicrophone();
      if (!isReady) return false;

      if (pcmCollector) pcmCollector.start();
      else microphone.resume();

      set({
        status: "recording",
        resumedAt: Date.now(),
        isMicrophoneLost: false,
      });

      return true;
    },

    syncElapsedTime: (elapsedMs) => {
      const { status, resumedAt } = get();
      if (status === "idle") return;

      set({
        accumulatedMs: elapsedMs,
        // 일시정지처럼 멈춰 둔 시간이 다시 흐르지 않게, 재는 중일 때만 지금부터 다시 재요
        resumedAt: resumedAt === null ? null : Date.now(),
      });
    },

    beginEnding: () => set({ isEnding: true }),

    cancelEnding: () => set({ isEnding: false }),

    stopRecording: async () => {
      set({ isEnding: true, ...freezeElapsed(get()) });

      await pcmCollector?.flush();
      await microphone.stop();
      set({ analyser: null });

      return recordedAudio.toBlob();
    },

    sealRecording: async () => {
      await get().stopRecording();

      return (
        chunkQueue?.seal() ?? {
          lastSequence: 0,
          totalFrameCount: 0,
          pendingDescriptors: [],
        }
      );
    },

    closePcmCollector: () => {
      pcmCollector?.close();
      pcmCollector = null;
    },

    discardRecording: ({ acceptUntil } = {}) => {
      microphone.disconnect();
      // 서버가 먼저 끝낸 녹음의 남은 소리는 봉인하지 않고 버려요
      pcmCollector?.close();
      pcmCollector = null;
      recordedAudio.clear();

      const queue = chunkQueue;
      if (queue && acceptUntil) {
        queue.setAcceptUntil(acceptUntil);
        // 큐가 비거나 멈추거나 시한이 지나면 올리기를 마친 것으로 봐요. 멈춘 이유는 큐가 콘솔에 남길 몫이 아니라 삼켜요
        void queue
          .drain()
          .catch(() => undefined)
          .finally(() => set({ isUploadingChunks: false }));
      } else {
        queue?.clear();
        chunkQueue = null;
      }
      set({ ...IDLE_STATE, isUploadingChunks: chunkQueue !== null });
    },

    clearMicrophoneLost: () => set({ isMicrophoneLost: false }),
  };
});
