import {
  GetReleaseNotesResponseDto,
  ReleaseNoteItemDto,
} from "@api/dto/releaseNote";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import {
  imageReleaseNoteItems,
  releaseNotesResponse,
} from "@api/mock/responses/releaseNote";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { expect, test, type Page } from "@playwright/test";

/**
 * GNB 「소식과 피드백」 메뉴와 피드백 보내기 E2E.
 *
 * 워크스페이스 화면 어디서나 GNB 오른쪽 버튼으로 새 소식과 피드백 보내기 메뉴를 열고,
 * 피드백 패널에서 유형을 골라 보내는 플로우를 확인해요. 휴대폰에서는 사이드바 맨 아래 「피드백」으로 시트를 열어요.
 * 피드백 저장(`/relay/f`)은 dev 서버의 msw mock이 받은 것처럼만 응답해요(`API_MOCKING`).
 * 새 소식 노트(`/relay/n`)도 msw mock이 주고, 읽음은 이 기기 localStorage에만 남아요.
 * 노트의 github.com 사진은 msw mock이 주소에 적힌 크기(`news-1596x1386`)의 사진으로 돌려주고, 크기가 없는 주소는 404예요.
 * 기본은 이미 다 본 사용자(`playwright.config.ts`의 storageState)라, 안 읽은 소식은 테스트에서 따로 만들어요.
 */

const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;

const NOTES = new GetReleaseNotesResponseDto(releaseNotesResponse).notes;
/** 기본 mock 맨 끝 노트의 사진 칸 경우별 항목. 응답 DTO를 거친 값이에요 */
const IMAGE_ITEMS = {
  normal: new ReleaseNoteItemDto(imageReleaseNoteItems.normal),
  wide: new ReleaseNoteItemDto(imageReleaseNoteItems.wide),
  tall: new ReleaseNoteItemDto(imageReleaseNoteItems.tall),
  broken: new ReleaseNoteItemDto(imageReleaseNoteItems.broken),
  disallowed: new ReleaseNoteItemDto(imageReleaseNoteItems.disallowed),
};
const LAST_SEEN_NOTE_ID_KEY = "knot.releaseNote.lastSeenId";
const READ_BUTTON_NAME = "새 소식과 피드백";
const UNREAD_BUTTON_NAME = "새 소식과 피드백, 읽지 않은 소식 있음";
const PANEL_DESCRIPTION = "새로 생기거나 달라진 점을 모아 둬요";

/** 「소식과 피드백」 버튼. 안 읽은 소식이 있으면 이름 뒤에 안내가 붙어요 */
const getMenuButton = (page: Page) =>
  page.getByRole("button", { name: /^새 소식과 피드백/ });

/** 메뉴의 「새 소식」 항목. 안 읽은 소식이 있으면 이름 뒤에 NEW가 붙어요 */
const getNewsMenuItem = (page: Page) =>
  page
    .getByRole("navigation", { name: "새 소식과 피드백" })
    .getByRole("button", { name: /^새 소식/ });

/** 맨 위 노트 하나만 아직 보지 않은 사용자로 홈에 들어가요. 읽음은 이 기기에만 남아 남겨 두고 다시 불러요 */
const visitHomeWithLatestNoteUnread = async (page: Page) => {
  await page.goto(HOME_PATH);
  await page.evaluate(
    ([key, id]) => localStorage.setItem(key, id),
    [LAST_SEEN_NOTE_ID_KEY, NOTES[1].id],
  );
  await page.reload();
};

/** 홈에서 메뉴로 새 소식 패널을 열어요 */
const openNewsPanel = async (page: Page) => {
  await page.goto(HOME_PATH);
  await getMenuButton(page).click();
  await getNewsMenuItem(page).click();
  await expect(page.getByText(PANEL_DESCRIPTION)).toBeVisible();
};

/** 새 소식 패널에서 제목으로 항목을 찾아요 */
const getNewsItem = (page: Page, title: string) =>
  page.getByRole("listitem").filter({ hasText: title });

/** 항목 사진이 보일 때까지 기다렸다가, 항목 폭과 사진 칸 크기, 맞춤 방식을 돌려줘요 */
const measureNewsImage = async (page: Page, title: string) => {
  const item = getNewsItem(page, title);
  const image = item.locator("img");
  await image.scrollIntoViewIfNeeded();
  await expect(image).toBeVisible();

  const itemBox = (await item.boundingBox())!;
  const imageBox = (await image.boundingBox())!;

  return {
    itemWidth: itemBox.width,
    width: imageBox.width,
    height: imageBox.height,
    objectFit: await image.evaluate(
      (element) => getComputedStyle(element).objectFit,
    ),
  };
};

const getFeedbackPanel = (page: Page) =>
  page.getByRole("dialog", { name: "피드백 보내기" });

