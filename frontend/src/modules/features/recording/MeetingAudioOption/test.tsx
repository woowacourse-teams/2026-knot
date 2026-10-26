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

const ANDROID_CHROME_UA =
  "Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

/** 화상 회의 줄의 상태와 설명. 바뀌면 화면 낭독기가 읽어요 */
const meetingSource = () => screen.getByRole("status");

const queryButton = (name: string) => screen.queryByRole("button", { name });

const openDialog = (name: string = MEETING_AUDIO_COPY.OPTION) => {
  fireEvent.click(queryButton(name)!);

  return screen.getByRole("dialog", { name: MEETING_AUDIO_COPY.DIALOG.TITLE });
};

const confirmDialog = async (dialog: HTMLElement) => {
  await act(async () => {
    fireEvent.click(
      within(dialog).getByRole("button", {
        name: MEETING_AUDIO_COPY.DIALOG.CONFIRM,
      }),
    );
  });
};

describe("MeetingAudioOption", () => {
  afterEach(() => {
    useRecordingStore.getState().discardRecording();
  });

  it("[STT-R43] 마이크만 녹음하는 동안 화상 회의 줄에 함께 녹음하기가 보인다", async () => {
    useBrowser(CHROME_UA);

    await renderDuringRecording();

    expect(meetingSource()).toHaveTextContent(MEETING_AUDIO_COPY.MEETING_TITLE);
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.off,
    );
    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).toBeInTheDocument();
  });

  it("[STT-R49] Chrome 계열이 아닌 컴퓨터 브라우저면 지원 안 함만 보이고 마이크 녹음은 그대로다", async () => {
    useBrowser(SAFARI_UA);
    const getDisplayMedia = vi.spyOn(navigator.mediaDevices, "getDisplayMedia");

    await renderDuringRecording();

    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.STATUS.UNSUPPORTED,
    );
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.unsupported,
    );
    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).not.toBeInTheDocument();
    expect(getDisplayMedia).not.toHaveBeenCalled();
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("[STT-R49] 휴대폰에서는 Chrome이어도 컴퓨터에서만 녹음할 수 있다고 알린다", async () => {
    useBrowser(ANDROID_CHROME_UA);

    await renderDuringRecording();

    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.mobile,
    );
    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).not.toBeInTheDocument();
  });

  it("[STT-R44, STT-R45] 함께 녹음하기를 누르면 공유 창보다 먼저 연결 안내와 녹음 고지를 보여 주고, 공유할 화면 고르기를 눌러야 공유 창을 연다", async () => {
    useBrowser(CHROME_UA);
    const getDisplayMedia = vi
      .spyOn(navigator.mediaDevices, "getDisplayMedia")
      .mockResolvedValue(createSharedTab().stream);
    await renderDuringRecording();

    const dialog = openDialog();

    expect(
      within(dialog).getByText(MEETING_AUDIO_COPY.DIALOG.NOTICE),
    ).toBeInTheDocument();
    expect(getDisplayMedia).not.toHaveBeenCalled();

    await confirmDialog(dialog);

    expect(getDisplayMedia).toHaveBeenCalledOnce();
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.STATUS.RECORDING,
    );
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.on,
    );
    // 함께 녹음하는 동안에는 버튼을 숨겨요
    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).not.toBeInTheDocument();
    expect(useRecordingStore.getState().meetingAudioStatus).toBe("on");
  });

  it("[STT-R45] 연결 안내는 브라우저에서가 기본이고, 설치한 앱에서를 고르면 전체 화면과 시스템 오디오 공유 단계로 바뀐다", async () => {
    useBrowser(CHROME_UA);
    await renderDuringRecording();

    const dialog = openDialog();
    const { browserTab, meetingApp } = MEETING_AUDIO_COPY.LOCATIONS;

    expect(
      within(dialog).getByRole("radio", { name: new RegExp(browserTab.LABEL) }),
    ).toBeChecked();
    expect(within(dialog).getAllByRole("listitem")[0]).toHaveTextContent(
      browserTab.STEPS[0],
    );

    fireEvent.click(
      within(dialog).getByRole("radio", { name: new RegExp(meetingApp.LABEL) }),
    );

    for (const step of meetingApp.STEPS) {
      expect(within(dialog).getByText(step)).toBeInTheDocument();
    }
    expect(within(dialog).queryByText(browserTab.STEPS[0])).toBeNull();
  });

  it("[STT-R45] 연결 안내에서 취소하면 공유 창을 열지 않는다", async () => {
    useBrowser(CHROME_UA);
    const getDisplayMedia = vi.spyOn(navigator.mediaDevices, "getDisplayMedia");
    await renderDuringRecording();

    const dialog = openDialog();
    fireEvent.click(
      within(dialog).getByRole("button", {
        name: MEETING_AUDIO_COPY.DIALOG.CANCEL,
      }),
    );

    expect(getDisplayMedia).not.toHaveBeenCalled();
    expect(useRecordingStore.getState().meetingAudioStatus).toBe("off");
  });

  it("[STT-R44] 공유 창에서 고르는 동안에는 버튼이 로딩으로 바뀌고 회의 화면을 고르라고 안내한다", async () => {
    useBrowser(CHROME_UA);
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockReturnValue(
      new Promise(() => {}),
    );
    await renderDuringRecording();

    await confirmDialog(openDialog());

    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).toHaveAttribute(
      "aria-busy",
      "true",
    );
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.connecting,
    );
  });

  it("[STT-R47] 공유 창을 닫으면 알림 없이 마이크만 녹음하고 처음 모습으로 돌아간다", async () => {
    useBrowser(CHROME_UA);
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockRejectedValue(
      new DOMException("Permission denied", "NotAllowedError"),
    );
    await renderDuringRecording();

    await confirmDialog(openDialog());

    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.off,
    );
    expect(queryButton(MEETING_AUDIO_COPY.OPTION)).not.toHaveAttribute(
      "aria-busy",
      "true",
    );
    expect(useRecordingStore.getState().status).toBe("recording");
  });

  it("[STT-R47] 소리 없이 공유하면 마이크만 녹음하며 소리가 없다고 알리고 다시 연결하기를 둔다", async () => {
    useBrowser(CHROME_UA);
    const getDisplayMedia = vi
      .spyOn(navigator.mediaDevices, "getDisplayMedia")
      .mockResolvedValue(createSharedTab({ hasAudio: false }).stream);
    await renderDuringRecording();

    await confirmDialog(openDialog());

    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.STATUS.NO_AUDIO,
    );
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.noAudio,
    );
    expect(useRecordingStore.getState().status).toBe("recording");

    getDisplayMedia.mockResolvedValue(createSharedTab().stream);
    await confirmDialog(openDialog(MEETING_AUDIO_COPY.RECONNECT));

    expect(useRecordingStore.getState().meetingAudioStatus).toBe("on");
  });

  it("[STT-R48] 함께 녹음하다 브라우저 공유 중지를 누르면 녹음은 이어지고 공유가 멈췄다고 알린다", async () => {
    useBrowser(CHROME_UA);
    const shared = createSharedTab();
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockResolvedValue(
      shared.stream,
    );
    await renderDuringRecording();
    await confirmDialog(openDialog());

    act(() => {
      shared.audio.stop();
      shared.audio.dispatchEvent(new Event("ended"));
    });

    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.STATUS.STOPPED,
    );
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.stopped,
    );
    expect(queryButton(MEETING_AUDIO_COPY.RECONNECT)).toBeInTheDocument();
    expect(useRecordingStore.getState()).toMatchObject({
      status: "recording",
      isMicrophoneLost: false,
    });
  });

  it("함께 녹음하다 일시정지하면 화상 회의 줄도 일시정지로 보인다", async () => {
    useBrowser(CHROME_UA);
    vi.spyOn(navigator.mediaDevices, "getDisplayMedia").mockResolvedValue(
      createSharedTab().stream,
    );
    await renderDuringRecording();
    await confirmDialog(openDialog());

    await act(async () => {
      await useRecordingStore.getState().pauseRecording();
    });

    expect(meetingSource()).toHaveTextContent(MEETING_AUDIO_COPY.STATUS.PAUSED);
    expect(meetingSource()).toHaveTextContent(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.paused,
    );
  });
});
