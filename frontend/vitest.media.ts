import { vi } from "vitest";

/**
 * jsdom에 없는 마이크·녹음·소리 분석 API를 흉내 내요.
 *
 * - `getUserMedia`는 늘 가짜 마이크를 돌려줘요. 권한 거부는 테스트에서 `vi.spyOn`으로 덮어요
 * - 마이크 끊김은 테스트가 받은 마이크의 트랙을 `stop()`하고 `ended` 이벤트를 보내 재현해요
 * - 분석기는 늘 같은 크기의 소리를 돌려줘요
 */
class FakeMediaStreamTrack extends EventTarget {
  kind = "audio";
  readyState: MediaStreamTrackState = "live";

  stop() {
    this.readyState = "ended";
  }
}

class FakeMediaStream {
  private tracks = [new FakeMediaStreamTrack()];

  getTracks() {
    return this.tracks;
  }

  getAudioTracks() {
    return this.tracks;
  }
}

class FakeMediaRecorder extends EventTarget {
  /** 모든 형식을 지원한다고 답해요. 미지원 브라우저는 테스트에서 `vi.spyOn`으로 덮어요 */
  static isTypeSupported() {
    return true;
  }

  state: RecordingState = "inactive";

  constructor(public stream: FakeMediaStream) {
    super();
  }

  start() {
    this.state = "recording";
  }

  pause() {
    if (this.state === "recording") this.state = "paused";
  }

  resume() {
    if (this.state === "paused") this.state = "recording";
  }

  stop() {
    this.state = "inactive";
    // 실제 녹음기처럼 멈춘 뒤 비동기로 `stop`을 알려요
    queueMicrotask(() => this.dispatchEvent(new Event("stop")));
  }
}

/** 분석기가 돌려주는 소리 크기(-1~1 사이 진폭). 막대가 0보다 높게 그려질 만큼만 둬요 */
const FAKE_AMPLITUDE = 0.5;

class FakeAnalyserNode {
  fftSize = 2048;

  connect() {}

  getFloatTimeDomainData(array: Float32Array) {
    array.fill(FAKE_AMPLITUDE);
  }
}

class FakeAudioContext {
  state: AudioContextState = "running";

  createMediaStreamSource() {
    return { connect: () => undefined, disconnect: () => undefined };
  }

  createAnalyser() {
    return new FakeAnalyserNode();
  }

  createMediaStreamDestination() {
    return { stream: new FakeMediaStream(), channelCount: 2 };
  }

  close() {
    this.state = "closed";

    return Promise.resolve();
  }
}

export const installFakeMedia = () => {
  Object.defineProperty(navigator, "mediaDevices", {
    configurable: true,
    value: {
      getUserMedia: async () => new FakeMediaStream() as unknown as MediaStream,
    },
  });
  vi.stubGlobal("MediaRecorder", FakeMediaRecorder);
  vi.stubGlobal("AudioContext", FakeAudioContext);
};
