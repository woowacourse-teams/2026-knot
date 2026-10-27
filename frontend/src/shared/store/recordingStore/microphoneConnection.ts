export const stopTracks = (stream: MediaStream) =>
  stream.getTracks().forEach((track) => track.stop());

/** 서버가 받는 최종 오디오 형식. 서버는 Content-Type을 정확히 이 값으로만 받아요 */
export const RECORDING_AUDIO_TYPE = "audio/webm";

/** 이 브라우저가 서버가 받는 형식(`RECORDING_AUDIO_TYPE`)으로 녹음할 수 있는지 */
export const isRecordingAudioTypeSupported = () =>
  MediaRecorder.isTypeSupported(RECORDING_AUDIO_TYPE);

/** 브라우저가 서버가 받는 형식으로 녹음할 수 없어 녹음을 시작하지 않을 때 던져요 */
export class UnsupportedRecordingAudioTypeError extends Error {
  constructor() {
    super(`이 브라우저는 ${RECORDING_AUDIO_TYPE} 형식으로 녹음할 수 없어요`);
    this.name = "UnsupportedRecordingAudioTypeError";
  }
}

export interface MicrophoneConnectionParams {
  stream: MediaStream;
  onData: (chunk: Blob) => void;
  onLost: () => void;
}

/**
 * 받은 마이크 하나에 녹음기와 분석기를 붙인 연결. 셋은 함께 꺼져요.
 *
 * 녹음기는 `start()`를 불러야 모으기 시작해요. 마이크 권한을 받은 뒤 서버에 녹음을 연 다음에
 * 시작하기 위해서예요.
 * 녹음기는 마이크를 바로 녹음하지 않고 분석기를 거쳐 섞은 소리를 녹음해요. 녹음 중에 `addAudio()`로
 * 회의 소리를 더해도 같은 파일에 이어 담기 위해서예요.
 * 마이크가 빠지면 `onLost`로 알리고, 닫은 뒤의 알림과 조각은 무시해요. 더한 소리가 끝나는 것은 알리지 않아요.
 */
export default class MicrophoneConnection {
  readonly analyser: AnalyserNode;

  /** 받은 마이크. PCM 수집기가 같은 마이크를 써요 */
  readonly stream: MediaStream;
  private readonly recorder: MediaRecorder;
  private readonly audioContext: AudioContext;
  private isClosed = false;

  constructor({ stream, onData, onLost }: MicrophoneConnectionParams) {
    this.stream = stream;

    this.audioContext = new AudioContext();
    this.analyser = this.audioContext.createAnalyser();
    this.audioContext.createMediaStreamSource(stream).connect(this.analyser);
    // 분석기는 받은 소리를 그대로 내보내요. 마이크처럼 mono로 녹음해요
    const mixed = this.audioContext.createMediaStreamDestination();
    mixed.channelCount = 1;
    this.analyser.connect(mixed);

    // 지원하지 않는 브라우저는 마이크를 받기 전에 막는다. (우리 서비스는 webm형식만 지원하고, 지원하지 않는 브라우저 사용하는 경우는 나중에 고민해보기!)
    this.recorder = new MediaRecorder(mixed.stream, {
      mimeType: RECORDING_AUDIO_TYPE,
    });
    this.recorder.addEventListener("dataavailable", (e: BlobEvent) => {
      if (!this.isClosed && e.data.size > 0) onData(e.data);
    });

    stream.getTracks().forEach((track) =>
      track.addEventListener("ended", () => {
        if (!this.isClosed) onLost();
      }),
    );
  }

  start() {
    if (this.recorder.state === "inactive") this.recorder.start();
  }

  /** 회의 소리 같은 다른 소리를 마이크와 섞어 녹음기와 파형에 더해요 */
  addAudio(stream: MediaStream) {
    this.audioContext.createMediaStreamSource(stream).connect(this.analyser);
  }

  isLive() {
    return this.stream.getTracks().some((track) => track.readyState === "live");
  }

  pause() {
    if (this.recorder.state === "recording") this.recorder.pause();
  }

  resume() {
    if (this.recorder.state === "paused") this.recorder.resume();
  }

  /** 녹음기를 멈추고 마지막 조각까지 `onData`로 넘긴 뒤에 끝나요 */
  stop() {
    if (this.recorder.state === "inactive") return Promise.resolve();

    return new Promise<void>((resolve) => {
      this.recorder.addEventListener("stop", () => resolve(), { once: true });
      this.recorder.stop();
    });
  }

  close() {
    this.isClosed = true;
    stopTracks(this.stream);
    if (this.recorder.state !== "inactive") this.recorder.stop();
    void this.audioContext.close();
  }
}
