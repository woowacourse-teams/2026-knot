import { expect, test, type Locator, type Page } from "@playwright/test";

import { TOAST_MESSAGES } from "@constants/toastMessages";

import { MEETING_AUDIO_COPY } from "../modules/features/recording/MeetingAudioOption/constants/meetingAudioCopy";

const HOME_PATH = "/workspace/1";
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";

/** 공유 창에서 사용자가 고르는 결과. 탭 오디오와 함께 공유, 오디오 없이 공유, 공유 창 닫기 */
type ShareChoice = "withAudio" | "noAudio" | "cancel";

/** 가짜 공유 창이 페이지에 남기는 값 */
type ShareWindow = Window & {
  __shareChoice: ShareChoice;
  __displayMediaCalls: number;
  __sharedAudioTrack?: MediaStreamTrack;
};

// 가짜 마이크로 권한 창 없이 녹음해요
test.use({
  permissions: ["microphone"],
  launchOptions: {
    args: [
      "--use-fake-device-for-media-stream",
      "--use-fake-ui-for-media-stream",
    ],
  },
});

/**
 * 공유 창은 사람이 골라야 열려 E2E가 고를 수 없어요. getDisplayMedia를 사용자가 고른 결과를 돌려주는 가짜로 바꾸고,
 * 회의 소리는 440Hz 소리를 내는 트랙으로 대신해요. 브라우저의 공유 중지는 그 트랙의 `ended`로 재현해요
 */
async function fakeSharePicker(page: Page, choice: ShareChoice) {
  await page.addInitScript((initialChoice) => {
    const shareWindow = window as unknown as ShareWindow;
    shareWindow.__shareChoice = initialChoice;
    shareWindow.__displayMediaCalls = 0;

    navigator.mediaDevices.getDisplayMedia = async () => {
      shareWindow.__displayMediaCalls += 1;
      if (shareWindow.__shareChoice === "cancel") {
        throw new DOMException("Permission denied", "NotAllowedError");
      }

      const [video] = document
        .createElement("canvas")
        .captureStream()
        .getVideoTracks();
      if (shareWindow.__shareChoice === "noAudio") {
        return new MediaStream([video]);
      }

      const context = new AudioContext();
      const tone = context.createOscillator();
      const output = context.createMediaStreamDestination();
      tone.connect(output);
      tone.start();
      const [audio] = output.stream.getAudioTracks();
      shareWindow.__sharedAudioTrack = audio;

      return new MediaStream([video, audio]);
    };
  }, choice);
}

// 현재 녹음 조회가 다른 탭의 녹음으로 답하면 독이 마이크 대신 칩을 보여 줘서, 조회만 닿지 않는 주소로 보내 실패시켜요.
// mock 워커가 먼저 받는 요청은 page.route가 잡지 못해요
async function failCurrentRecordingLookup(page: Page) {
  await page.addInitScript(() => {
    const open = XMLHttpRequest.prototype.open;

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: [boolean, string?, string?]
    ) {
      const target = String(url).includes("/recordings/current")
        ? "http://127.0.0.1:1/"
        : url;

      return open.call(this, method, target, ...rest);
    } as typeof XMLHttpRequest.prototype.open;
  });
}

/** 독 마이크로 녹음을 시작해 녹음 화면에 들어가요 */
async function startRecordingFromDock(
  page: Page,
  choice: ShareChoice = "withAudio",
) {
  await fakeSharePicker(page, choice);
  await failCurrentRecordingLookup(page);
  await page.goto(HOME_PATH);
  await page.getByRole("button", { name: "회의 녹음 시작" }).click();
  await expect(page).toHaveURL(/\/recording$/);
  await expect(recorderBar(page)).toContainText("녹음 중");
}

const recorderBar = (page: Page) =>
  page.getByRole("region", { name: "녹음 조작" });

const meetingAudioOption = (page: Page) =>
  page.getByRole("button", { name: MEETING_AUDIO_COPY.OPTION });

const meetingAudioReconnect = (page: Page) =>
  page.getByRole("button", { name: MEETING_AUDIO_COPY.RECONNECT });

const meetingAudioDialog = (page: Page) =>
  page.getByRole("dialog", { name: MEETING_AUDIO_COPY.DIALOG.TITLE });

/** 「화상 회의 상대방 목소리」 줄의 상태와 설명 */
const meetingSource = (page: Page) =>
  page
    .getByRole("status")
    .filter({ hasText: MEETING_AUDIO_COPY.MEETING_TITLE });

