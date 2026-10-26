import { PCM_PROCESSOR_NAME, PCM_SAMPLE_RATE } from "./pcmChunker";
import type { PcmProcessorMessage } from "./pcmProcessor";

/** 브라우저가 16kHz PCM 수집기를 만들 수 없어 PCM 녹음을 시작하지 않을 때 던져요 */
export class UnsupportedPcmCollectorError extends Error {
  constructor() {
    super(`이 브라우저는 ${PCM_SAMPLE_RATE}Hz PCM으로 녹음할 수 없어요`);
    this.name = "UnsupportedPcmCollectorError";
  }
}

export interface PcmCollectorParams {
  stream: MediaStream;
  onChunk: (pcm: ArrayBuffer) => void;
  onLost: () => void;
}

interface PcmCollectorNodes {
  audioContext: AudioContext;
  node: AudioWorkletNode;
}

/**
 * 받은 마이크 소리를 AudioWorklet으로 PCM16LE 16kHz mono 5초 조각으로 봉인해 `onChunk`로 넘겨요.
 * 마이크 연결과 파형이 쓰는 컨텍스트와는 별개의 16kHz 컨텍스트를 써요.
 *
 * - `create()`로 만들고, `start()`를 불러야 모으기 시작해요
 * - `flush()`는 남은 소리를 마지막 조각으로 봉인할 때까지 기다려요. 그 뒤 다시 `start()`하기 전까지
 *   들어온 소리는 버려요
 * - 마이크 트랙이 끝나거나 컨텍스트가 멈추거나 processor가 죽으면 `onLost`로 한 번만 알려요
 * - `close()`는 자기 컨텍스트만 닫고 마이크 트랙은 끄지 않아요
 */
export default class PcmCollector {
  private readonly audioContext: AudioContext;
  private readonly node: AudioWorkletNode;
  private isClosed = false;
  private isLost = false;
  private flushWaiters: (() => void)[] = [];

  /** 16kHz 컨텍스트·worklet·마이크 연결 중 하나라도 안 되면 `UnsupportedPcmCollectorError`를 던져요 */
  static async create(params: PcmCollectorParams) {
    // webpack은 `context.audioWorklet.addModule(new URL(...))` 모양(변수 이름 포함)만 worklet으로 묶어요
    let context: AudioContext;
    try {
      context = new AudioContext({ sampleRate: PCM_SAMPLE_RATE });
    } catch {
      throw new UnsupportedPcmCollectorError();
    }

    try {
      // 브라우저가 요청한 레이트를 조용히 바꿀 수 있어 실제 값을 확인해요
      if (context.sampleRate !== PCM_SAMPLE_RATE) throw new Error();

      await context.audioWorklet.addModule(
        // 이름이 없으면 webpack 5.109가 worklet 런타임을 정하지 못해 공용 모듈(pcmChunker) 해시에서 빌드가 깨져요
        /* webpackChunkName: "pcmProcessor" */ new URL(
          "./pcmProcessor",
          import.meta.url,
        ),
      );
      const node = new AudioWorkletNode(context, PCM_PROCESSOR_NAME);
      context.createMediaStreamSource(params.stream).connect(node);
      // 출력에 닿아야 processor가 돌아요. 출력은 비워 두므로 소리는 나지 않아요
      node.connect(context.destination);
      await context.resume();

      return new PcmCollector({ ...params, audioContext: context, node });
    } catch {
      void context.close();
      throw new UnsupportedPcmCollectorError();
    }
  }

  private constructor({
    stream,
    onChunk,
    onLost,
    audioContext,
    node,
  }: PcmCollectorParams & PcmCollectorNodes) {
    this.audioContext = audioContext;
    this.node = node;

    node.port.onmessage = ({ data }: MessageEvent<PcmProcessorMessage>) => {
      if (this.isClosed) return;
      if (data.type === "chunk") onChunk(data.pcm);
      if (data.type === "flushed") this.settleFlush();
    };

    const lose = () => {
      if (this.isClosed || this.isLost) return;
      this.isLost = true;
      this.settleFlush();
      onLost();
    };
    stream
      .getTracks()
      .forEach((track) => track.addEventListener("ended", lose));
    audioContext.addEventListener("statechange", () => {
      if (audioContext.state !== "running") lose();
    });
    node.addEventListener("processorerror", lose);
  }

  /** 마이크 트랙이 끝나거나 컨텍스트가 멈추거나 processor가 죽어 더는 모으지 못하면 `false`예요 */
  get isLive() {
    return !this.isLost && !this.isClosed;
  }

  start() {
    this.node.port.postMessage("start");
  }

  /** 회의 소리 같은 다른 소리를 마이크와 섞어 함께 모아요. 그 소리가 끝나도 `onLost`로 알리지 않아요 */
  addAudio(stream: MediaStream) {
    this.audioContext.createMediaStreamSource(stream).connect(this.node);
  }

  /**
   * 남은 소리를 마지막 조각으로 봉인해 `onChunk`로 넘길 때까지 기다려요.
   * 컨텍스트가 멈췄으면 응답이 오지 않으므로 받은 소리까지만 두고 바로 끝나요
   */
  flush() {
    if (this.isClosed || this.audioContext.state !== "running") {
      return Promise.resolve();
    }

    return new Promise<void>((resolve) => {
      this.flushWaiters.push(resolve);
      this.node.port.postMessage("flush");
    });
  }

  close() {
    this.isClosed = true;
    this.settleFlush();
    void this.audioContext.close();
  }

  private settleFlush() {
    this.flushWaiters.forEach((resolve) => resolve());
    this.flushWaiters = [];
  }
}
