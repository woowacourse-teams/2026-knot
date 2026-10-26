import { expect, test, type Locator, type Page } from "@playwright/test";

const HOME_PATH = "/workspace/1";
const CHAT_PATH = HOME_PATH + "/chat";
// 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9)
const DOCUMENTS_PATH = HOME_PATH + "/documents/recordings";
const NAV_LABEL = "워크스페이스 화면 이동";
/** 새 화면이 처음 그려질 때 가장 옅은 정도. 이보다 옅으면 배경이 비쳐 깜빡여 보여요 */
const MIN_SCREEN_OPACITY = 0.6;
/** 본문이 움직이는 최대 거리(px) */
const MAX_SCREEN_OFFSET = 8;
/** 한 번의 탭 전환(미끄러짐과 물방울 맺힘)이 끝나야 하는 시간(ms) */
const MAX_TRANSITION_MS = 400;
/** 진한 표시가 미끄러지며 진행 방향으로 늘어나는 최대 가로 배율 */
const MAX_STRETCH_X = 1.08;
/** 진한 표시가 미끄러지며 눌리는 최소 세로 배율 */
const MIN_STRETCH_Y = 0.94;
/** 진한 표시가 물방울처럼 맺힐 때 가장 부푼 배율 */
const MAX_SWELL_SCALE = 1.06;
/** 누르는 동안의 크기 */
const PRESS_SCALE = 0.96;
/** 계산된 배율의 소수점 오차 */
const SCALE_TOLERANCE = 0.001;

/** 표시의 지금 가로, 세로 배율. `scale` 속성은 값이 없으면 none, 하나면 두 방향이 같아요 */
interface IndicatorScale {
  x: number;
  y: number;
}

interface TransitionFrame {
  time: number;
  /** 독 안에서 보이는 것들 중 가장 옅은 것의 불투명도 */
  dockOpacity: number;
  /** 독 안에서 보이는 것의 개수 */
  dockItemCount: number;
  screenOpacity: number;
  screenOffset: number;
  screenTextLength: number;
  indicatorLeft: number;
  indicatorWidth: number;
  indicatorScale: IndicatorScale;
  /** 배경이 칠해진 탭 칸의 개수 */
  filledTabCount: number;
}

interface TransitionResult {
  frames: TransitionFrame[];
  /** 탭 전환으로 GNB와 본문에서 시작된 애니메이션 중 가장 늦게 끝나는 시각(ms) */
  latestEndTime: number;
  /** 본문에 걸린 애니메이션의 개수 */
  screenAnimationCount: number;
  /** 표시의 모양(scale)에 걸린 애니메이션의 개수. 연달아 눌러도 쌓이지 않아야 해요 */
  indicatorShapeAnimationCount: number;
  target: { left: number; width: number };
}

async function openWorkspace(page: Page, path = HOME_PATH) {
  await page.goto(path);
  await expect(page.getByRole("navigation", { name: NAV_LABEL })).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: "무엇이든 요청하세요" }),
  ).toBeAttached();
  // 처음 그린 화면의 카드가 다 불러와져 본문이 더 바뀌지 않을 때까지 기다려요
  await page.waitForLoadState("networkidle");
}

/** 내비 필 안의 진한 표시 */
const indicatorOf = (nav: Locator) => nav.locator(":scope > span");

const readIndicatorScale = (nav: Locator) =>
  indicatorOf(nav).evaluate((indicator) => {
    const values = getComputedStyle(indicator).scale.split(" ").map(Number);
    if (Number.isNaN(values[0])) return { x: 1, y: 1 };
    return { x: values[0], y: values[1] ?? values[0] };
  });

