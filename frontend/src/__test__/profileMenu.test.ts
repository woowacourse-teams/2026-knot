import { TOAST_MESSAGES } from "@constants/toastMessages";
import { expect, test, type Page } from "@playwright/test";

/**
 * 상단바 프로필 메뉴와 로그아웃 E2E(AUTH-R17~R22).
 *
 * 프로필 사진으로 메뉴를 열고 로그아웃해 로그인 화면으로 가는 플로우와, 녹음 중이거나 다른 곳에서 녹음 중일 때 묻는 창을 확인해요.
 * 로그인 상태는 dev 서버 mock의 쿠키로 흉내 내고, 로그아웃 mock이 그 쿠키를 지워요.
 * 다른 기기의 로그인과 녹음, 문서 정리 결과처럼 실제 서버가 있어야 보이는 것은 다루지 않아요.
 */

const HOME_PATH = "/workspace/1";
const LOGIN_PATH = /\/login$/;
const LOGOUT_REQUEST_PATH = "/auth/logout";
const REFRESH_REQUEST_PATH = "/auth/refresh";
const END_REQUEST_PATH = "/end";
const CHUNK_REQUEST_PATH = "/audio-chunks";
const CURRENT_RECORDING_REQUEST_PATH = "/recordings/current";
const END_DIALOG_NAME = "녹음을 끝내고 로그아웃할까요?";
const ELSEWHERE_DIALOG_NAME = "다른 곳에서 녹음 중이에요";
const FEEDBACK_DRAFT_KEY = "knot.feedback.draft";
const LAST_CONVERSATION_KEY = "knot.lastConversation.1";
const RETURN_PATH_KEY = "knot.auth.returnPath";

/** 시안의 메뉴 크기(px). 기본과 처리 중이 같아요 */
const MENU_SIZE = { width: 240, height: 52 };

/** 가짜 요청 조절기가 페이지에 남기는 값 */
type RequestControlWindow = Window & {
  __heldRequestCount: () => number;
  __releaseHeldRequests: () => void;
  __restoreFailedRequests: () => void;
};

type HeldRequest = XMLHttpRequest & { __isHeld?: boolean };

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

const avatar = (page: Page) => page.getByRole("button", { name: "내 프로필" });
const profileMenu = (page: Page) =>
  page.getByRole("navigation", { name: "프로필 메뉴" });
const logoutItem = (page: Page) => profileMenu(page).getByRole("button");
const endDialog = (page: Page) =>
  page.getByRole("dialog", { name: END_DIALOG_NAME });
const elsewhereDialog = (page: Page) =>
  page.getByRole("dialog", { name: ELSEWHERE_DIALOG_NAME });
const sessionExpiredToast = (page: Page) =>
  page.getByText(TOAST_MESSAGES.SESSION_EXPIRED.message);

const openHome = async (page: Page, query = "") => {
  await page.goto(`${HOME_PATH}${query}`);
  await expect(avatar(page)).toBeVisible();
};

const pressLogout = async (page: Page) => {
  await avatar(page).click();
  await logoutItem(page).click();
};

/** 처리 중에도 닫는 세 방법을 모두 써 봐요 */
const tryToCloseMenu = async (page: Page) => {
  await avatar(page).click();
  await page.mouse.click(400, 400);
  await page.keyboard.press("Escape");
};

/** 메뉴는 세로로 펼쳐지며 열려서, 다 펼쳐진 뒤의 크기를 시안과 견줘요 */
const expectMenuOpened = (page: Page) =>
  expect.poll(() => profileMenu(page).boundingBox()).toMatchObject(MENU_SIZE);

const expectProcessing = (page: Page) =>
  expect(logoutItem(page)).toHaveAttribute("aria-disabled", "true");

// mock 워커가 먼저 받는 요청은 page.route가 잡지 못해, 요청을 보내는 쪽(XHR)에서 조절해요