const displayMediaCalls = (page: Page) =>
  page.evaluate(() => (window as unknown as ShareWindow).__displayMediaCalls);

/** 「함께 녹음하기」를 눌러 연결 안내를 확인하고 공유 창을 열어요 */
async function shareMeetingAudio(page: Page) {
  await meetingAudioOption(page).click();
  await meetingAudioDialog(page)
    .getByRole("button", { name: MEETING_AUDIO_COPY.DIALOG.CONFIRM })
    .click();
}

/** 녹음한 시간이 흘러가는지 봐요. 녹음이 끝나거나 멈추지 않았다는 뜻이에요 */
async function expectRecordingContinues(page: Page) {
  const elapsed = recorderBar(page).getByLabel("녹음한 시간");
  const before = await elapsed.textContent();

  await expect(elapsed).not.toHaveText(before ?? "", { timeout: 3_000 });
  await expect(recorderBar(page)).toContainText("녹음 중");
  await expect(page).toHaveURL(/\/recording$/);
}

test.describe("녹음 중 회의 소리 함께 녹음", () => {
  test("[STT-R43] 독 마이크로 시작한 녹음은 공유 창을 열지 않고 마이크만 녹음한다", async ({
    page,
  }) => {
    await startRecordingFromDock(page);

    expect(await displayMediaCalls(page)).toBe(0);
    await expect(meetingAudioOption(page)).toBeVisible();
  });

  test("[STT-R44, STT-R45] 함께 녹음하기를 누르면 공유 창보다 먼저 연결 안내와 녹음 고지가 나오고, 연결되면 이어폰 토스트가 뜬다", async ({
    page,
  }) => {
    await startRecordingFromDock(page);

    await meetingAudioOption(page).click();

    await expect(meetingAudioDialog(page)).toContainText(
      MEETING_AUDIO_COPY.DIALOG.NOTICE,
    );
    expect(await displayMediaCalls(page)).toBe(0);

    await meetingAudioDialog(page)
      .getByRole("button", { name: MEETING_AUDIO_COPY.DIALOG.CONFIRM })
      .click();

    await expect.poll(() => displayMediaCalls(page)).toBe(1);
    await expect(meetingSource(page)).toContainText(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.on,
    );
    await expect(
      page.getByText(TOAST_MESSAGES.MEETING_AUDIO_CONNECTED.message),
    ).toBeVisible();
    await expect(meetingAudioOption(page)).toBeHidden();
    await expectRecordingContinues(page);
  });

  test("[STT-R47] 공유 창을 닫으면 녹음이 끝나지 않고 마이크만으로 이어진다", async ({
    page,
  }) => {
    await startRecordingFromDock(page, "cancel");

    await shareMeetingAudio(page);

    await expect.poll(() => displayMediaCalls(page)).toBe(1);
    await expect(meetingAudioOption(page)).toBeEnabled();
    await expect(meetingSource(page)).toContainText(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.off,
    );
    await expectRecordingContinues(page);
  });

  test("[STT-R47] 소리 트랙 없이 공유하면 마이크만으로 이어 가고 회의 소리를 받지 못했다고 알린다", async ({
    page,
  }) => {
    await startRecordingFromDock(page, "noAudio");

    await shareMeetingAudio(page);

    await expect(meetingSource(page)).toContainText(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.noAudio,
    );
    await expect(meetingAudioReconnect(page)).toBeVisible();
    await expectRecordingContinues(page);
  });

  test("[STT-R48] 녹음 중 브라우저 공유 중지를 누르면 녹음은 이어지고 멈췄다는 알림이 뜬다", async ({
    page,
  }) => {
    await startRecordingFromDock(page);
    await shareMeetingAudio(page);
    await expect(meetingAudioOption(page)).toBeHidden();

    await page.evaluate(() => {
      const track = (window as unknown as ShareWindow).__sharedAudioTrack!;
      track.stop();
      track.dispatchEvent(new Event("ended"));
    });

    await expect(meetingSource(page)).toContainText(
      MEETING_AUDIO_COPY.MEETING_DESCRIPTION.stopped,
    );
    await expect(meetingAudioReconnect(page)).toBeVisible();
    await expectRecordingContinues(page);
  });
});

