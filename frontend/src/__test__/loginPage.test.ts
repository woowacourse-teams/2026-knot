import { expect, test, type Page } from "@playwright/test";

/**
 * 로그인 화면(`/login`) E2E(AUTH-R11, AUTH-R12, AUTH-R16, TERMS-R10).
 *
 * 로그인하지 않은 사람이 GitHub, Google, 카카오 세 버튼과 같은 계정 안내, 약관 고지를 보고,
 * 버튼 하나를 누르면 넘어가기 전까지 세 버튼 모두 누를 수 없는지 확인해요.
 * 실제 로그인은 각 사의 화면과 서버가 필요해 다루지 않고, 프론트가 보내는 로그인 시작 주소까지만 봐요.
 */

const LOGIN_PATH = "/login";
const OAUTH_AUTHORIZATION_PATTERN = "**/oauth2/authorization/*";
const PROVIDER_LABELS = [
  "GitHub으로 시작하기",
  "Google로 시작하기",
  "카카오로 시작하기",
];
const SAME_ACCOUNT_GUIDE = "처음 가입한 방법으로 로그인해야 같은 계정이에요";
const TERMS_NOTICE =
  "로그인하면 서비스 이용약관과 개인정보 처리방침에 동의하게 됩니다.";

// 로그인 화면은 로그인하지 않은 사람만 볼 수 있어, 기본으로 넣는 mock 로그인 쿠키를 빼요
test.use({ storageState: { cookies: [], origins: [] } });

const getLoginButtons = (page: Page) => page.getByRole("button");

/** 위에서 아래로 놓인 순서대로 요소의 화면 위 끝(px)을 돌려줘요 */
const topOf = async (page: Page, text: string) => {
  const box = await page.getByText(text, { exact: true }).boundingBox();

  return box?.y ?? Number.NaN;
};

test.describe("로그인 화면", () => {
  test("[AUTH-QA-001] GitHub, Google, 카카오 순서로 세 버튼을 두고, 그 아래에 같은 계정 안내와 약관 고지를 둔다", async ({
    page,
  }) => {
    await page.goto(LOGIN_PATH);

    await expect(getLoginButtons(page)).toHaveText(PROVIDER_LABELS);
    await expect(page.getByText(SAME_ACCOUNT_GUIDE)).toBeVisible();
    await expect(page.getByText(TERMS_NOTICE)).toBeVisible();

    const lastButton = await getLoginButtons(page).last().boundingBox();
    const guideTop = await topOf(page, SAME_ACCOUNT_GUIDE);
    const termsTop = await topOf(page, TERMS_NOTICE);

    expect(guideTop).toBeGreaterThan(lastButton?.y ?? Number.NaN);
    expect(termsTop).toBeGreaterThan(guideTop);
  });

  for (const [index, provider] of ["github", "google", "kakao"].entries()) {
    test(`[AUTH-R11] 「${PROVIDER_LABELS[index]}」를 누르면 ${provider} 로그인 시작 주소로 넘어간다`, async ({
      page,
    }) => {
      // 실제 백엔드가 없으니 시작 주소로 가는 이동을 받아 빈 화면으로 답해요
      await page.route(OAUTH_AUTHORIZATION_PATTERN, (route) =>
        route.fulfill({ contentType: "text/html", body: "<p>OAuth</p>" }),
      );
      await page.goto(LOGIN_PATH);

      await getLoginButtons(page).nth(index).click();

      await expect(page).toHaveURL(
        new RegExp(`/oauth2/authorization/${provider}$`),
      );
    });
  }

  test("[AUTH-QA-005] 버튼 하나를 누르면 넘어가기 전까지 누른 버튼은 처리 중이고, 세 버튼 모두 누를 수 없다", async ({
    page,
  }) => {
    // 시작 주소로 가는 이동을 취소해 넘어가기 전의 화면에 머물게 해요.
    // 답하지 않고 붙잡아 두면 이동이 끝나기를 기다리느라 Playwright가 화면을 읽지 못해요
    await page.route(OAUTH_AUTHORIZATION_PATTERN, (route) =>
      route.abort("aborted"),
    );
    await page.goto(LOGIN_PATH);
    const buttons = getLoginButtons(page);

    await buttons.nth(2).click();

    await expect(buttons.nth(2)).toHaveAttribute("aria-busy", "true");
    await expect(buttons.nth(0)).toHaveAttribute("aria-busy", "false");
    await expect(buttons.nth(1)).toHaveAttribute("aria-busy", "false");
    for (const index of [0, 1, 2]) {
      await expect(buttons.nth(index)).toBeDisabled();
    }
  });

  test("[AUTH-R16] Google, 카카오 승인 화면에서 취소하고 돌아오면 안내 없이 로그인 기본 화면을 보여 준다", async ({
    page,
  }) => {
    // 백엔드는 세 수단 모두 취소를 이 주소로 돌려보내요
    await page.goto(`${LOGIN_PATH}?error=oauth2_cancelled`);

    await expect(getLoginButtons(page)).toHaveText(PROVIDER_LABELS);
    await expect(page.getByRole("alert")).toHaveCount(0);
  });

  test("[AUTH-R7] 취소가 아닌 로그인 실패로 돌아오면 버튼 위에 실패 안내를 띄운다", async ({
    page,
  }) => {
    await page.goto(`${LOGIN_PATH}?error=oauth2`);

    await expect(page.getByRole("alert")).toBeVisible();
    await expect(getLoginButtons(page)).toHaveText(PROVIDER_LABELS);
  });
});
