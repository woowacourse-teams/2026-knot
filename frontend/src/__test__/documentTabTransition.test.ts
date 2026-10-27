import { expect, test, type Locator, type Page } from "@playwright/test";

const RECORDINGS_PATH = "/workspace/1/documents/recordings";
const TOPICS_PATH = "/workspace/1/documents/topics";
const TABLIST_LABEL = "문서 보기 방식";
/** 한 번의 탭 전환(미끄러짐과 물방울 맺힘)이 끝나야 하는 시간(ms). GNB 탭과 같아요 */
const MAX_TRANSITION_MS = 400;
/** 진한 알약이 미끄러지며 진행 방향으로 늘어나는 최대 가로 배율 */
const MAX_STRETCH_X = 1.08;
/** 진한 알약이 미끄러지며 눌리는 최소 세로 배율 */
const MIN_STRETCH_Y = 0.94;
/** 진한 알약이 물방울처럼 맺힐 때 가장 부푼 배율 */
const MAX_SWELL_SCALE = 1.06;
/** 누르는 동안의 크기 */
const PRESS_SCALE = 0.96;
/** 계산된 배율의 소수점 오차 */
const SCALE_TOLERANCE = 0.001;

interface IndicatorScale {
  x: number;
  y: number;
}

interface IndicatorSample {
  time: number;
  scale: IndicatorScale;
  left: number;
  center: number;
  /** 배경이 칠해진 탭의 개수. 알약 하나만 움직여야 해서 늘 0이에요 */
  filledTabCount: number;
}

async function openDocuments(page: Page, path = RECORDINGS_PATH) {
  await page.goto(path);
  await expect(
    page.getByRole("tablist", { name: TABLIST_LABEL }),
  ).toBeVisible();
  await page.waitForLoadState("networkidle");
}

const tablistOf = (page: Page) =>
  page.getByRole("tablist", { name: TABLIST_LABEL });

/** 탭 묶음 안의 진한 알약 */
const indicatorOf = (tablist: Locator) => tablist.locator(":scope > span");

const readIndicatorScale = (tablist: Locator) =>
  indicatorOf(tablist).evaluate((indicator) => {
    const values = getComputedStyle(indicator).scale.split(" ").map(Number);
    if (Number.isNaN(values[0])) return { x: 1, y: 1 };
    return { x: values[0], y: values[1] ?? values[0] };
  });

interface SwitchTab {
  /** 고를 탭 이름 */
  label: string;
  /** 누르기로 고를지, 고른 탭에서 오른쪽 화살표로 고를지 */
  by: "click" | "arrowRight";
}

/**
 * 알약에 걸린 모션을 멈추고 10ms씩 옮기며 모양과 자리를 재요. 탭을 고르는 것도 페이지 안에서 바로 해요.
 * 실제 시간으로는 프레임 간격이 기기마다 달라 순간을 놓쳐서예요
 */
const sampleIndicator = (tablist: Locator, switchTab?: SwitchTab) =>
  tablist.evaluate(async (list, switchTab) => {
    const indicator = list.querySelector<HTMLElement>(":scope > span")!;
    const tabs = Array.from(list.querySelectorAll("button"));

    if (switchTab) {
      const target = tabs.find((tab) => tab.textContent === switchTab.label)!;
      if (switchTab.by === "click") {
        target.click();
      } else {
        const selected = tabs.find(
          (tab) => tab.getAttribute("aria-selected") === "true",
        )!;
        selected.focus();
        selected.dispatchEvent(
          new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }),
        );
      }
      const nextFrame = () =>
        new Promise((resolve) => requestAnimationFrame(resolve));
      for (
        let i = 0;
        i < 20 && target.getAttribute("aria-selected") !== "true";
        i++
      ) {
        await nextFrame();
      }
      // 바뀐 스타일을 계산시켜 새 전환이 잡히게 해요
      getComputedStyle(indicator).translate;
    }

    const animations = indicator.getAnimations();
    animations.forEach((animation) => animation.pause());
    const latestEndTime = Math.max(
      0,
      ...animations.map((animation) =>
        Number(animation.effect!.getComputedTiming().endTime),
      ),
    );
    const shapeAnimationCount = animations.filter((animation) =>
      (animation.effect as KeyframeEffect)
        .getKeyframes()
        .some((keyframe) => "scale" in keyframe),
    ).length;

    const listLeft = list.getBoundingClientRect().left;
    const samples = [];
    for (let time = 0; time <= 500; time += 10) {
      animations.forEach((animation) => (animation.currentTime = time));
      const values = getComputedStyle(indicator).scale.split(" ").map(Number);
      const rect = indicator.getBoundingClientRect();
      samples.push({
        time,
        scale: Number.isNaN(values[0])
          ? { x: 1, y: 1 }
          : { x: values[0], y: values[1] ?? values[0] },
        left: rect.left - listLeft,
        center: rect.left + rect.width / 2,
        filledTabCount: tabs.filter(
          (tab) => getComputedStyle(tab).backgroundColor !== "rgba(0, 0, 0, 0)",
        ).length,
      });
    }
    animations.forEach((animation) => animation.finish());

    return { samples, latestEndTime, shapeAnimationCount };
  }, switchTab);

