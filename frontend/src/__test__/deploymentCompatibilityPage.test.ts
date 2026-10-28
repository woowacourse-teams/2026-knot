import { GetDocumentRecordingsResponseDto, GetDocumentResponseDto } from "@api/dto/document";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { documentDetailsResponse, documentRecordingsResponse } from "@api/mock/responses/document";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";

const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse).workspaces[0].id;
const expectedDocument = new GetDocumentResponseDto(documentDetailsResponse[0]);
const expectedRecordings = new GetDocumentRecordingsResponseDto(documentRecordingsResponse).items;
const expectedRecording = expectedRecordings.find(
  ({ recordingId }) => recordingId === expectedDocument.recordingSessionId,
)!;
const HOME = `/workspace/${WORKSPACE_ID}`;
const LIST = `${HOME}/documents/recordings`;
const DOCUMENT = `${HOME}/documents/${expectedDocument.id}`;
const RECORDING = `${HOME}/recordings/${expectedRecording.recordingId}/document`;
const STORAGE_KEY = "knot.mock.deploymentCompatibility";

interface SetScenarioParams { page: Page; scenario: string; }

const setScenario = ({ page, scenario }: SetScenarioParams) => page.evaluate(
  ({ key, value }) => localStorage.setItem(key, value),
  { key: STORAGE_KEY, value: scenario },
);

/** 열린 앱을 다시 보았을 때의 조회를 발생시켜요. 앱이나 쿼리 캐시는 새로 만들지 않아요. */
const returnToTab = (page: Page) => page.evaluate(() => {
  const descriptor = Object.getOwnPropertyDescriptor(document, "visibilityState");
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
  window.dispatchEvent(new Event("visibilitychange"));
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
  window.dispatchEvent(new Event("visibilitychange"));
  if (descriptor) Object.defineProperty(document, "visibilityState", descriptor);
  else Reflect.deleteProperty(document, "visibilityState");
});

const navigation = (page: Page) => page.getByRole("navigation", { name: "워크스페이스 화면 이동" });
const sourceCard = (page: Page) => page.getByRole("region", { name: "이 문서가 나온 녹음" });

test("[ERR-QA-003] 실패 상태여도 재시도 허용 필드가 없으면 재시도하지 않고 조회 링크를 유지한다", async ({ page }) => {
  await page.addInitScript(({ key }) => localStorage.setItem(key, "missing-retryable"), { key: STORAGE_KEY });
  await page.goto(HOME);
  await expect(page.getByText("녹음을 글로 옮기지 못했어요").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 시도" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "녹음 보기" }).first()).toHaveAttribute("href", /recordings/);
});

test("[ERR-QA-005, ERR-QA-006] 만료된 재생 정보가 캐시에 있어도 롤백 확인 후 이전 음성을 새로 재생하지 않는다", async ({ page }) => {
  await page.addInitScript(({ key }) => localStorage.setItem(key, "expired-audio"), { key: STORAGE_KEY });
  await page.goto(RECORDING);
  const player = page.getByRole("group", { name: "녹음 음성" });
  await expect(player.getByRole("button", { name: "재생" })).toBeVisible();
  await setScenario({ page, scenario: "audio" });
  await player.getByRole("button", { name: "재생" }).click();
  await expect(player.getByText("업데이트 준비 중")).toBeVisible();
  await expect(player.locator("audio")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: expectedRecording.title, exact: true })).toBeVisible();
  await setScenario({ page, scenario: "normal" });
  await player.getByRole("button", { name: "다시 확인" }).click();
  await expect(player.getByRole("button", { name: "재생" })).toBeVisible();
});

test("[ERR-QA-001, ERR-QA-002, DOC-R24, DOC-R25] 이전 서버 응답에서도 홈과 기존 문서 조회, 확인, 복사, 원문을 이용한다", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.addInitScript(({ key }) => localStorage.setItem(key, "legacy"), { key: STORAGE_KEY });
  await page.goto(HOME);
  await expect(page.getByRole("heading", { name: /반가워요/ })).toBeVisible();
  await expect(page.getByText("처리 상태를 확인할 수 없어요").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "팀원 초대" })).toBeVisible();
  await navigation(page).getByRole("button", { name: "문서", exact: true }).click();
  await expect(page).toHaveURL(LIST);
  await expect(page.getByText("업데이트 준비 중", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "주제별" }).click();
  await expect(page.getByRole("main").getByRole("heading", { level: 3 }).first()).toBeVisible();
  // 기존 문서 URL 진입도 지원해야 해요. 새 기능 진입을 거치지 않아요.
  await page.goto(DOCUMENT);
  await expect(page.getByRole("heading", { name: expectedDocument.title, exact: true })).toBeVisible();
  await expect(sourceCard(page).getByText("업데이트 준비 중")).toBeVisible();
  await expect(sourceCard(page).getByRole("link")).toHaveCount(0);
  await page.getByRole("button", { name: "문서를 확인했어요", exact: true }).click();
  await expect(page.getByRole("button", { name: "확인했어요", exact: true })).toBeVisible();
  await page.getByRole("main").getByRole("button", { name: "복사", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain(expectedDocument.title);
  await page.getByRole("button", { name: "원문 닫기", exact: true }).click();
  await page.getByRole("button", { name: "원문 보기", exact: true }).click();
  await expect(page.getByRole("heading", { name: "원문", exact: true })).toBeVisible();
  await expect(page.getByText(/참여자 1:/).first()).toBeVisible();
  await expect(page.getByText("잠시 문제가 생겼어요")).toHaveCount(0);
});