// 실제 시간으로는 프레임 간격이 기기마다 달라 순간을 놓치므로, 전환 모션을 멈추고 20ms씩 옮기며 화면을 재요
async function measureTabTransition(page: Page, labels: string[]) {
  return page.evaluate(async (labels): Promise<TransitionResult> => {
    const nav = document.querySelector<HTMLElement>(
      'nav[aria-label="워크스페이스 화면 이동"]',
    )!;
    const main = document.querySelector("main")!;
    const dock = document
      .querySelector('textarea[aria-label="무엇이든 요청하세요"]')!
      .closest("form")!;
    const indicator = nav.querySelector<HTMLElement>(":scope > span")!;
    const tabs = Array.from(nav.querySelectorAll("button"));
    const lastLabel = labels[labels.length - 1];
    const lastTab = tabs.find((tab) => tab.textContent === lastLabel)!;

    const nextFrame = () =>
      new Promise((resolve) => requestAnimationFrame(resolve));
    const readScale = (element: Element) => {
      const values = getComputedStyle(element).scale.split(" ").map(Number);
      if (Number.isNaN(values[0])) return { x: 1, y: 1 };
      return { x: values[0], y: values[1] ?? values[0] };
    };

    for (const label of labels) {
      tabs.find((tab) => tab.textContent === label)!.click();
    }
    const isLastTabActive = () =>
      lastTab.getAttribute("aria-current") === "page";
    for (let i = 0; i < 20 && !isLastTabActive(); i++) {
      await nextFrame();
    }
    // 바뀐 스타일을 계산시켜 새 전환이 잡히게 해요
    getComputedStyle(indicator).translate;

    const targetOf = (animation: Animation) =>
      (animation.effect as KeyframeEffect).target as Element;
    // 화면 안에서 계속 도는 스피너와 스켈레톤은 전환 모션이 아니어서 빼요
    const isTransitionTarget = (animation: Animation) =>
      animation.effect!.getComputedTiming().endTime !== Infinity &&
      [nav, main, dock].some((root) => root.contains(targetOf(animation)));
    const animations = document.getAnimations().filter(isTransitionTarget);
    animations.forEach((animation) => animation.pause());

    const latestEndTime = Math.max(
      0,
      ...animations
        .filter(
          (animation) =>
            nav.contains(targetOf(animation)) ||
            targetOf(animation).parentElement === main,
        )
        .map((animation) =>
          Number(animation.effect!.getComputedTiming().endTime),
        ),
    );
    // 본문의 화면 자체에 걸린 모션만 세요. 화면 안의 카드가 제 모션을 가질 수 있어서예요
    const screenAnimationCount = animations.filter(
      (animation) => targetOf(animation).parentElement === main,
    ).length;
    const indicatorShapeAnimationCount = animations.filter(
      (animation) =>
        targetOf(animation) === indicator &&
        (animation.effect as KeyframeEffect)
          .getKeyframes()
          .some((keyframe) => "scale" in keyframe),
    ).length;

    const navLeft = nav.getBoundingClientRect().left;
    const frames: TransitionFrame[] = [];
    for (let time = 0; time <= 500; time += 20) {
      animations.forEach((animation) => (animation.currentTime = time));

      const dockItems = Array.from(dock.children).filter(
        (child) => child.getBoundingClientRect().width > 1,
      );
      const screen = main.firstElementChild as HTMLElement;
      const screenStyle = getComputedStyle(screen);
      const indicatorRect = indicator.getBoundingClientRect();

      frames.push({
        time,
        dockOpacity: Math.min(
          ...dockItems.map((child) => Number(getComputedStyle(child).opacity)),
        ),
        dockItemCount: dockItems.length,
        screenOpacity: Number(screenStyle.opacity),
        screenOffset: new DOMMatrix(screenStyle.transform).e,
        screenTextLength: screen.innerText.trim().length,
        indicatorLeft: indicatorRect.left - navLeft,
        indicatorWidth: indicatorRect.width,
        indicatorScale: readScale(indicator),
        filledTabCount: tabs.filter(
          (tab) => getComputedStyle(tab).backgroundColor !== "rgba(0, 0, 0, 0)",
        ).length,
      });
    }
    animations.forEach((animation) => animation.finish());

    const targetRect = lastTab.getBoundingClientRect();

    return {
      frames,
      latestEndTime,
      screenAnimationCount,
      indicatorShapeAnimationCount,
      target: { left: targetRect.left - navLeft, width: targetRect.width },
    };
  }, labels);
}