/** 알약이 물방울처럼 늘고 맺히는 동안 GNB 탭과 같은 상한을 넘지 않는지 봐요 */
function expectDropletWithinLimits(samples: IndicatorSample[]) {
  for (const { time, scale } of samples) {
    const at = `${time}ms`;

    expect(scale.x, `${at} 가로 늘어남`).toBeLessThanOrEqual(
      MAX_STRETCH_X + SCALE_TOLERANCE,
    );
    expect(scale.y, `${at} 세로 맺힘`).toBeLessThanOrEqual(
      MAX_SWELL_SCALE + SCALE_TOLERANCE,
    );
    expect(Math.min(scale.x, scale.y), `${at} 눌림`).toBeGreaterThanOrEqual(
      MIN_STRETCH_Y - SCALE_TOLERANCE,
    );
  }
}

test.describe("문서 탭 전환 모션", () => {
  test("「주제별」을 누르면 진한 알약이 미끄러지며 살짝 늘어났다가, 도착해 물방울처럼 맺히고 제 모양으로 돌아온다", async ({
    page,
  }) => {
    await openDocuments(page);
    const tablist = tablistOf(page);
    const topicTab = tablist.getByRole("tab", { name: "주제별" });

    const { samples, latestEndTime, shapeAnimationCount } =
      await sampleIndicator(tablist, { label: "주제별", by: "click" });
    const target = await topicTab.evaluate((tab) => ({
      left: (tab as HTMLElement).offsetLeft,
    }));

    await expect(page).toHaveURL(TOPICS_PATH);
    expectDropletWithinLimits(samples);
    expect(shapeAnimationCount).toBe(1);
    expect(samples[0].left).toBeLessThan(target.left);
    expect(Math.max(...samples.map(({ scale }) => scale.x))).toBeGreaterThan(
      1.04,
    );
    expect(Math.min(...samples.map(({ scale }) => scale.y))).toBeLessThan(0.97);
    for (const { time, filledTabCount } of samples) {
      expect(filledTabCount, `${time}ms 칠해진 탭`).toBe(0);
    }
    const last = samples[samples.length - 1];
    expect(last.left).toBeCloseTo(target.left, 0);
    expect(last.scale).toEqual({ x: 1, y: 1 });
    expect(latestEndTime).toBeLessThanOrEqual(MAX_TRANSITION_MS);
  });

  test("「녹음별」로 돌아오면 알약이 왼쪽으로 미끄러져 돌아온다", async ({
    page,
  }) => {
    await openDocuments(page, TOPICS_PATH);
    const tablist = tablistOf(page);

    const { samples } = await sampleIndicator(tablist, {
      label: "녹음별",
      by: "click",
    });

    await expect(page).toHaveURL(RECORDINGS_PATH);
    expect(samples[0].left).toBeGreaterThan(samples[samples.length - 1].left);
    const targetLeft = await tablist
      .getByRole("tab", { name: "녹음별" })
      .evaluate((tab) => (tab as HTMLElement).offsetLeft);
    expect(samples[samples.length - 1].left).toBeCloseTo(targetLeft, 0);
  });

  test("누르는 동안 탭이 작아지고, 떼면 1.02배를 넘지 않고 돌아온다", async ({
    page,
  }) => {
    await openDocuments(page);
    const topicTab = tablistOf(page).getByRole("tab", { name: "주제별" });
    const box = (await topicTab.boundingBox())!;

    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await expect
      .poll(() =>
        topicTab.evaluate(
          (tab) => new DOMMatrix(getComputedStyle(tab).transform).a,
        ),
      )
      .toBeCloseTo(PRESS_SCALE, 2);

    await page.mouse.up();
    const scales = await topicTab.evaluate((tab) => {
      const animations = tab.getAnimations();
      animations.forEach((animation) => animation.pause());
      const values: number[] = [];
      for (let time = 0; time <= 300; time += 10) {
        animations.forEach((animation) => (animation.currentTime = time));
        values.push(new DOMMatrix(getComputedStyle(tab).transform).a);
      }
      animations.forEach((animation) => animation.finish());
      return values;
    });

    expect(Math.max(...scales)).toBeLessThanOrEqual(1.02);
    expect(scales[scales.length - 1]).toBeCloseTo(1, 3);
  });

  test("고른 탭을 누르면 알약이 함께 눌렸다가, 떼면 이동 없이 제자리에서 물방울처럼 한 번 맺힌다", async ({
    page,
  }) => {
    await openDocuments(page);
    const tablist = tablistOf(page);
    const recordingTab = tablist.getByRole("tab", { name: "녹음별" });
    const box = (await recordingTab.boundingBox())!;
    const tabCenter = box.x + box.width / 2;

    await page.mouse.move(tabCenter, box.y + box.height / 2);
    await page.mouse.down();
    await expect
      .poll(async () => (await readIndicatorScale(tablist)).x)
      .toBeCloseTo(PRESS_SCALE, 3);

    await page.mouse.up();
    const { samples, latestEndTime } = await sampleIndicator(tablist);

    expectDropletWithinLimits(samples);
    expect(Math.max(...samples.map(({ scale }) => scale.x))).toBeGreaterThan(
      1.03,
    );
    for (const { time, center } of samples) {
      expect(center, `${time}ms 알약 가운데`).toBeCloseTo(tabCenter, 0);
    }
    expect(samples[samples.length - 1].scale).toEqual({ x: 1, y: 1 });
    expect(latestEndTime).toBeLessThanOrEqual(MAX_TRANSITION_MS);
    await expect(page).toHaveURL(RECORDINGS_PATH);
  });

  test("움직임 줄이기를 켜 두면 눌림, 알약 이동과 물방울 없이 바로 바뀐다", async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await openDocuments(page);
    const tablist = tablistOf(page);
    const topicTab = tablist.getByRole("tab", { name: "주제별" });

    const box = (await topicTab.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    expect(
      await topicTab.evaluate((tab) => getComputedStyle(tab).transform),
    ).toBe("none");
    await page.mouse.up();
    await expect(topicTab).toHaveAttribute("aria-selected", "true");

    const { samples, latestEndTime } = await sampleIndicator(tablist);
    const targetLeft = await topicTab.evaluate(
      (tab) => (tab as HTMLElement).offsetLeft,
    );

    expect(latestEndTime).toBe(0);
    expect(samples[0].left).toBeCloseTo(targetLeft, 0);
    expect(samples[0].scale).toEqual({ x: 1, y: 1 });
  });

  test("키보드 화살표로 탭을 옮겨도 알약이 새 탭으로 미끄러진다", async ({
    page,
  }) => {
    await openDocuments(page);
    const tablist = tablistOf(page);
    const topicTab = tablist.getByRole("tab", { name: "주제별" });

    const { samples, shapeAnimationCount } = await sampleIndicator(tablist, {
      label: "주제별",
      by: "arrowRight",
    });
    await expect(topicTab).toHaveAttribute("aria-selected", "true");
    const targetLeft = await topicTab.evaluate(
      (tab) => (tab as HTMLElement).offsetLeft,
    );

    expect(shapeAnimationCount).toBe(1);
    expect(samples[0].left).toBeLessThan(targetLeft);
    expect(samples[samples.length - 1].left).toBeCloseTo(targetLeft, 0);
  });
});