test.describe("회의 소리를 받을 수 없는 브라우저", () => {
  test.use({ userAgent: SAFARI_UA });

  test("[STT-R49, STT-R29] Safari에서는 지원 안 함만 보이고 미지원 다이얼로그 없이 마이크 녹음은 그대로다", async ({
    page,
  }) => {
    await startRecordingFromDock(page);

    await expect(meetingSource(page)).toContainText(
      MEETING_AUDIO_COPY.STATUS.UNSUPPORTED,
    );
    await expect(meetingAudioOption(page)).toHaveCount(0);
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    expect(await displayMediaCalls(page)).toBe(0);
    await expectRecordingContinues(page);
  });
});

/** 생존 신호를 막았다 풀 수 있게 해요. 막으면 연결이 끊긴 것처럼 신호 요청만 닿지 못해요 */
async function blockableHeartbeat(page: Page) {
  await page.addInitScript(() => {
    const { open } = XMLHttpRequest.prototype;

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: [boolean, string?, string?]
    ) {
      const isBlocked =
        (window as unknown as HeartbeatWindow).__isHeartbeatBlocked &&
        String(url).includes("/heartbeat");

      return open.call(
        this,
        method,
        isBlocked ? "http://127.0.0.1:1/" : url,
        ...rest,
      );
    } as typeof XMLHttpRequest.prototype.open;
  });
}

type HeartbeatWindow = Window & { __isHeartbeatBlocked?: boolean };

/** 막거나 푼 뒤 다음 주기를 기다리지 않고 신호를 보내요. 연결이 돌아오면 신호를 바로 보내는 동작을 빌려요 */
async function setHeartbeatBlocked(page: Page, isBlocked: boolean) {
  await page.evaluate((blocked) => {
    (window as unknown as HeartbeatWindow).__isHeartbeatBlocked = blocked;
    window.dispatchEvent(new Event("online"));
  }, isBlocked);
}

const connectionToast = (page: Page) =>
  page.getByText(TOAST_MESSAGES.RECORDING_DISCONNECTED.message);

test.describe("연결이 끊긴 동안", () => {
  test("[STT-R53] 신호가 닿지 않아도 녹음이 이어지고, 끊김 토스트가 보였다가 연결이 돌아오면 닫힌다", async ({
    page,
  }) => {
    await blockableHeartbeat(page);
    await startRecordingFromDock(page);

    await setHeartbeatBlocked(page, true);

    await expect(connectionToast(page)).toBeVisible();
    await expectRecordingContinues(page);

    await setHeartbeatBlocked(page, false);

    await expect(connectionToast(page)).toBeHidden();
    await expectRecordingContinues(page);
  });

  test("[STT-R53] 끊김 토스트는 화면을 옮겨도 같은 자리에 남고, X로 닫으면 끊긴 동안 다시 뜨지 않는다", async ({
    page,
  }) => {
    await blockableHeartbeat(page);
    await startRecordingFromDock(page);
    await setHeartbeatBlocked(page, true);
    const toastList = page.getByTestId("toast-list");
    await expect(toastList).toContainText(
      TOAST_MESSAGES.RECORDING_DISCONNECTED.message,
    );

    await page.getByRole("button", { name: "홈", exact: true }).click();

    await expect(toastList).toContainText(
      TOAST_MESSAGES.RECORDING_DISCONNECTED.message,
    );

    await toastList.getByRole("button", { name: "닫기" }).click();

    await expect(connectionToast(page)).toBeHidden();

    await setHeartbeatBlocked(page, true);

    await expect(connectionToast(page)).toBeHidden();
  });
});

test.describe("화면 꺼짐 방지", () => {
  type WakeLockWindow = Window & {
    __wakeLock: { requested: number; released: number };
  };

  test("[STT-R13] 녹음 중에는 화면 잠금을 잡고, 일시정지하면 놓는다", async ({
    page,
  }) => {
    await page.addInitScript(() => {
      const wakeLock = { requested: 0, released: 0 };
      (window as unknown as WakeLockWindow).__wakeLock = wakeLock;

      Object.defineProperty(navigator, "wakeLock", {
        value: {
          request: async () => {
            wakeLock.requested += 1;

            return {
              release: async () => {
                wakeLock.released += 1;
              },
            };
          },
        },
      });
    });
    await startRecordingFromDock(page);
    const wakeLock = () =>
      page.evaluate(() => (window as unknown as WakeLockWindow).__wakeLock);

    await expect.poll(wakeLock).toMatchObject({ requested: 1, released: 0 });

    await recorderBar(page).getByRole("button", { name: "일시정지" }).click();

    await expect.poll(wakeLock).toMatchObject({ released: 1 });
  });
});

