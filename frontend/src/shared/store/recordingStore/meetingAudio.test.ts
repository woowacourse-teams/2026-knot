import { afterEach, describe, expect, it, vi } from "vitest";

import { useRecordingStore } from ".";
import { isMeetingAudioSupported } from "./meetingAudio";
import type { PcmCollectorParams } from "./pcmCollector";

const { FakePcmCollector } = vi.hoisted(() => {
  /** 실제 AudioWorklet 대신 쓰는 수집기. 섞은 소리와 `lose`(마이크 트랙 끝남)만 흉내 내요 */
  class FakePcmCollector {
    static instances: FakePcmCollector[] = [];

    isLive = true;
    addedStreams: MediaStream[] = [];

    private constructor(private readonly params: PcmCollectorParams) {}

    static async create(params: PcmCollectorParams) {
      const collector = new FakePcmCollector(params);
      FakePcmCollector.instances.push(collector);

      return collector;
    }

    start() {}

    async flush() {}

    close() {}

    addAudio(stream: MediaStream) {
      this.addedStreams.push(stream);
    }

    lose() {
      this.isLive = false;
      this.params.onLost();
    }
  }

  return { FakePcmCollector };
});

vi.mock("./pcmCollector", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./pcmCollector")>()),
  default: FakePcmCollector,
}));

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
const EDGE_UA = `${CHROME_UA} Edg/155.0.0.0`;
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";
const FIREFOX_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:150.0) Gecko/20100101 Firefox/150.0";

class FakeTrack extends EventTarget {
  readyState: MediaStreamTrackState = "live";

  constructor(readonly kind: "audio" | "video") {
    super();
  }

  stop() {
    this.readyState = "ended";
  }
}

/** 공유 창에서 고른 탭·화면. 화면 트랙은 늘 있고, 오디오 공유를 켰을 때만 소리 트랙이 있어요 */
const createSharedScreen = ({ hasAudio = true } = {}) => {
  const video = new FakeTrack("video");
  const audio = new FakeTrack("audio");
  const tracks = hasAudio ? [video, audio] : [video];
  const stream = {
    getTracks: () => tracks,
    getAudioTracks: () => tracks.filter((track) => track.kind === "audio"),
  } as unknown as MediaStream;

  return { stream, audio, video };
};

const shareScreen = (shared: ReturnType<typeof createSharedScreen>) =>
  vi
    .spyOn(navigator.mediaDevices, "getDisplayMedia")
    .mockResolvedValue(shared.stream);

const startRecording = async ({ isPcm = false } = {}) => {
  const store = useRecordingStore.getState();
  await store.connectMicrophone({
    audioMode: isPcm ? "PCM16_CHUNKS_V1" : "FINAL_WEBM_V1",
  });
  store.startRecording({
    workspaceId: 1,
    recordingId: 10,
    chunkRequests: isPcm
      ? {
          reserve: vi.fn(),
          upload: vi.fn(),
          complete: vi.fn(),
        }
      : undefined,
  });
};

const getState = () => useRecordingStore.getState();

describe("회의 소리를 받을 수 있는 브라우저 판정", () => {
  it.each([
    ["Chrome", CHROME_UA],
    ["Edge", EDGE_UA],
  ])("%s는 공유 창이 있으면 받을 수 있다", (_, userAgent) => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

    expect(isMeetingAudioSupported()).toBe(true);
  });

  it.each([
    ["Safari", SAFARI_UA],
    ["Firefox", FIREFOX_UA],
  ])("%s는 공유 창이 있어도 회의 소리를 받을 수 없다", (_, userAgent) => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

    expect(isMeetingAudioSupported()).toBe(false);
  });

  it("공유 창이 없는 Chrome(휴대폰)은 받을 수 없다", () => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(CHROME_UA);
    const { getDisplayMedia } = navigator.mediaDevices;
    Object.assign(navigator.mediaDevices, { getDisplayMedia: undefined });

    try {
      expect(isMeetingAudioSupported()).toBe(false);
    } finally {
      Object.assign(navigator.mediaDevices, { getDisplayMedia });
    }
  });
});

