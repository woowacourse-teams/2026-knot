import { expect, test } from "@playwright/test";

/**
 * 랜딩 화면(`/`) E2E(AUTH-R11).
 *
 * 로그인하지 않은 사람이 첫 장면의 시작 자리에서 「시작하기」 하나를 보고,
 * 누르면 로그인 화면으로 가서 GitHub, Google, 카카오 세 버튼과 같은 계정 안내를 보는지 확인해요.
 */

const PROVIDER_LABELS = [
  "GitHub으로 시작하기",
  "Google로 시작하기",
  "카카오로 시작하기",
];

// 랜딩은 로그인하지 않은 사람만 볼 수 있어, 기본으로 넣는 mock 로그인 쿠키를 빼요
test.use({ storageState: { cookies: [], origins: [] } });

test.describe("랜딩 화면", () => {
  test("[AUTH-R11] 첫 장면의 시작 자리에는 「시작하기」 하나만 두고, 누르면 로그인 화면에서 세 버튼과 같은 계정 안내를 본다", async ({
    page,
  }) => {
    await page.goto("/");

    // 보이지 않는 장면은 aria-hidden이라, 역할로 찾으면 지금 보이는 첫 장면의 링크만 잡혀요
    const startLink = page.getByRole("link", { name: "시작하기", exact: true });
    await expect(startLink).toHaveCount(1);
    await expect(
      page.getByRole("button", { name: /로 시작하기$/ }),
    ).toHaveCount(0);

    await startLink.click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole("button", { name: /로 시작하기$/ }),
    ).toHaveText(PROVIDER_LABELS);
    await expect(
      page.getByText("처음 가입한 방법으로 로그인해야 같은 계정이에요"),
    ).toBeVisible();
  });
});