/** 주소에 `urlPart`가 든 요청을 `__releaseHeldRequests`를 부를 때까지 보내지 않아요 */
const holdRequests = (page: Page, urlPart: string) =>
  page.addInitScript((part) => {
    const controlWindow = window as unknown as RequestControlWindow;
    const heldSends: (() => void)[] = [];
    let isHolding = true;
    let heldCount = 0;
    const { open, send } = XMLHttpRequest.prototype;

    controlWindow.__heldRequestCount = () => heldCount;
    controlWindow.__releaseHeldRequests = () => {
      isHolding = false;
      heldSends.splice(0).forEach((sendHeld) => sendHeld());
    };

    XMLHttpRequest.prototype.open = function (
      this: HeldRequest,
      method: string,
      url: string | URL,
      ...rest: [boolean, string?, string?]
    ) {
      this.__isHeld = String(url).includes(part);

      return open.call(this, method, url, ...rest);
    } as typeof XMLHttpRequest.prototype.open;

    XMLHttpRequest.prototype.send = function (
      this: HeldRequest,
      body?: Document | XMLHttpRequestBodyInit | null,
    ) {
      if (isHolding && this.__isHeld) {
        heldCount += 1;
        heldSends.push(() => send.call(this, body));

        return;
      }

      send.call(this, body);
    };
  }, urlPart);

/** 주소에 `urlPart`가 든 요청을 `__restoreFailedRequests`를 부를 때까지 닿지 않는 주소로 보내 실패시켜요 */
const failRequests = (page: Page, urlPart: string) =>
  page.addInitScript((part) => {
    const controlWindow = window as unknown as RequestControlWindow;
    let isFailing = true;
    const { open } = XMLHttpRequest.prototype;

    controlWindow.__restoreFailedRequests = () => {
      isFailing = false;
    };

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: [boolean, string?, string?]
    ) {
      const target =
        isFailing && String(url).includes(part) ? "http://127.0.0.1:1/" : url;

      return open.call(this, method, target, ...rest);
    } as typeof XMLHttpRequest.prototype.open;
  }, urlPart);

const releaseHeldRequests = (page: Page) =>
  page.evaluate(() =>
    (window as unknown as RequestControlWindow).__releaseHeldRequests(),
  );

const restoreFailedRequests = (page: Page) =>
  page.evaluate(() =>
    (window as unknown as RequestControlWindow).__restoreFailedRequests(),
  );

/** 나간 요청의 주소를 차례대로 모아요 */
const collectRequestUrls = (page: Page) => {
  const urls: string[] = [];

  page.on("request", (request) => urls.push(request.url()));

  return urls;
};