test("[ERR-QA-002, ERR-QA-006] 열린 홈이 정상, 미지 상태, 이전 응답, 복구 순서로 바뀌어도 오류 화면 없이 유지된다", async ({ page }) => {
  await page.goto(HOME);
  const viewLinks = page.getByRole("link", { name: "녹음 보기" });
  const activeViewLinks = page.locator("a").filter({ hasText: "녹음 보기" });
  await expect(activeViewLinks.first()).toHaveAttribute("href", /recordings/);
  for (const scenario of ["unknown", "legacy"]) {
    await setScenario({ page, scenario: scenario });
    await returnToTab(page);
    await expect(page.getByText("처리 상태를 확인할 수 없어요").first()).toBeVisible();
    await expect(viewLinks.first()).toHaveAttribute("aria-disabled", "true");
    await expect(page.getByRole("heading", { name: "팀원 초대" })).toBeVisible();
  }
  await setScenario({ page, scenario: "normal" });
  await returnToTab(page);
  await expect(activeViewLinks.first()).toHaveAttribute("href", /recordings/);
  await expect(page.getByText("처리 상태를 확인할 수 없어요")).toHaveCount(0);
});

test("[ERR-QA-005, ERR-QA-006, DOC-R24] 캐시된 녹음별 목록은 롤백 시 준비 안내로 바뀌고 다시 확인하면 복구된다", async ({ page }) => {
  await page.goto(LIST);
  const firstGroup = page.getByRole("main").getByRole("region", { name: expectedRecordings[0].title });
  await expect(firstGroup).toBeVisible();
  await setScenario({ page, scenario: "list" });
  await returnToTab(page);
  await expect(page.getByText("업데이트 준비 중", { exact: true })).toBeVisible();
  await expect(firstGroup).toHaveCount(0);
  await expect(page).toHaveURL(LIST);
  await setScenario({ page, scenario: "list-error" });
  await page.getByRole("button", { name: "다시 확인" }).click();
  await expect(page.getByRole("alert")).toContainText("목록을 불러오지 못했어요");
  await expect(firstGroup).toHaveCount(0);
  // 화면을 다시 열어도 성공하지 못한 캐시로 동작을 허용하지 않아요.
  await navigation(page).getByRole("button", { name: "홈", exact: true }).click();
  await navigation(page).getByRole("button", { name: "문서", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("목록을 불러오지 못했어요");
  await expect(firstGroup).toHaveCount(0);
  await setScenario({ page, scenario: "normal" });
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(firstGroup).toBeVisible();
});

test("[ERR-QA-005, ERR-QA-006, DOC-R25] 캐시된 출처 링크만 롤백 시 제한되고 기존 문서는 유지된다", async ({ page }) => {
  await page.goto(DOCUMENT);
  await expect(sourceCard(page).getByRole("link")).toContainText(expectedRecording.title);
  await setScenario({ page, scenario: "document" });
  await returnToTab(page);
  await expect(sourceCard(page).getByText("업데이트 준비 중")).toBeVisible();
  await expect(sourceCard(page).getByRole("link")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: expectedDocument.title, exact: true })).toBeVisible();
  await setScenario({ page, scenario: "normal" });
  await sourceCard(page).getByRole("button", { name: "다시 확인" }).click();
  await expect(sourceCard(page).getByRole("link")).toContainText(expectedRecording.title);
});

test("[ERR-QA-004, ERR-QA-005, ERR-QA-006] 녹음 문서 조회가 롤백되면 캐시된 요약을 제한하고 다시 확인하면 복구된다", async ({ page }) => {
  await page.goto(RECORDING);
  const title = page.getByRole("heading", { name: expectedRecording.title, exact: true });
  await expect(title).toBeVisible();
  await setScenario({ page, scenario: "document" });
  await returnToTab(page);
  await expect(page.getByText("업데이트 준비 중", { exact: true })).toBeVisible();
  await expect(title).toHaveCount(0);
  await setScenario({ page, scenario: "normal" });
  await page.getByRole("button", { name: "다시 확인" }).click();
  await expect(title).toBeVisible();
});

test("[ERR-QA-004, ERR-QA-005, ERR-QA-006] 신규 원문만 롤백되면 요약은 유지되고 원문은 다시 확인 후 복구된다", async ({ page }) => {
  await page.goto(RECORDING);
  await expect(page.getByText(/참여자 1:/).first()).toBeVisible();
  await setScenario({ page, scenario: "transcript" });
  await returnToTab(page);
  await expect(page.getByText("업데이트 준비 중", { exact: true })).toBeVisible();
  await expect(page.getByText(/참여자 1:/)).toHaveCount(0);
  await expect(page.getByRole("heading", { name: expectedRecording.title, exact: true })).toBeVisible();
  await setScenario({ page, scenario: "normal" });
  await page.getByRole("button", { name: "다시 확인" }).click();
  await expect(page.getByText(/참여자 1:/).first()).toBeVisible();
});

test("[ERR-QA-004, ERR-QA-006] 음성 API만 미지원이면 요약과 원문을 읽고 음성 지원 복구 후 재생할 수 있다", async ({ page }) => {
  await page.addInitScript(({ key }) => localStorage.setItem(key, "audio"), { key: STORAGE_KEY });
  await page.goto(RECORDING);
  const player = page.getByRole("group", { name: "녹음 음성" });
  await expect(player.getByText("업데이트 준비 중")).toBeVisible();
  await expect(player.getByRole("button", { name: "재생" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: expectedRecording.title, exact: true })).toBeVisible();
  await expect(page.getByText(/참여자 1:/).first()).toBeVisible();
  await setScenario({ page, scenario: "normal" });
  await player.getByRole("button", { name: "다시 확인" }).click();
  await expect(player.getByRole("button", { name: "재생" })).toBeVisible();
});
