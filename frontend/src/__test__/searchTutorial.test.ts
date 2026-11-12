import { GetMeResponseDto } from "@api/dto/auth";
import { GetRecordingDocumentResponseDto } from "@api/dto/recordingDocument";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { meResponse } from "@api/mock/responses/auth";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";
import { workspacesResponse } from "@api/mock/responses/workspace";
import {
  SEARCH_TUTORIAL_MOCK_CONVERSATION,
  SEARCH_TUTORIAL_QUESTION_KINDS,
} from "@constants/searchTutorial";
import { expect, test, type Page } from "@playwright/test";

/**
 * 탐색 튜토리얼 E2E. 문서가 있는 워크스페이스에 처음 들어온 멤버가 탐색이 무엇이고 언제 쓰는지 겪는 흐름을 확인해요.
 *
 * 다른 E2E는 튜토리얼을 이미 끝낸 멤버로 시작해요(playwright.config.ts). 이 파일만 그 기록을 지우고 시작해요.
 * 회원, 워크스페이스, 문서는 dev 서버의 msw mock 응답에서 오고, 예시 대화는 서버에 보내지 않는 목 데이터예요.
 */

const { memberId } = new GetMeResponseDto(meResponse);
const RECORD_KEY = `knot.searchTutorial.${memberId}`;
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse).workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
const CHAT_PATH = `/workspace/${WORKSPACE_ID}/chat`;
const { recordingId } = new GetRecordingDocumentResponseDto(recordingDocumentResponse);
const RECORDING_DOCUMENT_PATH = `/workspace/${WORKSPACE_ID}/recordings/${recordingId}/document`;

const getCoach = (page: Page) => page.getByRole("dialog");

/** 이 페이지를 처음 여는 순간에만 튜토리얼 기록을 지워요. 앱 안에서 옮겨 다니는 동안 남긴 기록은 그대로 둬요 */
const startAsNewMember = (page: Page) =>
  page.addInitScript((key) => {
    if (sessionStorage.getItem("e2e.searchTutorial.cleared")) return;
    sessionStorage.setItem("e2e.searchTutorial.cleared", "1");
    localStorage.removeItem(key);
  }, RECORD_KEY);

test.describe("탐색 튜토리얼", () => {
  test("[SEARCH-R18, SEARCH-R24, SEARCH-R25, SEARCH-R26, SEARCH-R27, SEARCH-R28] 처음 들어온 멤버는 홈에서 탐색을 밝힌 안내를 보고, 예시 질문과 답변을 거쳐 직접 질문을 보낸다", async ({ page }) => {
    await startAsNewMember(page);
    await page.goto(HOME_PATH);

    // 1단계: 탐색 탭과 독 채팅 버튼을 밝힌 안내
    await expect(getCoach(page)).toContainText("1 / 4");
    await expect(page.getByText("독의 채팅 버튼으로도 열 수 있어요")).toBeVisible();
    await getCoach(page).getByRole("button", { name: "보여 주세요" }).click();

    // 2단계: 탐색으로 옮겨 예시 질문을 입력창에 넣어요
    await expect(page).toHaveURL(CHAT_PATH);
    await expect(page.getByText("예시 대화라 대화 목록에 남지 않아요")).toBeVisible();

    // 3단계: 예시 답변을 다 보여 준 뒤 다음으로 넘어가요
    await expect(page.getByText(SEARCH_TUTORIAL_MOCK_CONVERSATION.question)).toBeVisible();
    await expect(getCoach(page)).toContainText("3 / 4", { timeout: 10_000 });
    await expect(page.getByText(SEARCH_TUTORIAL_MOCK_CONVERSATION.answer)).toBeVisible();
    await getCoach(page).getByRole("button", { name: "다음" }).click();

    // 4단계: 질문 종류 카드를 누르면 실제 질문으로 보내고 튜토리얼이 끝나요
    const [kind] = SEARCH_TUTORIAL_QUESTION_KINDS;
    await page.getByRole("list", { name: "이렇게 물어볼 수 있어요" }).getByRole("button", { name: new RegExp(kind.question) }).click();

    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);
    await expect(page.getByText(kind.question).first()).toBeVisible();
  });

  test("[SEARCH-R20] 녹음 문서 화면에서는 바로 띄우지 않고 문서를 살짝 확인한 뒤 문서 화면용 제목으로 띄운다", async ({ page }) => {
    await startAsNewMember(page);
    await page.goto(RECORDING_DOCUMENT_PATH);

    await page.waitForTimeout(1500);
    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);
    await expect(getCoach(page)).toContainText("궁금한 게 생기면 탐색에 물어보세요", { timeout: 10_000 });
  });

  test("[SEARCH-R21] 다음에 볼게요를 누르면 같은 날 다시 들어와도 띄우지 않는다", async ({ page }) => {
    await startAsNewMember(page);
    await page.goto(HOME_PATH);

    await getCoach(page).getByRole("button", { name: "다음에 볼게요" }).click();
    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);

    await page.reload();
    await expect(page.getByRole("button", { name: "탐색", exact: true })).toBeVisible();
    await page.waitForTimeout(1500);
    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);
  });

  test("[SEARCH-R21] 2단계에서 건너뛰면 끝내고 다시 띄우지 않는다", async ({ page }) => {
    await startAsNewMember(page);
    await page.goto(HOME_PATH);

    await getCoach(page).getByRole("button", { name: "보여 주세요" }).click();
    await page.getByRole("button", { name: "건너뛰기" }).click();
    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);

    await page.goto(HOME_PATH);
    await page.waitForTimeout(1500);
    await expect(page.getByTestId("search-tutorial")).toHaveCount(0);
  });

  test("[SEARCH-R22] 이미 끝낸 멤버도 탐색 빈 대화의 탐색 사용법 보기로 다시 볼 수 있다", async ({ page }) => {
    await page.goto(CHAT_PATH);

    await page.getByRole("button", { name: "탐색 사용법 보기" }).click();

    await expect(getCoach(page)).toContainText("1 / 4");
  });
});
