import { captureCaughtError } from "@provider/sentry";
import { markRecordingStart } from "@utils/recordingStartTrace";

import MicrophoneConnection, {
  type MicrophoneConnectionParams,
  stopTracks,
} from "./microphoneConnection";

type MicrophoneParams = Omit<MicrophoneConnectionParams, "stream">;

/**
 * 녹음에 쓰는 마이크. 권한 받기·연결 교체·끄기를 한곳에서 맡아요.
 *
 * - 여러 번 연결해도 권한 창은 한 번만 띄우고 같은 결과를 함께 기다려요
 * - 권한을 기다리는 사이 끊었다면 받은 마이크를 바로 꺼요
 * - 끊긴 뒤 다시 연결하면 이전 연결을 정리하고 새로 붙여요
 * - 연결만으로는 녹음하지 않고, `start()`를 불러야 모으기 시작해요
 */
export default class Microphone {
  private readonly onData: MicrophoneParams["onData"];
  private readonly onLost: MicrophoneParams["onLost"];
  private connection: MicrophoneConnection | null = null;

  /** 끊을 때마다 1씩 늘어요. 권한을 받는 사이 끊겼는지 알아보는 데 써요 */
  private generation = 0;

  private pendingConnect: Promise<MicrophoneConnection | null> | null = null;

  /** 마지막으로 연결하지 못한 오류의 이름(`NotAllowedError` 등). 연결했거나 기다리는 사이 끊었으면 `null`이에요 */
  errorName: string | null = null;

  constructor({ onData, onLost }: MicrophoneParams) {
    this.onData = onData;
    this.onLost = onLost;
  }

  /** 마이크를 받아 연결하고 그 연결(분석기·마이크)을 돌려줘요. 받지 못하면 `null`이에요 */
  connect() {
    this.pendingConnect ??= this.open().finally(() => {
      this.pendingConnect = null;
    });

    return this.pendingConnect;
  }

  start() {
    this.connection?.start();
  }

  isLive() {
    return this.connection?.isLive() ?? false;
  }

  pause() {
    this.connection?.pause();
  }

  resume() {
    this.connection?.resume();
  }

  /** 마지막 조각까지 모은 뒤 마이크를 꺼요 */
  async stop() {
    await this.connection?.stop();
    this.disconnect();
  }

  disconnect() {
    this.generation += 1;
    this.connection?.close();
    this.connection = null;
  }

  private async open() {
    const requestedGeneration = this.generation;
    this.errorName = null;

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      markRecordingStart("mic-granted");

      if (requestedGeneration !== this.generation) {
        stopTracks(stream);
        return null;
      }

      this.connection?.close();
      this.connection = new MicrophoneConnection({
        stream,
        onData: this.onData,
        onLost: this.onLost,
      });
      markRecordingStart("audio-ready");

      return this.connection;
    } catch (error) {
      // 받지 못하면 연결하지 않고 `null`만 돌려줘요. 권한 거부와 장치 없음은 Sentry 보고 기준에서 빠져요
      captureCaughtError({ error, feature: "recording" });
      // 기다리는 사이 끊었으면 받은 마이크를 버리듯 이번 실패의 이유도 남기지 않아요
      if (requestedGeneration !== this.generation) return null;

      // getUserMedia는 DOMException으로 거절해요
      this.errorName =
        error instanceof DOMException || error instanceof Error
          ? error.name
          : null;

      return null;
    }
  }
}
