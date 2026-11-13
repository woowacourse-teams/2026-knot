import { expect, test } from "@playwright/test";

/**
 * 랜딩 화면(`/`)과 기술 소개 랜딩(`/landing/technology`) E2E(LAND-QA-001, AUTH-R11, LAND-R1~R8).
 *
 * 로그인하지 않은 사람이 공개 랜딩에서 장면 7개(소개부터 시작까지)를 보고 탐색 소개 다음에 바로 시작하기로 가는지,
 * 첫 장면의 시작 자리에서 「시작하기」 하나를 보고 누르면 로그인 화면으로 가서
 * GitHub, Google, 카카오 세 버튼과 같은 계정 안내를 보는지 확인해요.
 * 기술 소개 랜딩에서는 탐색 소개 다음에 녹음 기술, 탐색 기술 두 장면을 읽고 시작하기로 가는지, 로그인한 사람도 볼 수 있는지 확인해요.
 */

const PROVIDER_LABELS = [
  "GitHub으로 시작하기",
  "Google로 시작하기",
  "카카오로 시작하기",
];

// 랜딩은 로그인하지 않은 사람만 볼 수 있어, 기본으로 넣는 mock 로그인 쿠키를 빼요
test.use({ storageState: { cookies: [], origins: [] } });

test.describe("랜딩 화면", () => {
  test("[LAND-QA-001] 장면 이동 버튼은 소개부터 시작까지 7개이고, 탐색 소개 다음이 바로 시작하기다", async ({
    page,
  }) => {
    await page.goto("/");
    // 엔진은 글꼴을 기다린 뒤 시작해요. 최초 시작이 장면 선택을 덮지 않게 기다려요.
    await page.waitForTimeout(2000);

    await expect(
      page
        .getByRole("navigation", { name: "섹션", exact: true })
        .getByRole("button"),
    ).toHaveText(["소개", "문제", "녹음", "정리", "확인", "탐색", "시작"]);
    await expect(page.getByText(/기술: (녹음|탐색)/)).toHaveCount(0);

    await page
      .getByRole("button", { name: "탐색 섹션으로 이동", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "“그거 어떻게 하기로 했더라?”" }),
    ).toBeVisible();
    await page.waitForTimeout(1000);
    await page.keyboard.press("PageDown");

    await expect(page.getByRole("heading", { name: /팀의 결정을/ })).toBeVisible();
    await expect(page.getByRole("link", { name: "시작하기", exact: true })).toBeVisible();
  });

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

test.describe("기술 소개 랜딩 화면", () => {
  const TECHNOLOGY_PATH = "/landing/technology";

  test("[LAND-R8] 로그인한 사용자는 공개 랜딩 대신 워크스페이스로 가지만, 기술 소개 랜딩은 그대로 볼 수 있다", async ({
    page,
    context,
  }) => {
    await context.addCookies([
      {
        name: "KNOT_MOCK_AUTH",
        value: "member",
        domain: "localhost",
        path: "/",
      },
    ]);
    await page.goto("/");
    await expect(page).toHaveURL(/\/workspace\//);
    await expect(
      page.getByRole("navigation", { name: "섹션", exact: true }),
    ).toHaveCount(0);

    await page.goto(TECHNOLOGY_PATH);
    await expect(page).toHaveURL(/\/landing\/technology$/);
    await expect(
      page
        .getByRole("navigation", { name: "섹션", exact: true })
        .getByRole("button", { name: "기술: 녹음 섹션으로 이동", exact: true }),
    ).toBeVisible();
  });

  test("[LAND-R1, LAND-R2, LAND-R4, LAND-R5, LAND-R6] 비로그인 방문자가 녹음과 탐색 기술을 순서대로 읽고 검증 조건과 근거를 확인한다", async ({
    page,
  }) => {
    await page.goto(TECHNOLOGY_PATH);
    // 엔진은 글꼴을 기다린 뒤 시작해요. 최초 시작이 장면 선택을 덮지 않게 기다려요.
    await page.waitForTimeout(2000);
    await expect(
      page
        .getByRole("navigation", { name: "섹션", exact: true })
        .getByRole("button"),
    ).toHaveText([
      "소개",
      "문제",
      "녹음",
      "정리",
      "확인",
      "탐색",
      "기술: 녹음",
      "기술: 탐색",
      "시작",
    ]);
    await page
      .getByRole("button", { name: "탐색 섹션으로 이동", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "“그거 어떻게 하기로 했더라?”" }),
    ).toBeVisible();
    await page.waitForTimeout(1000);
    await page.keyboard.press("PageDown");
    const recording = page.getByRole("region", {
      name: "녹음은 정확하게, 기록은 이어지게.",
    });
    await expect(recording).toBeVisible();
    await expect(
      recording.getByText("녹음을 5초마다 저장", { exact: true }),
    ).toBeVisible();
    await expect(
      recording.getByText("평균 20분 → 평균 26초", { exact: true }),
    ).toBeVisible();
    await expect(recording.getByText(/보관한 음성을 재전송합니다/)).toBeVisible();
    await expect(recording.getByRole("link")).toHaveAttribute(
      "href",
      "https://ajar-relish-001.notion.site/408b435175228079972bc3b739681859",
    );
    await recording
      .getByRole("button", { name: "탐색 기술 →", exact: true })
      .click();
    const search = page.getByRole("region", {
      name: "찾는 건 빠르게, 답은 근거 있게.",
    });
    await expect(search).toBeVisible();
    await expect(recording).toHaveCount(0);
    await expect(search.getByText("3.5초", { exact: true })).toBeVisible();
    await expect(search.getByText("233회 → 0회", { exact: true })).toBeVisible();
    await expect(
      search.getByText(/첫 비교 실행은 29\/30, 28\/30/),
    ).toBeVisible();
    await expect(
      search.getByRole("link", { name: /탐색 속도 실험 자세히 보기/ }),
    ).toHaveAttribute(
      "href",
      "https://app.notion.com/p/3fc0691a002381899022e9a2f7e320d7",
    );
  });

  test("[LAND-R7, LAND-R8] 움직임 줄이기와 키보드로 두 기술 장면을 지나 시작하기에서 로그인한다", async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto(TECHNOLOGY_PATH);
    await page.waitForTimeout(2000);
    await page.keyboard.press("End");
    await expect(
      page.getByRole("heading", { name: /팀의 결정을/ }),
    ).toBeVisible();
    await page.waitForTimeout(300);
    await page.keyboard.press("PageUp");
    const search = page.getByRole("region", {
      name: "찾는 건 빠르게, 답은 근거 있게.",
    });
    await expect(search).toBeVisible();
    await page.waitForTimeout(300);
    await page.keyboard.press("PageUp");
    await expect(
      page.getByRole("region", { name: "녹음은 정확하게, 기록은 이어지게." }),
    ).toBeVisible();
    await page.waitForTimeout(300);
    await page.keyboard.press("PageDown");
    await expect(search).toBeVisible();
    await search
      .getByRole("button", { name: "시작하기로 →", exact: true })
      .click();
    await page.getByRole("link", { name: "시작하기", exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test("[LAND-R3, LAND-R7] 좁은 화면에서 본문을 스크롤해 읽고 다음 장면으로 나간다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto(TECHNOLOGY_PATH);
    await page.waitForTimeout(2000);
    await page.keyboard.press("End");
    await expect(
      page.getByRole("heading", { name: /팀의 결정을/ }),
    ).toBeVisible();
    await page.waitForTimeout(300);
    await page.keyboard.press("PageUp");
    const search = page.getByRole("region", {
      name: "찾는 건 빠르게, 답은 근거 있게.",
    });
    await expect(search).toBeVisible();
    await expect
      .poll(() =>
        search.evaluate(
          (element) => element.scrollWidth <= element.clientWidth,
        ),
      )
      .toBe(true);
    await search.focus();
    await page.keyboard.press("ArrowDown");
    await expect
      .poll(() => search.evaluate((element) => element.scrollTop))
      .toBeGreaterThan(0);
    await search
      .getByRole("button", { name: "← 녹음 기술", exact: true })
      .click();
    const recording = page.getByRole("region", {
      name: "녹음은 정확하게, 기록은 이어지게.",
    });
    await expect(recording).toBeVisible();
    await recording
      .getByRole("button", { name: "탐색 기술 →", exact: true })
      .click();
    await search
      .getByRole("button", { name: "시작하기로 →", exact: true })
      .click();
    await expect(
      page.getByRole("link", { name: "시작하기", exact: true }),
    ).toBeVisible();
  });
});