describe("녹음 중 회의 소리 함께 녹음", () => {
  afterEach(() => {
    getState().discardRecording();
    FakePcmCollector.instances.length = 0;
  });

  it("녹음기는 마이크가 아니라 섞은 소리를 녹음해 회의 소리를 나중에 더할 수 있다", async () => {
    const getUserMedia = vi.spyOn(navigator.mediaDevices, "getUserMedia");
    const recorder = vi.spyOn(window, "MediaRecorder");

    await startRecording();

    const microphone = await getUserMedia.mock.results[0].value;
    expect(recorder).toHaveBeenCalledOnce();
    expect(recorder.mock.calls[0][0]).not.toBe(microphone);
  });

  it("공유한 회의 소리를 녹음기와 PCM 수집기에 함께 섞고 같은 녹음을 이어 간다", async () => {
    const sourceOf = vi.spyOn(
      AudioContext.prototype,
      "createMediaStreamSource",
    );
    const shared = createSharedScreen();
    shareScreen(shared);
    await startRecording({ isPcm: true });

    expect(await getState().connectMeetingAudio()).toBe(true);
    expect(sourceOf).toHaveBeenCalledWith(shared.stream);
    expect(FakePcmCollector.instances[0].addedStreams).toEqual([shared.stream]);
    expect(getState()).toMatchObject({
      status: "recording",
      session: { recordingId: 10, audioMode: "PCM16_CHUNKS_V1" },
      meetingAudioStatus: "on",
    });
  });

  it("브라우저 공유 중지로 소리 트랙이 끝나면 녹음을 멈추지 않고 마이크만으로 이어 가며 알린다", async () => {
    const shared = createSharedScreen();
    shareScreen(shared);
    await startRecording({ isPcm: true });
    await getState().connectMeetingAudio();

    shared.audio.dispatchEvent(new Event("ended"));

    expect(getState()).toMatchObject({
      status: "recording",
      isMicrophoneLost: false,
      meetingAudioStatus: "stopped",
    });
    // 공유가 남지 않게 화면 트랙도 꺼요
    expect(shared.video.readyState).toBe("ended");
  });

  it("소리 없이 공유하면 공유를 끄고 마이크만으로 이어 가며 알린다", async () => {
    const shared = createSharedScreen({ hasAudio: false });
    shareScreen(shared);
    await startRecording({ isPcm: true });

    expect(await getState().connectMeetingAudio()).toBe(false);
    expect(shared.video.readyState).toBe("ended");
    expect(FakePcmCollector.instances[0].addedStreams).toEqual([]);
    expect(getState()).toMatchObject({
      status: "recording",
      meetingAudioStatus: "noAudio",
    });
  });

  it("공유 창을 닫으면 알리지 않고 마이크만으로 이어 간다", async () => {
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockRejectedValue(
      new DOMException("Permission denied", "NotAllowedError"),
    );
    await startRecording();

    expect(await getState().connectMeetingAudio()).toBe(false);
    expect(getState()).toMatchObject({
      status: "recording",
      meetingAudioStatus: "off",
    });
  });

  it("마이크가 끊겨 다시 받으면 새 수집기에도 회의 소리를 다시 섞는다", async () => {
    const shared = createSharedScreen();
    shareScreen(shared);
    await startRecording({ isPcm: true });
    await getState().connectMeetingAudio();

    FakePcmCollector.instances[0].lose();
    await getState().resumeRecording();

    expect(FakePcmCollector.instances[1].addedStreams).toEqual([shared.stream]);
    expect(getState()).toMatchObject({
      status: "recording",
      meetingAudioStatus: "on",
    });
  });

  it("녹음을 끝내면 공유도 끈다", async () => {
    const shared = createSharedScreen();
    shareScreen(shared);
    await startRecording();
    await getState().connectMeetingAudio();

    await getState().stopRecording();

    expect(shared.audio.readyState).toBe("ended");
    expect(shared.video.readyState).toBe("ended");
  });

  it("공유 창을 연 사이 녹음을 버리면 받은 공유를 바로 끈다", async () => {
    const shared = createSharedScreen();
    let pickScreen = () => {};
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockReturnValue(
      new Promise((resolve) => {
        pickScreen = () => resolve(shared.stream);
      }),
    );
    await startRecording();
    const connecting = getState().connectMeetingAudio();

    getState().discardRecording();
    pickScreen();
    await connecting;

    expect(shared.audio.readyState).toBe("ended");
    expect(getState().meetingAudioStatus).toBe("off");
  });
});