test.describe("새로고침 뒤 이어서 녹음", () => {
  /** 녹음을 시작한 뒤 새로고침해요. 서버는 녹음을 끝내지 않아, 새로고침 뒤 현재 녹음 조회는 녹음 중이라고 답해요 */
  async function reloadWhileRecording(page: Page) {
    await page.goto(HOME_PATH);
    await page.getByRole("button", { name: "회의 녹음 시작" }).click();
    await expect(recorderBar(page)).toContainText("녹음 중");
    await page.evaluate(() =>
      localStorage.setItem(
        "knot.mock.scenario",
        JSON.stringify({ currentRecording: "recording" }),
      ),
    );
    page.on("dialog", (dialog) => void dialog.accept());
    await page.reload();
  }

  test("[STT-R26] 새로고침한 같은 탭에 「이어서 녹음」이 보이고, 누르면 같은 녹음이 이어진다", async ({
    page,
  }) => {
    await reloadWhileRecording(page);

    await expect(recorderBar(page)).toContainText("녹음 멈춤");
    await recorderBar(page)
      .getByRole("button", { name: "이어서 녹음" })
      .click();

    await expectRecordingContinues(page);
  });

  test("[STT-R26] 새로고침 뒤 홈에서도 녹음 화면으로 돌아가 이어 갈 수 있다", async ({
    page,
  }) => {
    await reloadWhileRecording(page);
    await page.getByRole("button", { name: "홈", exact: true }).click();

    await page.getByRole("button", { name: "녹음 화면으로", exact: true }).click();

    await expect(
      recorderBar(page).getByRole("button", { name: "이어서 녹음" }),
    ).toBeVisible();
  });
});

test.describe("다른 탭에서 녹음 중", () => {
  const OTHER_TAB_HOME_PATH = `${HOME_PATH}?mock.currentRecording=recording`;
  const endButton = (scope: Page | Locator) =>
    scope.getByRole("button", { name: "녹음 끝내기" });

  test("[STT-R28, STT-R24] 다른 탭 홈 맨 위 줄에 상태와 안내, 비활성 「녹음 화면으로」 하나가 있다", async ({
    page,
  }) => {
    await page.goto(OTHER_TAB_HOME_PATH);
    const recordingCard = page.getByRole("region", { name: "녹음 기록" });

    await expect(
      page.getByText("녹음 중인 탭에서 일시정지·이어서 녹음을 할 수 있어요"),
    ).toBeVisible();
    await expect(
      recordingCard.getByRole("button", { name: "녹음 화면으로", exact: true }),
    ).toBeDisabled();
    await expect(endButton(recordingCard)).toHaveCount(0);
  });

  test("[STT-R28] 다른 탭의 독에 시간 칩과 끝내기 버튼이 있고, 마이크와 일시정지 버튼은 없다", async ({
    page,
  }) => {
    await page.goto(OTHER_TAB_HOME_PATH);

    await expect(endButton(page)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "회의 녹음 시작" }),
    ).toBeHidden();
    await expect(page.getByRole("button", { name: "일시정지" })).toBeHidden();
    await expect(
      page.getByRole("button", { name: "이어서 녹음" }),
    ).toBeHidden();
  });

  test("[STT-R28] 독의 끝내기 버튼은 확인 창을 거치고, 취소하면 끝나지 않으며 확정하면 본문 없이 끝낸다", async ({
    page,
  }) => {
    await page.goto(OTHER_TAB_HOME_PATH);
    const endRequests: (string | null)[] = [];
    page.on("request", (request) => {
      if (request.url().endsWith("/end")) {
        endRequests.push(request.postData());
      }
    });
    const dialog = page.getByRole("dialog", { name: "녹음을 끝낼까요?" });

    await endButton(page).click();
    await dialog.getByRole("button", { name: "계속 녹음" }).click();
    expect(endRequests).toEqual([]);

    await endButton(page).click();
    await dialog.getByRole("button", { name: "녹음 끝내기" }).click();

    await expect.poll(() => endRequests).toEqual([null]);
  });

  test("[STT-R26, STT-R28] 증명이 없는 탭은 녹음 화면 주소로 들어가도 홈으로 간다", async ({
    page,
  }) => {
    await page.goto(`${HOME_PATH}/recording?mock.currentRecording=recording`);

    await expect(page).toHaveURL(new RegExp(`${HOME_PATH}(\\?.*)?$`));
  });
});
