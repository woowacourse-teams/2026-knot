import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { expect, test, type Page } from "@playwright/test";

/**
 * GNB 「소식과 피드백」 메뉴와 피드백 보내기 E2E.
 *
 * 워크스페이스 화면 어디서나 GNB 오른쪽 버튼으로 새 소식과 피드백 보내기 메뉴를 열고,
 * 피드백 패널에서 유형을 골라 보내는 플로우를 확인해요. 휴대폰에서는 사이드바 맨 아래 「피드백」으로 시트를 열어요.
 * 피드백 저장(`/relay/f`)은 dev 서버의 msw mock이 받은 것처럼만 응답해요(`API_MOCKING`).
 */

const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;

const getMenuButton = (page: Page) =>
  page.getByRole("button", { name: "새 소식과 피드백" });

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

    await expect(page.getByRole("button", { name: "새 소식", exact: true })).toBeVisible();
    await expect(
      page.getByRole("button", { name: "피드백 보내기" }),
    ).toBeVisible();

    await page.getByRole("button", { name: "새 소식", exact: true }).click();

    await expect(
      page.getByText("새로 생기거나 달라진 점을 모아 둬요"),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "피드백 보내기" }),
    ).toBeHidden();
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
