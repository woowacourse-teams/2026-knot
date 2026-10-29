import { expect, test, type Page } from "@playwright/test";

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
  // 앞에 숨긴 「녹음한 시간」 이름과 시간이 함께 든 줄이에요
  const elapsed = recorderBar(page).getByText(/녹음한 시간 \d{2}:\d{2}/);
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