function expectNoBlankFrame(frames: TransitionFrame[]) {
  for (const frame of frames) {
    const at = `${frame.time}ms`;

    expect(frame.dockItemCount, `${at} 독 내용 개수`).toBeGreaterThan(0);
    expect(frame.dockOpacity, `${at} 독 내용`).toBeGreaterThanOrEqual(
      MIN_SCREEN_OPACITY,
    );
    expect(frame.screenTextLength, `${at} 본문 글`).toBeGreaterThan(0);
    expect(frame.screenOpacity, `${at} 본문`).toBeGreaterThanOrEqual(
      MIN_SCREEN_OPACITY,
    );
    expect(Math.abs(frame.screenOffset), `${at} 본문 이동`).toBeLessThanOrEqual(
      MAX_SCREEN_OFFSET,
    );
    expect(frame.filledTabCount, `${at} 칠해진 탭 칸`).toBe(0);
  }
}

/** 표시가 물방울처럼 늘고 맺히는 동안 상한을 넘지 않는지 봐요. 과하면 장난스러워 보여서예요 */
function expectDropletWithinLimits(
  scales: { time: number; scale: IndicatorScale }[],
) {
  for (const { time, scale } of scales) {
    const at = `${time}ms`;

    expect(scale.x, `${at} 가로 늘어남`).toBeLessThanOrEqual(
      MAX_STRETCH_X + SCALE_TOLERANCE,
    );
    expect(scale.y, `${at} 세로 맺힘`).toBeLessThanOrEqual(
      MAX_SWELL_SCALE + SCALE_TOLERANCE,
    );
    expect(Math.min(scale.x, scale.y), `${at} 전체 확대`).toBeLessThanOrEqual(
      MAX_SWELL_SCALE + SCALE_TOLERANCE,
    );
    expect(Math.min(scale.x, scale.y), `${at} 눌림`).toBeGreaterThanOrEqual(
      MIN_STRETCH_Y - SCALE_TOLERANCE,
    );
  }
}

const scalesOf = (frames: TransitionFrame[]) =>
  frames.map(({ time, indicatorScale }) => ({ time, scale: indicatorScale }));

