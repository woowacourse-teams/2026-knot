import { expect, test, type Page } from "@playwright/test";

const HOME_PATH = "/workspace/1";
const CHAT_PATH = HOME_PATH + "/chat";
// 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9)
const DOCUMENTS_PATH = HOME_PATH + "/documents/recordings";

/** 반응형 기준(RESP)의 시안 폭과 가장 좁게 지켜야 하는 폭 */
const VIEWPORTS = [
  { name: "모바일 360", width: 360, height: 780 },
  { name: "모바일 390", width: 390, height: 844 },
  { name: "태블릿 834", width: 834, height: 1194 },
];

// 화면 폭보다 넓은 요소가 있으면 문서 전체가 가로로 스크롤돼요.
// 화면은 창이 아니라 본문(main) 안에서 스크롤되므로, 본문 밖으로 넘치는지와 본문이 화면 밖으로 나가는지도 함께 봐요
async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => {
    const main = document.querySelector("main");

    return {
      documentScrollWidth: document.documentElement.scrollWidth,
      mainScrollWidth: main?.scrollWidth ?? 0,
      mainClientWidth: main?.clientWidth ?? 0,
      mainRight: main?.getBoundingClientRect().right ?? 0,
      innerWidth: window.innerWidth,
    };
  });

  expect(widths.documentScrollWidth).toBeLessThanOrEqual(widths.innerWidth);
  expect(widths.mainScrollWidth).toBeLessThanOrEqual(widths.mainClientWidth);
  expect(widths.mainRight).toBeLessThanOrEqual(widths.innerWidth);
}

async function openFirstDocument(page: Page) {
  await page.goto(DOCUMENTS_PATH);
  // 「녹음별」 탭에서 문서 행은 목록 항목 안의 링크예요. 녹음 행(목록 밖)은 녹음 문서로 가요
  await page
    .getByRole("main")
    .getByRole("listitem")
    .first()
    .getByRole("link")
    .click();
  await expect(page.getByRole("button", { name: /원문/ })).toBeVisible();
}

for (const viewport of VIEWPORTS) {
  test.describe(`${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    for (const path of [HOME_PATH, CHAT_PATH, DOCUMENTS_PATH]) {
      test(`${path} 화면이 가로로 넘치지 않는다`, async ({ page }) => {
        await page.goto(path);
        await expect(page.getByRole("main")).toBeVisible();
        await page.waitForLoadState("networkidle");

        await expectNoHorizontalOverflow(page);
      });
    }

    test("문서 화면이 가로로 넘치지 않고, 원문은 처음에 닫혀 있다", async ({
      page,
    }) => {
      await openFirstDocument(page);

      await expectNoHorizontalOverflow(page);
      await expect(page.getByRole("region", { name: "원문" })).toHaveCount(0);
    });

    test("사이드바는 고정되지 않고 덮개로 열리며, 바깥을 누르면 닫힌다", async ({
      page,
    }) => {
      await page.goto(HOME_PATH);
      const sidebarButton = page.getByRole("button", { name: "사이드바" });

      await sidebarButton.click();

      await expect(sidebarButton).toHaveAttribute("aria-expanded", "true");
      await expect(sidebarButton).toHaveAttribute("aria-pressed", "false");
      await expectNoHorizontalOverflow(page);

      // 덮개 오른쪽의 흐린 바깥을 눌러요
      await page.mouse.click(viewport.width - 8, viewport.height / 2);

      await expect(sidebarButton).toHaveAttribute("aria-expanded", "false");
    });
  });
}

test.describe("모바일 390 홈", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("홈 카드 네 개가 좌우 16px 여백 안에서 한 열로 쌓인다", async ({
    page,
  }) => {
    await page.goto(HOME_PATH);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // 인사말 바로 다음 묶음이 카드 묶음이에요
    const cards = page.locator("main h1 + div > *");
    await expect(cards).toHaveCount(4);

    for (const card of await cards.all()) {
      const box = await card.boundingBox();

      expect(Math.round(box!.x)).toBe(16);
      expect(Math.round(box!.width)).toBe(390 - 32);
    }
  });
});

test.describe("모바일 390 원문 시트", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("원문 버튼을 누르면 화면 아래에 붙은 시트로 열리고, 바깥을 누르면 닫힌다", async ({
    page,
  }) => {
    await openFirstDocument(page);

    await page.getByRole("button", { name: /원문/ }).click();
    const transcript = page.getByRole("region", { name: "원문" });
    await expect(transcript).toBeVisible();

    const box = await transcript.boundingBox();
    expect(box).not.toBeNull();
    expect(Math.round(box!.x)).toBe(0);
    expect(Math.round(box!.width)).toBe(390);
    expect(Math.round(box!.y + box!.height)).toBe(844);

    // 시트 위의 흐린 바깥을 눌러요
    await page.mouse.click(195, 90);

    await expect(transcript).toHaveCount(0);
  });
});

test.describe("태블릿 834 원문 덮개", () => {
  test.use({ viewport: { width: 834, height: 1194 } });

  test("원문은 본문을 밀지 않고 오른쪽에 폭 400으로 뜬다", async ({ page }) => {
    await openFirstDocument(page);

    await page.getByRole("button", { name: /원문/ }).click();
    const transcript = page.getByRole("region", { name: "원문" });
    await expect(transcript).toBeVisible();

    const box = await transcript.boundingBox();
    expect(Math.round(box!.width)).toBe(400);
    expect(Math.round(box!.x + box!.width)).toBe(834 - 16);
    await expectNoHorizontalOverflow(page);
  });
});
