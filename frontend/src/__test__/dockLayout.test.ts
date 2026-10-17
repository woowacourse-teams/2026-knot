import { expect, test, type Page } from "@playwright/test";

const HOME_PATH = "/workspace/1";
const DOCK_HEIGHT = 60;
const PRESERVED_MESSAGE =
  "첫 줄 질문\n지난 회의에서 정한 배포 일정과 담당자를 정리해 주세요";

interface DockFrame {
  time: number;
  top: number;
  bottom: number;
  height: number;
}

// 녹음 칩이 있으면 접힌 폭이 내용만큼이라 입력창이 좁아지지 않아요. 가장 좁게 출발하는 마이크 독에서 확인해요.
// mock 워커가 먼저 받는 요청은 page.route가 잡지 못해, 현재 녹음 조회만 닿지 않는 주소로 보내 실패시켜요.
// 조회에 실패하면 독은 마이크를 둬요
async function openHomeWithoutRecording(page: Page) {
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
  await page.goto(HOME_PATH);
  await expect(
    page.getByRole("button", { name: "회의 녹음 시작" }),
  ).toBeVisible();
}

// 실제 시간으로는 프레임 간격이 기기마다 달라 순간 급증을 놓치므로, 펼침 모션을 멈추고 20ms씩 옮기며 독을 재요
async function measureExpand(page: Page) {
  return page.evaluate(async () => {
    const field = document.querySelector<HTMLTextAreaElement>(
      'textarea[aria-label="무엇이든 요청하세요"]',
    )!;
    const dock = field.closest("form")!;

    dock
      .querySelector<HTMLButtonElement>(
        'button[aria-label="무엇이든 요청하기"]',
      )!
      .click();
    await new Promise((resolve) => setTimeout(resolve, 0));

    const animations = document
      .getAnimations()
      .filter((animation) =>
        dock.contains((animation.effect as KeyframeEffect).target as Node),
      );
    animations.forEach((animation) => animation.pause());

    const frames = [];
    for (let time = 0; time <= 500; time += 20) {
      animations.forEach((animation) => (animation.currentTime = time));
      const { top, bottom, height } = dock.getBoundingClientRect();
      frames.push({ time, top, bottom, height });
    }
    animations.forEach((animation) => animation.finish());

    return frames;
  });
}

function expectNoSpike(frames: DockFrame[]) {
  const last = frames[frames.length - 1];

  for (const frame of frames) {
    expect(frame.height, `${frame.time}ms 독 높이`).toBeLessThanOrEqual(
      last.height,
    );
    expect(frame.top, `${frame.time}ms 독 위쪽`).toBeGreaterThanOrEqual(
      last.top,
    );
    expect(frame.bottom, `${frame.time}ms 독 아래쪽`).toBe(last.bottom);
  }
}

test.describe("독 펼침 레이아웃", () => {
  test("빈 입력창으로 펼칠 때 독이 위로 튀지 않는다", async ({ page }) => {
    await openHomeWithoutRecording(page);

    const frames = await measureExpand(page);

    expectNoSpike(frames);
    expect(frames[0].height).toBe(DOCK_HEIGHT);
  });

  test("남겨 둔 여러 줄 글로 다시 펼칠 때 독이 위로 튀지 않는다", async ({
    page,
  }) => {
    await openHomeWithoutRecording(page);
    const field = page.getByRole("textbox", { name: "무엇이든 요청하세요" });
    await page.getByRole("button", { name: "무엇이든 요청하기" }).click();
    await field.fill(PRESERVED_MESSAGE);
    await page.mouse.click(10, 300);
    await expect(
      page.getByRole("button", { name: "무엇이든 요청하기" }),
    ).toBeVisible();

    const frames = await measureExpand(page);

    expectNoSpike(frames);
    await expect(field).toHaveValue(PRESERVED_MESSAGE);
    await expect(field).toBeFocused();
  });
});