test.describe("GNB 탭 전환", () => {
  test("홈에서 탐색으로 옮길 때 빈 화면 없이 표시가 미끄러지고 본문이 오른쪽에서 들어온다", async ({
    page,
  }) => {
    await openWorkspace(page);

    const { frames, latestEndTime, target } = await measureTabTransition(page, [
      "탐색",
    ]);

    expectNoBlankFrame(frames);
    expect(frames[0].screenOffset).toBeGreaterThan(0);
    expect(frames[0].indicatorLeft).toBeLessThan(target.left);
    expect(frames[frames.length - 1].screenOffset).toBe(0);
    expect(frames[frames.length - 1].screenOpacity).toBe(1);
    expect(frames[frames.length - 1].indicatorLeft).toBeCloseTo(target.left, 0);
    expect(frames[frames.length - 1].indicatorWidth).toBeCloseTo(
      target.width,
      0,
    );
    expect(latestEndTime).toBeLessThanOrEqual(MAX_TRANSITION_MS);
  });

  test("표시가 미끄러지는 동안 진행 방향으로 살짝 늘어났다가, 새 탭에 도착하며 물방울처럼 한 번 맺히고 제 모양으로 돌아온다", async ({
    page,
  }) => {
    await openWorkspace(page);

    const { frames, indicatorShapeAnimationCount } = await measureTabTransition(
      page,
      ["문서"],
    );
    const scales = scalesOf(frames);

    expectDropletWithinLimits(scales);
    expect(indicatorShapeAnimationCount).toBe(1);
    expect(frames[0].indicatorScale).toEqual({ x: 1, y: 1 });
    expect(Math.max(...scales.map(({ scale }) => scale.x))).toBeGreaterThan(
      1.04,
    );
    expect(Math.min(...scales.map(({ scale }) => scale.y))).toBeLessThan(0.97);
    expect(Math.max(...scales.map(({ scale }) => scale.y))).toBeGreaterThan(
      1.03,
    );
    expect(frames[frames.length - 1].indicatorScale).toEqual({ x: 1, y: 1 });
  });

  test("탐색에서 홈으로 돌아올 때 빈 화면 없이 본문이 왼쪽에서 들어온다", async ({
    page,
  }) => {
    await openWorkspace(page, CHAT_PATH);

    const { frames, target } = await measureTabTransition(page, ["홈"]);

    expectNoBlankFrame(frames);
    expect(frames[0].screenOffset).toBeLessThan(0);
    expect(frames[0].indicatorLeft).toBeGreaterThan(target.left);
    expect(frames[frames.length - 1].indicatorLeft).toBeCloseTo(target.left, 0);
  });

  test("빠르게 연달아 누르면 마지막 탭의 화면만 한 번 움직인다", async ({
    page,
  }) => {
    await openWorkspace(page);

    const { frames, screenAnimationCount, target } = await measureTabTransition(
      page,
      ["탐색", "문서"],
    );

    await expect(page).toHaveURL(DOCUMENTS_PATH);
    expect(screenAnimationCount).toBeLessThanOrEqual(1);
    expect(frames[frames.length - 1].indicatorLeft).toBeCloseTo(target.left, 0);
  });

  test("표시가 움직이는 중에 다른 탭을 누르면 지금 자리와 모양에서 이어서 새 탭으로 가고, 모양 변화가 쌓이지 않는다", async ({
    page,
  }) => {
    await openWorkspace(page);

    const { before, after, shapeAnimationCount } = await page.evaluate(
      async () => {
        const nav = document.querySelector<HTMLElement>(
          'nav[aria-label="워크스페이스 화면 이동"]',
        )!;
        const indicator = nav.querySelector<HTMLElement>(":scope > span")!;
        const tabOf = (label: string) =>
          Array.from(nav.querySelectorAll("button")).find(
            (tab) => tab.textContent === label,
          )!;
        const nextFrame = () =>
          new Promise((resolve) => requestAnimationFrame(resolve));
        const switchTab = async (label: string) => {
          tabOf(label).click();
          for (
            let i = 0;
            i < 20 && tabOf(label).getAttribute("aria-current") !== "page";
            i++
          ) {
            await nextFrame();
          }
          getComputedStyle(indicator).translate;
        };
        const pauseIndicatorAt = (time: number) => {
          const animations = indicator.getAnimations();
          animations.forEach((animation) => {
            animation.pause();
            animation.currentTime = time;
          });
          return animations;
        };
        const readShape = () => {
          const values = getComputedStyle(indicator)
            .scale.split(" ")
            .map(Number);
          const scale = Number.isNaN(values[0])
            ? { x: 1, y: 1 }
            : { x: values[0], y: values[1] ?? values[0] };
          return { scale, left: indicator.getBoundingClientRect().left };
        };

        // 홈에서 탐색으로 가며 가장 늘어난 즈음에 멈춰 두고 문서를 눌러요
        await switchTab("탐색");
        pauseIndicatorAt(80);
        const before = readShape();

        await switchTab("문서");
        const animations = pauseIndicatorAt(0);
        const after = readShape();
        const shapeAnimationCount = animations.filter((animation) =>
          (animation.effect as KeyframeEffect)
            .getKeyframes()
            .some((keyframe) => "scale" in keyframe),
        ).length;
        animations.forEach((animation) => animation.finish());

        return { before, after, shapeAnimationCount };
      },
    );

    await expect(page).toHaveURL(DOCUMENTS_PATH);
    expect(before.scale.x).toBeGreaterThan(1);
    expect(after.scale.x).toBeCloseTo(before.scale.x, 3);
    expect(after.scale.y).toBeCloseTo(before.scale.y, 3);
    expect(after.left).toBeCloseTo(before.left, 0);
    expect(shapeAnimationCount).toBe(1);
  });

  test("처음 들어오거나 창 크기가 바뀌면 표시가 미끄러지지 않고 제자리에 놓인다", async ({
    page,
  }) => {
    await page.addInitScript(() => {
      const runs: string[] = [];
      (window as unknown as { indicatorRuns: string[] }).indicatorRuns = runs;
      document.addEventListener("transitionrun", (event) => {
        const target = event.target as HTMLElement;
        const indicatorSelector =
          'nav[aria-label="워크스페이스 화면 이동"] > span';
        if (target.matches(indicatorSelector)) runs.push(event.propertyName);
      });
    });
    await openWorkspace(page);
    await page.setViewportSize({ width: 900, height: 700 });
    await page.waitForTimeout(100);

    const nav = page.getByRole("navigation", { name: NAV_LABEL });
    const indicatorBox = await indicatorOf(nav).boundingBox();
    const homeBox = await nav.getByRole("button", { name: "홈" }).boundingBox();
    const runs = await page.evaluate(
      () => (window as unknown as { indicatorRuns: string[] }).indicatorRuns,
    );

    expect(runs).toEqual([]);
    expect(
      await indicatorOf(nav).evaluate((indicator) => indicator.getAnimations()),
    ).toEqual([]);
    expect(indicatorBox!.x).toBeCloseTo(homeBox!.x, 0);
    expect(indicatorBox!.width).toBeCloseTo(homeBox!.width, 0);
  });

  test("같은 탭 안에서 옮길 때는 본문이 움직이지 않는다", async ({ page }) => {
    await openWorkspace(page, DOCUMENTS_PATH);
    // 「녹음별」 탭에서 문서 행은 목록 항목 안의 링크예요. 녹음 행(목록 밖)은 녹음 문서로 가요
    const documentLink = page
      .getByRole("main")
      .getByRole("listitem")
      .first()
      .getByRole("link");
    const documentPath = (await documentLink.getAttribute("href"))!;
    await documentLink.click();
    await expect(page).not.toHaveURL(DOCUMENTS_PATH);
    // 주소가 먼저 바뀌고 문서 화면은 불러온 뒤에 그려져요. 그 사이에는 GNB가 아직 목록 기준이라 문서 화면이 그려진 뒤 눌러요
    await expect(page.locator(`main a[href="${documentPath}"]`)).toHaveCount(0);

    const { screenAnimationCount } = await measureTabTransition(page, ["문서"]);

    await expect(page).toHaveURL(DOCUMENTS_PATH);
    expect(screenAnimationCount).toBe(0);
  });

  test("누르는 동안 탭 칸이 작아지고, 떼면 1.02배를 넘지 않고 돌아온다", async ({
    page,
  }) => {
    await openWorkspace(page);
    const homeTab = page
      .getByRole("navigation", { name: NAV_LABEL })
      .getByRole("button", { name: "홈" });
    const box = (await homeTab.boundingBox())!;

    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await expect
      .poll(() =>
        homeTab.evaluate(
          (tab) => new DOMMatrix(getComputedStyle(tab).transform).a,
        ),
      )
      .toBeCloseTo(0.96, 2);

    await page.mouse.up();
    const scales = await homeTab.evaluate((tab) => {
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
    await expect(page).toHaveURL(HOME_PATH);
  });

  test("지금 탭을 누르면 표시가 함께 눌렸다가, 떼면 이동 없이 제자리에서 물방울처럼 한 번 맺힌다", async ({
    page,
  }) => {
    await openWorkspace(page);
    const nav = page.getByRole("navigation", { name: NAV_LABEL });
    const homeTab = nav.getByRole("button", { name: "홈" });
    const box = (await homeTab.boundingBox())!;
    const homeCenter = box.x + box.width / 2;

    await page.mouse.move(homeCenter, box.y + box.height / 2);
    await page.mouse.down();
    await expect
      .poll(async () => (await readIndicatorScale(nav)).x)
      .toBeCloseTo(PRESS_SCALE, 3);

    await page.mouse.up();
    const { samples, latestEndTime } = await indicatorOf(nav).evaluate(
      (indicator) => {
        const animations = indicator.getAnimations();
        animations.forEach((animation) => animation.pause());
        const latestEndTime = Math.max(
          0,
          ...animations.map((animation) =>
            Number(animation.effect!.getComputedTiming().endTime),
          ),
        );
        const samples = [];
        for (let time = 0; time <= 500; time += 10) {
          animations.forEach((animation) => (animation.currentTime = time));
          const values = getComputedStyle(indicator)
            .scale.split(" ")
            .map(Number);
          const rect = indicator.getBoundingClientRect();
          samples.push({
            time,
            scale: Number.isNaN(values[0])
              ? { x: 1, y: 1 }
              : { x: values[0], y: values[1] ?? values[0] },
            center: rect.left + rect.width / 2,
          });
        }
        animations.forEach((animation) => animation.finish());
        return { samples, latestEndTime };
      },
    );

    expectDropletWithinLimits(samples);
    expect(
      Math.min(...samples.map(({ scale }) => scale.x)),
    ).toBeGreaterThanOrEqual(PRESS_SCALE - SCALE_TOLERANCE);
    expect(Math.max(...samples.map(({ scale }) => scale.x))).toBeGreaterThan(
      1.03,
    );
    for (const { time, center } of samples) {
      expect(center, `${time}ms 표시 가운데`).toBeCloseTo(homeCenter, 0);
    }
    expect(samples[samples.length - 1].scale).toEqual({ x: 1, y: 1 });
    expect(latestEndTime).toBeLessThanOrEqual(MAX_TRANSITION_MS);
    await expect(page).toHaveURL(HOME_PATH);
  });

  test("움직임 줄이기를 켜 두면 눌림, 표시 이동과 물방울, 본문 모두 바로 바뀐다", async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await openWorkspace(page);
    const nav = page.getByRole("navigation", { name: NAV_LABEL });
    const exploreTab = nav.getByRole("button", { name: "탐색" });

    const { frames, latestEndTime, screenAnimationCount, target } =
      await measureTabTransition(page, ["탐색"]);

    expect(latestEndTime).toBe(0);
    expect(screenAnimationCount).toBe(0);
    expect(frames[0].indicatorLeft).toBeCloseTo(target.left, 0);
    expect(frames[0].screenOffset).toBe(0);

    const box = (await exploreTab.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    expect(
      await exploreTab.evaluate((tab) => getComputedStyle(tab).transform),
    ).toBe("none");
    expect(await readIndicatorScale(nav)).toEqual({ x: 1, y: 1 });
    await page.mouse.up();
    expect(
      await indicatorOf(nav).evaluate((indicator) => indicator.getAnimations()),
    ).toEqual([]);
    expect(await readIndicatorScale(nav)).toEqual({ x: 1, y: 1 });
  });

  test("탭을 누르면 포커스가 누른 탭에 남고 지금 탭으로 알린다", async ({
    page,
  }) => {
    await openWorkspace(page);
    const exploreTab = page
      .getByRole("navigation", { name: NAV_LABEL })
      .getByRole("button", { name: "탐색" });

    await exploreTab.click();

    await expect(page).toHaveURL(CHAT_PATH);
    await expect(exploreTab).toBeFocused();
    await expect(exploreTab).toHaveAttribute("aria-current", "page");
  });
});