/** 홈에 들어가 GNB 메뉴에서 「피드백 보내기」를 골라 패널을 열어요 */
const openFeedbackPanel = async (page: Page) => {
  await page.goto(HOME_PATH);
  await getMenuButton(page).click();
  await page.getByRole("button", { name: "피드백 보내기" }).click();
  await expect(getFeedbackPanel(page)).toBeVisible();
};

test.describe("소식과 피드백", () => {
  test("[NEWS-R3] 「소식과 피드백」을 누르면 「새 소식」과 「피드백 보내기」 메뉴가 열리고, 「새 소식」을 고르면 같은 자리에 새 소식 패널이 열린다", async ({
    page,
  }) => {
    await page.goto(HOME_PATH);

    await getMenuButton(page).click();

    await expect(getNewsMenuItem(page)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "피드백 보내기" }),
    ).toBeVisible();

    await getNewsMenuItem(page).click();

    await expect(
      page.getByText("새로 생기거나 달라진 점을 모아 둬요"),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "피드백 보내기" }),
    ).toBeHidden();
  });

  test("[NEWS-R1, NEWS-R2] 안 읽은 새 소식이 있으면 버튼 이름이 「새 소식과 피드백, 읽지 않은 소식 있음」이고, 패널을 열기 전까지는 새로고침해도 남는다", async ({
    page,
  }) => {
    await visitHomeWithLatestNoteUnread(page);

    await expect(getMenuButton(page)).toHaveAccessibleName(UNREAD_BUTTON_NAME);

    await page.reload();

    await expect(getMenuButton(page)).toHaveAccessibleName(UNREAD_BUTTON_NAME);
  });

  test("[NEWS-R3] 안 읽은 새 소식이 있으면 메뉴의 「새 소식」에 NEW 칩을 붙이고, 메뉴만 열었다 닫으면 그대로 둔다", async ({
    page,
  }) => {
    await visitHomeWithLatestNoteUnread(page);

    await getMenuButton(page).click();

    await expect(getNewsMenuItem(page).getByText("NEW")).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(getMenuButton(page)).toHaveAccessibleName(UNREAD_BUTTON_NAME);
  });

  test("[NEWS-R4, NEWS-R2] 새 소식 패널을 열면 안 읽은 노트의 항목에만 NEW를 붙이고, 버튼과 메뉴의 안 읽음 표시는 꺼져 새로고침해도 돌아오지 않는다", async ({
    page,
  }) => {
    await visitHomeWithLatestNoteUnread(page);

    await getMenuButton(page).click();
    await getNewsMenuItem(page).click();

    await expect(page.getByText(PANEL_DESCRIPTION)).toBeVisible();
    await expect(page.getByText("NEW", { exact: true })).toHaveCount(
      NOTES[0].items.length,
    );
    await expect(getMenuButton(page)).toHaveAccessibleName(READ_BUTTON_NAME);

    await page.keyboard.press("Escape");
    // 접히는 동안 다시 누르면 닫히던 패널이 그대로 펼쳐져서, 다 닫힌 뒤에 메뉴를 열어요
    await expect(page.getByText(PANEL_DESCRIPTION)).toBeHidden();
    await getMenuButton(page).click();

    await expect(getNewsMenuItem(page)).toHaveAccessibleName("새 소식");

    await page.reload();

    await expect(getMenuButton(page)).toHaveAccessibleName(READ_BUTTON_NAME);
  });

  test("[NEWS-R7] 이 기기에 남은 노트가 목록에 없으면 모두 읽은 것으로 보고 버튼, 메뉴, 패널 어디에도 안 읽음을 표시하지 않는다", async ({
    page,
  }) => {
    // 기본 storageState가 목록에 없는 노트 id를 남겨 둬요
    await page.goto(HOME_PATH);

    await expect(getMenuButton(page)).toHaveAccessibleName(READ_BUTTON_NAME);

    await getMenuButton(page).click();

    await expect(getNewsMenuItem(page)).toHaveAccessibleName("새 소식");

    await getNewsMenuItem(page).click();

    await expect(page.getByText(PANEL_DESCRIPTION)).toBeVisible();
    await expect(page.getByText("NEW", { exact: true })).toHaveCount(0);
  });

  test("[NEWS-R13] 항목 사진을 자르지 않고 원래 비율로, 항목 폭에 맞춰 보여 준다", async ({
    page,
  }) => {
    await openNewsPanel(page);

    const wide = await measureNewsImage(page, IMAGE_ITEMS.wide.title);

    expect(wide.width).toBeCloseTo(wide.itemWidth, 0);
    // 2560×420 사진을 폭에 맞춘 높이 그대로예요
    expect(wide.height).toBeCloseTo((wide.itemWidth * 420) / 2560, 0);
    expect(wide.objectFit).toBe("contain");
  });

  test("[NEWS-R13] 최대 높이를 넘는 사진은 비율을 지킨 채 높이 240으로 줄이고 가운데에 둔다", async ({
    page,
  }) => {
    await openNewsPanel(page);

    for (const { title } of [IMAGE_ITEMS.normal, IMAGE_ITEMS.tall]) {
      const image = await measureNewsImage(page, title);

      expect(image.width).toBeCloseTo(image.itemWidth, 0);
      expect(image.height).toBeCloseTo(240, 0);
      // 칸 안에서 비율을 지켜 줄이고 남는 양옆을 비워요(object-position 기본값이 가운데)
      expect(image.objectFit).toBe("contain");
    }
  });

  test("[NEWS-R14] 허용하지 않는 사진 주소는 사진 칸을 그리지 않고 글만 보여 준다", async ({
    page,
  }) => {
    await openNewsPanel(page);

    const item = getNewsItem(page, IMAGE_ITEMS.disallowed.title);
    await item.scrollIntoViewIfNeeded();

    await expect(
      item.getByText(IMAGE_ITEMS.disallowed.description),
    ).toBeVisible();
    await expect(item.locator("img")).toHaveCount(0);
  });

  test("[NEWS-R14] 열리지 않는 사진은 사진 칸만 숨기고 제목과 설명은 그대로 보여 준다", async ({
    page,
  }) => {
    const brokenImage = page.waitForResponse(
      (response) => response.url() === IMAGE_ITEMS.broken.image,
    );
    await openNewsPanel(page);

    expect((await brokenImage).status()).toBe(404);

    const item = getNewsItem(page, IMAGE_ITEMS.broken.title);
    await item.scrollIntoViewIfNeeded();

    await expect(item.getByText(IMAGE_ITEMS.broken.description)).toBeVisible();
    await expect(item.locator("img")).toBeHidden();
  });

  test("[FEEDBACK-R5, R8] 유형만 골라도 보낼 수 있고, 보내면 패널이 닫히며 완료 토스트가 뜬다", async ({
    page,
  }) => {
    await openFeedbackPanel(page);
    const sendButton = getFeedbackPanel(page).getByRole("button", {
      name: "보내기",
    });

    await expect(sendButton).toBeDisabled();
    await page.getByText("좋았어요", { exact: true }).click();
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(getFeedbackPanel(page)).toBeHidden();
    await expect(
      page.getByText(TOAST_MESSAGES.FEEDBACK_SENT.message),
    ).toBeVisible();
    await expect(getMenuButton(page)).toBeFocused();
  });

  test("[FEEDBACK-R4, R6] ESC로 닫으면 버튼으로 포커스가 돌아오고, 새로고침한 뒤 다시 열어도 적던 내용이 그대로다", async ({
    page,
  }) => {
    await openFeedbackPanel(page);
    await page.getByText("오류가 있어요", { exact: true }).click();
    await getFeedbackPanel(page)
      .getByRole("textbox", { name: "내용" })
      .fill("문서 복사 버튼을 눌렀는데 표가 깨져요.");

    await page.keyboard.press("Escape");

    await expect(getFeedbackPanel(page)).toBeHidden();
    await expect(getMenuButton(page)).toBeFocused();

    await page.reload();
    await getMenuButton(page).click();
    await page.getByRole("button", { name: "피드백 보내기" }).click();

    await expect(
      getFeedbackPanel(page).getByRole("radio", { name: "오류가 있어요" }),
    ).toBeChecked();
    await expect(
      getFeedbackPanel(page).getByRole("textbox", { name: "내용" }),
    ).toHaveValue("문서 복사 버튼을 눌렀는데 표가 깨져요.");
  });

  test.describe("휴대폰", () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test("[FEEDBACK-R2] 사이드바 맨 아래 「피드백」을 누르면 피드백 패널을 아래 시트로 열고, 「소식과 피드백」은 메뉴 없이 새 소식을 연다", async ({
      page,
    }) => {
      await page.goto(HOME_PATH);

      await getMenuButton(page).click();
      await expect(
        page.getByText("새로 생기거나 달라진 점을 모아 둬요"),
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "피드백 보내기" }),
      ).toBeHidden();
      await page.keyboard.press("Escape");

      await page.getByRole("button", { name: "사이드바" }).click();
      await page.getByRole("button", { name: "피드백", exact: true }).click();

      const panel = getFeedbackPanel(page);
      await expect(panel).toBeVisible();
      // 시트는 화면 아래 끝에 붙어요
      const box = await panel.boundingBox();
      expect(box && Math.round(box.y + box.height)).toBe(844);
    });
  });
});
