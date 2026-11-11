import { expect, test } from "@playwright/test";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
test.use({
  permissions: ["microphone"],
  launchOptions: {
    args: [
      "--use-fake-device-for-media-stream",
      "--use-fake-ui-for-media-stream",
    ],
  },
});
const recordingId = recordingDocumentResponse.recordingId;
const detail = `/workspace/1/recordings/${recordingId}/document`;
const scenario = (page: import("@playwright/test").Page, value: string) =>
  page.addInitScript(
    (value) => localStorage.setItem("knot.mock.recordingDemo", value),
    value,
  );

test("[STT-R18, STT-R20] 문서 생성 중이어도 전사가 완료되면 녹음 상세와 전사를 먼저 연다 @core", async ({
  page,
}) => {
  await scenario(page, "processing");
  await page.goto(`/workspace/1/recordings/${recordingId}`);
  await expect(page).toHaveURL(detail);
  await expect(
    page.getByRole("region", { name: "원문", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "이 회의에서 나온 문서" }),
  ).toBeVisible();
});
test("[STT-R39] 같은 녹음의 같은 화자 이름을 멤버 이름으로 변경한다 @core", async ({
  page,
}) => {
  await page.goto(detail);
  const buttons = page.getByRole("button", { name: "참여자 1 이름 바꾸기" });
  await expect(buttons.first()).toBeVisible();
  const count = await buttons.count();
  await buttons.first().click();
  const dialog = page.getByRole("dialog", { name: "이름 바꾸기" });
  await dialog.getByRole("button", { name: /흑곰/ }).click();
  await dialog.getByRole("button", { name: "바꾸기", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "흑곰 이름 바꾸기" }),
  ).toHaveCount(count);
  await expect(buttons).toHaveCount(0);
});
test("[STT-R41, STT-R16] 주제가 없는 인사말 녹음도 전체 요약을 보여 준다 @core", async ({
  page,
}) => {
  await scenario(page, "greeting");
  await page.goto(detail);
  await expect(page.getByText("참석자가 인사를 나눴습니다.")).toBeVisible();
  await expect(
    page.getByText("이 녹음에서 생성된 주제별 문서는 없어요."),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "원문", exact: true }),
  ).toBeVisible();
});
test("[STT-R16, STT-R42] 실제 빈 전사는 녹음 음성을 보존하고 요약과 문서를 만들지 않는다", async ({
  page,
}) => {
  await scenario(page, "empty");
  await page.goto(detail);
  await expect(
    page
      .getByRole("region", { name: "원문", exact: true })
      .getByText("변환된 음성 기록이 없어요."),
  ).toBeVisible();
  await expect(page.getByRole("group", { name: "녹음 음성" })).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "이 회의에서 나온 문서" }),
  ).toHaveCount(0);
});

test.describe("녹음 참가자 수", () => {
  test("[STT-R54] 녹음·일시정지 중 참가자 수를 자동에서 1~10명으로 저장한다 @core", async ({
    page,
  }) => {
    await scenario(page, "participants");
    await page.goto("/workspace/1");
    await page.getByRole("button", { name: "회의 녹음 시작" }).click();
    await expect(page).toHaveURL(/\/recording$/);
    const more = page.getByRole("button", { name: "참가자 수 늘리기" });
    const less = page.getByRole("button", { name: "참가자 수 줄이기" });
    await expect(more).toBeEnabled();
    await expect(less).toBeDisabled();
    await more.click();
    await expect(page.getByText("1명", { exact: true })).toBeVisible();
    await less.click();
    await expect(page.getByText("자동 감지", { exact: true })).toBeVisible();
    await page
      .getByRole("region", { name: "녹음 조작" })
      .getByRole("button", { name: "일시정지", exact: true })
      .click();
    for (let count = 1; count <= 10; count++) {
      await more.click();
      await expect(page.getByText(`${count}명`, { exact: true })).toBeVisible();
    }
    await expect(more).toBeDisabled();
    await expect(less).toBeEnabled();
  });
});
test("[STT-R18] 다른 화면에서는 전사가 준비돼도 이동하지 않는다", async ({
  page,
}) => {
  await scenario(page, "processing");
  await page.goto("/workspace/1");
  await expect(page.getByRole("main")).toBeVisible();
  await page.waitForTimeout(3500);
  await expect(page).toHaveURL("/workspace/1");
});