test.describe("프로필 메뉴", () => {
  test("[AUTH-R17] 프로필 사진을 누르면 프로필 메뉴가 열리고 「로그아웃」 하나만 보임", async ({
    page,
  }) => {
    await openHome(page);

    await avatar(page).click();

    await expect(profileMenu(page).getByRole("button")).toHaveCount(1);
    await expect(logoutItem(page)).toHaveText("로그아웃");
    await expect(logoutItem(page)).toBeFocused();

    await expectMenuOpened(page);

    const menu = await profileMenu(page).boundingBox();
    const trigger = await avatar(page).boundingBox();

    expect(menu!.x + menu!.width).toBeCloseTo(trigger!.x + trigger!.width);
    expect(menu!.y).toBeCloseTo(trigger!.y + trigger!.height + 8);
  });

  test("[AUTH-R17] 프로필 사진을 다시 누르거나 바깥을 누르거나 ESC를 누르면 메뉴가 닫힘", async ({
    page,
  }) => {
    await openHome(page);

    await avatar(page).click();
    await expect(profileMenu(page)).toBeVisible();
    await avatar(page).click();
    await expect(profileMenu(page)).toBeHidden();

    await avatar(page).click();
    await expect(profileMenu(page)).toBeVisible();
    await page.mouse.click(400, 400);
    await expect(profileMenu(page)).toBeHidden();

    await avatar(page).click();
    await expect(profileMenu(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(profileMenu(page)).toBeHidden();
  });

  test("[AUTH-R17] 처리 중에는 프로필 사진, 바깥, ESC로 메뉴가 닫히지 않음", async ({
    page,
  }) => {
    await holdRequests(page, LOGOUT_REQUEST_PATH);
    await openHome(page);
    await pressLogout(page);
    await expectProcessing(page);

    await tryToCloseMenu(page);

    await expect(profileMenu(page)).toBeVisible();
    await expectProcessing(page);
  });

  test("[AUTH-R17] 처리 중에 창 크기가 바뀌어도 메뉴가 닫히지 않고 프로필 사진 곁에 남음", async ({
    page,
  }) => {
    await holdRequests(page, LOGOUT_REQUEST_PATH);
    await openHome(page);
    await pressLogout(page);
    await expectProcessing(page);

    await page.setViewportSize({ width: 900, height: 700 });

    await expect(profileMenu(page)).toBeVisible();
    await expect
      .poll(async () => {
        const menu = await profileMenu(page).boundingBox();
        const trigger = await avatar(page).boundingBox();

        return Math.round(
          menu!.x + menu!.width - (trigger!.x + trigger!.width),
        );
      })
      .toBe(0);
  });

  test.describe("휴대폰", () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test("[AUTH-R17] 메뉴가 프로필 사진 오른쪽 끝에 맞춰 화면 안에 열림", async ({
      page,
    }) => {
      await openHome(page);

      await avatar(page).click();
      await expectMenuOpened(page);

      const menu = await profileMenu(page).boundingBox();

      expect(menu!.x + menu!.width).toBeCloseTo(374);
    });
  });
});

test.describe("로그아웃", () => {
  test("[AUTH-R18] 「로그아웃」을 누르면 확인 없이 로그아웃되고 로그인 화면으로 감", async ({
    page,
  }) => {
    await openHome(page);

    await pressLogout(page);

    await expect(page).toHaveURL(LOGIN_PATH);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("[AUTH-R18] 로그아웃 뒤 뒤로 가기를 눌러도 앞 계정의 화면이 보이지 않음", async ({
    page,
  }) => {
    await openHome(page);
    await page.getByRole("button", { name: "문서" }).click();
    await expect(page).toHaveURL(/\/documents/);

    await pressLogout(page);
    await expect(page).toHaveURL(LOGIN_PATH);

    await page.goBack();

    await expect(page).toHaveURL(LOGIN_PATH);
    await expect(avatar(page)).toBeHidden();
  });

  test("[AUTH-R18] 같은 브라우저의 다른 탭은 다음 요청에서 로그인 화면으로 가고 로그인 만료 토스트가 뜸", async ({
    page,
    context,
  }) => {
    await openHome(page);
    const otherTab = await context.newPage();
    await openHome(otherTab);

    await page.bringToFront();
    await pressLogout(page);
    await expect(page).toHaveURL(LOGIN_PATH);

    await otherTab.bringToFront();
    await otherTab.getByRole("button", { name: "문서" }).click();

    await expect(otherTab).toHaveURL(LOGIN_PATH);
    await expect(sessionExpiredToast(otherTab)).toBeVisible();
  });

  test("[AUTH-R21] 로그아웃해서 온 로그인 화면에는 로그인 만료 토스트가 뜨지 않음", async ({
    page,
  }) => {
    await openHome(page);
    const refreshRejected = page.waitForResponse((response) =>
      response.url().endsWith(REFRESH_REQUEST_PATH),
    );

    await pressLogout(page);

    await expect(page).toHaveURL(LOGIN_PATH);
    await expect(sessionExpiredToast(page)).toHaveCount(0);

    // 로그인 화면은 로그인 확인을 기다리지 않고 그려져요. 재발급 거절이 남긴 기록은 다음 진입에 드러나서 한 번 더 들어와요
    await refreshRejected;
    await page.reload();

    await expect(
      page.getByRole("button", { name: "GitHub으로 시작하기" }),
    ).toBeVisible();
    await expect(sessionExpiredToast(page)).toHaveCount(0);
  });

  test("[AUTH-R20] 같은 탭에서 다른 계정으로 로그인하면 앞 계정의 피드백 초안과 마지막 대화가 보이지 않음", async ({
    page,
  }) => {
    await openHome(page);
    await page.evaluate(
      ([draftKey, conversationKey]) => {
        sessionStorage.setItem(
          draftKey,
          JSON.stringify({ type: null, content: "앞 계정이 적던 내용" }),
        );
        sessionStorage.setItem(conversationKey, "10");
      },
      [FEEDBACK_DRAFT_KEY, LAST_CONVERSATION_KEY],
    );

    await pressLogout(page);
    await expect(page).toHaveURL(LOGIN_PATH);
    await page.getByRole("button", { name: "GitHub으로 시작하기" }).click();
    await expect(avatar(page)).toBeVisible();

    await page.getByRole("button", { name: "새 소식과 피드백" }).click();
    await page.getByRole("button", { name: "피드백 보내기" }).click();
    await expect(page.getByRole("textbox", { name: "내용" })).toHaveValue("");

    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "탐색" }).click();
    await expect(page).toHaveURL(/\/chat$/);
  });

  test("[AUTH-R20] 같은 탭에서 다른 계정으로 로그인하면 앞 계정이 돌아가려던 주소로 가지 않음", async ({
    page,
  }) => {
    await openHome(page);
    await page.evaluate(
      (returnPathKey) =>
        sessionStorage.setItem(returnPathKey, "/invite/앞-계정의-초대"),
      RETURN_PATH_KEY,
    );

    await pressLogout(page);
    await expect(page).toHaveURL(LOGIN_PATH);
    await page.getByRole("button", { name: "GitHub으로 시작하기" }).click();

    await expect(avatar(page)).toBeVisible();
    await expect(page).not.toHaveURL(/\/invite\//);
  });

  test("[AUTH-R19] 로그아웃을 요청하는 동안 「로그아웃」은 처리 중이고 다시 누를 수 없음", async ({
    page,
  }) => {
    await holdRequests(page, LOGOUT_REQUEST_PATH);
    await openHome(page);

    await pressLogout(page);

    await expectProcessing(page);
    await expect(profileMenu(page).locator("svg")).toHaveCount(0);
    await expectMenuOpened(page);

    await logoutItem(page).dispatchEvent("click");
    // 겹친 요청은 현재 녹음 조회를 거쳐 나가서 그만큼 기다린 뒤에 세요
    await page.waitForTimeout(300);

    expect(
      await page.evaluate(() =>
        (window as unknown as RequestControlWindow).__heldRequestCount(),
      ),
    ).toBe(1);

    await releaseHeldRequests(page);

    await expect(page).toHaveURL(LOGIN_PATH);
  });

  test("[AUTH-R19] 서버 오류나 연결 문제로 로그아웃에 실패하면 로그인과 화면이 그대로이고 오류 토스트", async ({
    page,
  }) => {
    await failRequests(page, LOGOUT_REQUEST_PATH);
    await openHome(page);

    await pressLogout(page);

    await expect(
      page.getByText(TOAST_MESSAGES.LOGOUT_FAILED.message),
    ).toBeVisible();
    await expect(page).toHaveURL(HOME_PATH);
    await expect(logoutItem(page)).toHaveAttribute("aria-disabled", "false");

    await restoreFailedRequests(page);
    await logoutItem(page).click();

    await expect(page).toHaveURL(LOGIN_PATH);
  });
});

test.describe("이 탭에서 녹음 중일 때", () => {
  const recorderBar = (page: Page) =>
    page.getByRole("region", { name: "녹음 조작" });

  /** 독 마이크로 녹음을 시작해 녹음 화면에 들어가요 */
  const startRecording = async (page: Page) => {
    await openHome(page);
    await page.getByRole("button", { name: "회의 녹음 시작" }).click();
    await expect(page).toHaveURL(/\/recording$/);
    await expect(recorderBar(page)).toContainText("녹음 중");
  };

  const pressEndAndLogout = async (page: Page) => {
    await pressLogout(page);
    await endDialog(page)
      .getByRole("button", { name: "끝내고 로그아웃" })
      .click();
  };

  test("[AUTH-R22] 녹음 중이나 일시정지 중에 「로그아웃」을 누르면 녹음을 끝낼지 묻는 창이 뜸", async ({
    page,
  }) => {
    await startRecording(page);

    await pressLogout(page);

    await expect(endDialog(page)).toBeVisible();
    await expect(profileMenu(page)).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(endDialog(page)).toBeHidden();
    await recorderBar(page).getByRole("button", { name: "일시정지" }).click();
    await expect(recorderBar(page)).toContainText("일시정지");

    await pressLogout(page);

    await expect(endDialog(page)).toBeVisible();
  });

  test("[AUTH-R22] 메뉴는 창의 배경 막 아래에 남아 창이 떠 있는 동안 누를 수 없음", async ({
    page,
  }) => {
    await startRecording(page);
    await pressLogout(page);
    await expect(endDialog(page)).toBeVisible();

    const isMenuOnTop = await profileMenu(page).evaluate((menu) => {
      const { x, y, width, height } = menu.getBoundingClientRect();

      return menu.contains(
        document.elementFromPoint(x + width / 2, y + height / 2),
      );
    });

    expect(isMenuOnTop).toBe(false);
  });

  test("[AUTH-R22] 「끝내고 로그아웃」을 누르면 녹음이 끝난 뒤 로그아웃되고 그 녹음은 문서로 정리됨", async ({
    page,
  }) => {
    const urls = collectRequestUrls(page);
    await startRecording(page);

    await pressEndAndLogout(page);

    await expect(page).toHaveURL(LOGIN_PATH);
    const endIndex = urls.findIndex((url) => url.endsWith(END_REQUEST_PATH));
    const logoutIndex = urls.findIndex((url) =>
      url.endsWith(LOGOUT_REQUEST_PATH),
    );

    expect(endIndex).toBeGreaterThan(-1);
    expect(logoutIndex).toBeGreaterThan(endIndex);
  });

  test("[AUTH-R22] 「취소」나 바깥, ESC로 닫으면 확인 창만 닫히고 녹음은 이어짐", async ({
    page,
  }) => {
    await startRecording(page);
    await pressLogout(page);

    await endDialog(page).getByRole("button", { name: "취소" }).click();
    await expect(endDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await logoutItem(page).click();
    await expect(endDialog(page)).toBeVisible();
    await page.mouse.click(10, 500);
    await expect(endDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await logoutItem(page).click();
    await expect(endDialog(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(endDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await expect(page).toHaveURL(/\/recording$/);
    await expect(recorderBar(page)).toContainText("녹음 중");
  });

  test("[AUTH-R22] 끝낸 녹음의 소리를 올리는 동안 「로그아웃」은 처리 중이고 다 올리면 로그아웃됨", async ({
    page,
  }) => {
    const urls = collectRequestUrls(page);
    await holdRequests(page, CHUNK_REQUEST_PATH);
    await startRecording(page);

    await pressEndAndLogout(page);

    await expect(endDialog(page)).toBeHidden();
    await expectProcessing(page);
    await expect
      .poll(() => urls.some((url) => url.endsWith(END_REQUEST_PATH)))
      .toBe(true);
    await tryToCloseMenu(page);
    await expect(profileMenu(page)).toBeVisible();
    expect(urls.some((url) => url.endsWith(LOGOUT_REQUEST_PATH))).toBe(false);

    await releaseHeldRequests(page);

    await expect(page).toHaveURL(LOGIN_PATH);
  });

  test("[AUTH-R22] 연결이 끊겨 끝내기가 서버에 닿지 않으면 연결이 돌아올 때까지 처리 중으로 기다린 뒤 로그아웃됨", async ({
    page,
  }) => {
    await failRequests(page, END_REQUEST_PATH);
    await startRecording(page);

    await pressEndAndLogout(page);
    // 닫히는 애니메이션 중인 창이 ESC로 처리 중을 풀지 않아요
    await page.keyboard.press("Escape");

    await expect(endDialog(page)).toBeHidden();
    await expectProcessing(page);
    await expect(page).toHaveURL(/\/recording$/);

    await restoreFailedRequests(page);

    await expect(page).toHaveURL(LOGIN_PATH, { timeout: 15_000 });
  });
});

test.describe("이 탭이 아닌 곳에서 녹음 중일 때", () => {
  const RECORDING_ELSEWHERE_QUERY = "?mock.currentRecording=recording";

  test("[AUTH-R22] 이 탭이 아닌 곳에서 녹음 중이면 「로그아웃」을 눌렀을 때 알림 창이 뜸", async ({
    page,
  }) => {
    await openHome(page, RECORDING_ELSEWHERE_QUERY);

    await pressLogout(page);

    await expect(elsewhereDialog(page)).toBeVisible();
    await expect(profileMenu(page)).toBeVisible();
  });

  test("[AUTH-R22] 일시정지 중인 녹음도 알림 창이 뜸", async ({ page }) => {
    await openHome(page, "?mock.currentRecording=paused");

    await pressLogout(page);

    await expect(elsewhereDialog(page)).toBeVisible();
  });

  test("[AUTH-R22] 알림 창에서 「로그아웃」을 누르면 이 탭은 로그아웃되고 다른 기기의 녹음은 이어짐", async ({
    page,
  }) => {
    await holdRequests(page, LOGOUT_REQUEST_PATH);
    await openHome(page, RECORDING_ELSEWHERE_QUERY);
    await pressLogout(page);

    await elsewhereDialog(page)
      .getByRole("button", { name: "로그아웃" })
      .click();

    await expect(elsewhereDialog(page)).toBeHidden();
    await expectProcessing(page);

    await releaseHeldRequests(page);

    await expect(page).toHaveURL(LOGIN_PATH);
  });

  test("[AUTH-R22] 알림 창에서 「취소」나 바깥, ESC로 닫으면 알림 창만 닫히고 로그아웃되지 않음", async ({
    page,
  }) => {
    await openHome(page, RECORDING_ELSEWHERE_QUERY);
    await pressLogout(page);

    await elsewhereDialog(page).getByRole("button", { name: "취소" }).click();
    await expect(elsewhereDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await logoutItem(page).click();
    await expect(elsewhereDialog(page)).toBeVisible();
    await page.mouse.click(10, 500);
    await expect(elsewhereDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await logoutItem(page).click();
    await expect(elsewhereDialog(page)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(elsewhereDialog(page)).toBeHidden();
    await expect(profileMenu(page)).toBeVisible();

    await expect(page).toHaveURL(HOME_PATH);
  });

  test("[AUTH-R22] 보고 있는 워크스페이스가 아닌 다른 워크스페이스에서 녹음 중이면 알림 창 없이 로그아웃됨", async ({
    page,
  }) => {
    // 조회는 보고 있는 워크스페이스만 답해요. 다른 워크스페이스의 녹음은 이 조회에 보이지 않아 녹음 없음(204)과 같아요
    await openHome(page, "?mock.currentRecording=none");

    await pressLogout(page);

    await expect(page).toHaveURL(LOGIN_PATH);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("[AUTH-R22] 녹음 여부를 확인하지 못하면 알리지 않고 로그아웃이 이어짐", async ({
    page,
  }) => {
    await failRequests(page, CURRENT_RECORDING_REQUEST_PATH);
    await openHome(page);

    await pressLogout(page);

    await expect(page).toHaveURL(LOGIN_PATH);
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});
