import { ThemeProvider } from "@emotion/react";
import { DialogProvider } from "@provider/context/dialogContext";
import { theme } from "@provider/themeProvider";
import { useRecordingStore } from "@store/recordingStore";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import MeetingAudioOption from ".";
import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";

class FakeTrack extends EventTarget {
  readyState: MediaStreamTrackState = "live";

  constructor(readonly kind: "audio" | "video") {
    super();
  }

  stop() {
    this.readyState = "ended";
  }
}

/** 공유 창에서 고른 탭. 탭 오디오 공유를 켰을 때만 소리 트랙이 있어요 */
const createSharedTab = ({ hasAudio = true } = {}) => {
  const audio = new FakeTrack("audio");
  const tracks = hasAudio
    ? [new FakeTrack("video"), audio]
    : [new FakeTrack("video")];
  const stream = {
    getTracks: () => tracks,
    getAudioTracks: () => tracks.filter((track) => track.kind === "audio"),
  } as unknown as MediaStream;

  return { stream, audio };
};

const useBrowser = (userAgent: string) =>
  vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

/** 독 마이크로 시작한 녹음처럼 마이크만 받아 녹음을 시작해 둬요 */
const renderDuringRecording = async () => {
  await act(async () => {
    await useRecordingStore.getState().connectMicrophone();
    useRecordingStore
      .getState()
      .startRecording({ workspaceId: 1, recordingId: 10 });
  });

  render(
    <ThemeProvider theme={theme}>
      <DialogProvider>
        <MeetingAudioOption />
      </DialogProvider>
    </ThemeProvider>,
  );
};

const queryOption = () =>
  screen.queryByRole("button", { name: MEETING_AUDIO_COPY.OPTION });

const openNotice = () => {
  fireEvent.click(queryOption()!);

  return screen.getByRole("dialog", { name: MEETING_AUDIO_COPY.NOTICE_TITLE });
};

const confirmNotice = async (notice: HTMLElement) => {
  await act(async () => {
    fireEvent.click(
      within(notice).getByRole("button", {
        name: MEETING_AUDIO_COPY.NOTICE_CONFIRM,
      }),
    );
  });
};

describe("MeetingAudioOption", () => {
  afterEach(() => {
    useRecordingStore.getState().discardRecording();
  });

  it("[STT-R49] Chrome 계열이 아니면 선택지 대신 안내만 보이고 마이크 녹음은 그대로다", async () => {
    useBrowser(SAFARI_UA);
    const getDisplayMedia = vi.spyOn(navigator.mediaDevices, "getDisplayMedia");

    await renderDuringRecording();

    expect(
      screen.getByText(MEETING_AUDIO_COPY.UNSUPPORTED),
    ).toBeInTheDocument();
    expect(queryOption()).not.toBeInTheDocument();
    expect(getDisplayMedia).not.toHaveBeenCalled();
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("[STT-R44, STT-R45] 선택지를 누르면 공유 창보다 먼저 녹음 고지와 이어폰 권장을 보여 주고, 확인해야 공유 창을 연다", async () => {
    useBrowser(CHROME_UA);
    const getDisplayMedia = vi
      .spyOn(navigator.mediaDevices, "getDisplayMedia")
      .mockResolvedValue(createSharedTab().stream);
    await renderDuringRecording();

    const notice = openNotice();

    expect(
      within(notice).getByText(MEETING_AUDIO_COPY.NOTICE_DESCRIPTION, {
        normalizer: (text) => text,
      }),
    ).toBeInTheDocument();
    expect(getDisplayMedia).not.toHaveBeenCalled();

    await confirmNotice(notice);

    expect(getDisplayMedia).toHaveBeenCalledOnce();
    // 함께 녹음하는 동안에는 선택지를 숨겨요
    expect(queryOption()).not.toBeInTheDocument();
    expect(useRecordingStore.getState().meetingAudioStatus).toBe("on");
  });

  it("[STT-R45] 고지에서 취소하면 공유 창을 열지 않는다", async () => {
    useBrowser(CHROME_UA);
    const getDisplayMedia = vi.spyOn(navigator.mediaDevices, "getDisplayMedia");
    await renderDuringRecording();

    const notice = openNotice();
    fireEvent.click(
      within(notice).getByRole("button", {
        name: MEETING_AUDIO_COPY.NOTICE_CANCEL,
      }),
    );

    expect(getDisplayMedia).not.toHaveBeenCalled();
    expect(useRecordingStore.getState().meetingAudioStatus).toBe("off");
  });

  it("[STT-R47] 공유 창을 닫으면 알림 없이 마이크만 녹음하고 선택지를 그대로 둔다", async () => {
    useBrowser(CHROME_UA);
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockRejectedValue(
      new DOMException("Permission denied", "NotAllowedError"),
    );
    await renderDuringRecording();

    await confirmNotice(openNotice());

    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    expect(queryOption()).toBeInTheDocument();
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("[STT-R47] 소리 없이 공유하면 마이크만 녹음하며 회의 소리를 받지 못했다고 알린다", async () => {
    useBrowser(CHROME_UA);
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockResolvedValue(
      createSharedTab({ hasAudio: false }).stream,
    );
    await renderDuringRecording();

    await confirmNotice(openNotice());

    expect(screen.getByRole("status")).toHaveTextContent(
      MEETING_AUDIO_COPY.NO_AUDIO,
    );
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("[STT-R48] 함께 녹음하다 브라우저 공유 중지를 누르면 녹음은 이어지고 회의 소리 녹음이 멈췄다고 알린다", async () => {
    useBrowser(CHROME_UA);
    const shared = createSharedTab();
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockResolvedValue(
      shared.stream,
    );
    await renderDuringRecording();
    await confirmNotice(openNotice());

    act(() => {
      shared.audio.stop();
      shared.audio.dispatchEvent(new Event("ended"));
    });

    expect(screen.getByRole("status")).toHaveTextContent(
      MEETING_AUDIO_COPY.STOPPED,
    );
    expect(queryOption()).toBeInTheDocument();
    expect(useRecordingStore.getState()).toMatchObject({
      status: "recording",
      isMicrophoneLost: false,
    });
  });
});
