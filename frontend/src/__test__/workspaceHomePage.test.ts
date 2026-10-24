import { GetMeResponseDto } from "@api/dto/auth";
import { GetDocumentsResponseDto } from "@api/dto/document";
import {
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
} from "@api/dto/workspace";
import { PostWorkspaceInvitationResponseDto } from "@api/dto/workspaceInvitation";
import { meResponse } from "@api/mock/responses/auth";
import { documentsResponse } from "@api/mock/responses/document";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import { workspaceInvitationResponse } from "@api/mock/responses/workspaceInvitation";
import { expect, test, type Page } from "@playwright/test";

/**
 * 워크스페이스 홈 화면(`/workspace/:workspaceId`) E2E.
 *
 * 홈 진입, 사이드바 열고 닫기, 문서 목록의 폴더 펼침·접힘과 문서 이동, 초대 링크·코드 복사까지
 * 홈 화면 위에서 사용자가 밟는 플로우를 페이지 단위로 확인해요.
 * 회원·워크스페이스·초대·최근 문서·사이드바 문서 목록은 dev 서버의 msw mock 응답(`API_MOCKING`)에서 오므로
 * 기대값도 같은 응답을 DTO로 변환해 가져와요.
 */

const expectedMe = new GetMeResponseDto(meResponse);
const expectedWorkspace = new GetWorkspaceResponseDto(workspaceDetailResponse);
const expectedInvitation = new PostWorkspaceInvitationResponseDto(
  workspaceInvitationResponse,
);
const expectedDocuments = new GetDocumentsResponseDto(documentsResponse);
// 사이드바 문서 목록에서 펼쳐 볼 폴더와 그 폴더의 문서(최근 회의순. 기본 응답은 서버가 준 순서와 같아요)
const [{ topic: FOLDER_NAME }] = expectedDocuments.topics;
const FOLDER_DOCUMENTS = expectedDocuments.items.filter(
  ({ topic }) => topic === FOLDER_NAME,
);

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;

const GREETING = `반가워요, ${expectedMe.nickname} 님`;
// 최근 추가된 문서 카드는 최신순 문서 가운데 앞의 세 개를 보여 줘요
const RECENT_DOCUMENT_TITLES = new GetDocumentsResponseDto(
  documentsResponse,
).items
  .slice(0, 3)
  .map(({ title }) => title);
const INVITE_CODE = expectedInvitation.code;
const DISPLAY_INVITE_LINK = `/invite/${expectedInvitation.linkToken}`;

const getSidebarToggle = (page: Page) =>
  page.getByRole("button", { name: "사이드바" });

const getSidebar = (page: Page) =>
  page.getByRole("complementary", { name: "워크스페이스 사이드바" });

interface GetFolderRowParams {
  page: Page;
  name: string;
}

/** 사이드바의 문서 목록. 폴더 행과 문서 행은 이 안에서 찾아요. */
const getDocumentTree = (page: Page) =>
  getSidebar(page).getByRole("navigation", { name: "문서" });

/** 정규식에서 뜻이 있는 글자를 글자 그대로 맞추게 바꿔요. 폴더 이름은 AI가 분류한 자유로운 글이에요. */
const escapeRegExp = (text: string) =>
  text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** 폴더 행의 접근성 이름은 `이름 + 문서 수`라, 이름 뒤에 숫자만 오는 행을 찾아요. */
const getFolderRow = ({ page, name }: GetFolderRowParams) =>
  getDocumentTree(page).getByRole("button", {
    name: new RegExp(`^${escapeRegExp(name)}\\s*\\d+$`),
  });

interface GetCardParams {
  page: Page;
  title: string;
}

/** 카드 `section`에는 landmark 이름이 없어 제목으로 카드를 찾아요. */
const getCard = ({ page, title }: GetCardParams) =>
  page
    .locator("section")
    .filter({ has: page.getByRole("heading", { name: title }) });

const readClipboard = (page: Page) =>
  page.evaluate(() => navigator.clipboard.readText());

// 클립보드에 쓴 값을 실제로 읽어 확인하기 위해 권한을 미리 허용해요
test.use({ permissions: ["clipboard-read", "clipboard-write"] });

test.beforeEach(async ({ page }) => {
  await page.goto(HOME_PATH);
});

test.describe("홈 진입", () => {
  test("인사말과 카드 세 개를 보여주고 사이드바는 닫혀 있다", async ({
    page,
  }) => {
    await expect(page.getByRole("heading", { name: GREETING })).toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Notion 동기화" }),
    ).toBeHidden();

    const recentDocumentsCard = getCard({ page, title: "최근 추가된 문서" });
    await expect(
      recentDocumentsCard.getByRole("link", { name: "전체 보기" }),
    ).toBeVisible();
    await expect(
      recentDocumentsCard.getByRole("list").getByRole("link"),
    ).toHaveText(
      RECENT_DOCUMENT_TITLES.map((title) => new RegExp(`^${title}`)),
    );

    await expect(getCard({ page, title: "진행 중인 녹음" })).toBeVisible();

    const inviteCard = getCard({ page, title: "팀원 초대" });
    await expect(
      inviteCard.getByRole("textbox", { name: "초대 링크" }),
    ).toHaveValue(DISPLAY_INVITE_LINK);
    await expect(
      inviteCard.getByRole("button", { name: "복사", exact: true }),
    ).toBeVisible();
    await expect(
      inviteCard.getByRole("button", { name: "초대 코드 복사" }),
    ).toBeVisible();

    await expect(getSidebarToggle(page)).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    await expect(getSidebar(page)).toBeHidden();
  });
});

test.describe("사이드바", () => {
  test("GNB 버튼으로 열면 워크스페이스 이름과 문서 목록의 폴더를 보여주고, 다시 누르면 닫힌다", async ({
    page,
  }) => {
    const toggle = getSidebarToggle(page);
    const sidebar = getSidebar(page);

    await toggle.click();

    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(sidebar).toBeVisible();
    await expect(sidebar.getByText(expectedWorkspace.name)).toBeVisible();
    await expect(getDocumentTree(page)).toBeVisible();

    for (const { topic, documentCount } of expectedDocuments.topics) {
      await expect(getFolderRow({ page, name: topic })).toContainText(
        String(documentCount),
      );
    }

    await toggle.click();

    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await expect(sidebar).toBeHidden();
  });

  test("홈에서는 폴더가 모두 접혀 있고, 폴더를 누르면 그 폴더의 문서를 보여주고 다시 누르면 접힌다", async ({
    page,
  }) => {
    await getSidebarToggle(page).click();

    const folderRow = getFolderRow({ page, name: FOLDER_NAME });
    const documentRows = getDocumentTree(page).getByRole("link");

    for (const { topic } of expectedDocuments.topics) {
      await expect(getFolderRow({ page, name: topic })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    }
    await expect(documentRows).toHaveCount(0);

    await folderRow.click();

    await expect(folderRow).toHaveAttribute("aria-expanded", "true");
    await expect(documentRows).toHaveText(
      FOLDER_DOCUMENTS.map(({ title }) => title),
    );

    await folderRow.click();

    await expect(folderRow).toHaveAttribute("aria-expanded", "false");
    await expect(documentRows).toHaveCount(0);
  });

  test("문서 행을 누르면 그 문서 보기로 이동하고, 사이드바에 그 문서가 지금 보는 문서로 표시된다", async ({
    page,
  }) => {
    const [targetDocument] = FOLDER_DOCUMENTS;
    const targetRow = getDocumentTree(page).getByRole("link", {
      name: targetDocument.title,
    });

    await getSidebarToggle(page).click();
    await getFolderRow({ page, name: FOLDER_NAME }).click();
    await targetRow.click();

    await expect(page).toHaveURL(`${HOME_PATH}/documents/${targetDocument.id}`);
    await expect(targetRow).toHaveAttribute("aria-current", "page");
    await expect(getFolderRow({ page, name: FOLDER_NAME })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  test("사이드바를 닫았다 다시 열면 펼쳐 둔 폴더는 다시 접혀 있다", async ({
    page,
  }) => {
    const toggle = getSidebarToggle(page);
    const folderRow = getFolderRow({ page, name: FOLDER_NAME });

    await toggle.click();
    await folderRow.click();
    await expect(folderRow).toHaveAttribute("aria-expanded", "true");

    await toggle.click();
    await expect(getSidebar(page)).toBeHidden();

    await toggle.click();

    await expect(folderRow).toHaveAttribute("aria-expanded", "false");
  });
});

test.describe("팀원 초대 카드", () => {
  test("복사를 누르면 전체 초대 링크가 클립보드에 담기고 복사됨을 잠시 보여준 뒤 돌아온다", async ({
    page,
  }) => {
    const inviteCard = getCard({ page, title: "팀원 초대" });
    const copyLinkButton = inviteCard.getByRole("button", {
      name: "복사",
      exact: true,
    });

    await copyLinkButton.click();

    await expect(
      inviteCard.getByRole("button", { name: "복사됨", exact: true }),
    ).toBeVisible();
    await expect(copyLinkButton).toBeHidden();
    const origin = await page.evaluate(() => window.location.origin);
    expect(await readClipboard(page)).toBe(`${origin}${DISPLAY_INVITE_LINK}`);

    // 2초 뒤 원래 `복사`로 돌아와요
    await expect(copyLinkButton).toBeVisible();
    await expect(
      inviteCard.getByRole("button", { name: "복사됨", exact: true }),
    ).toBeHidden();
  });

  test("초대 코드 복사를 누르면 6자 코드가 클립보드에 담기고 글자가 복사됨으로 바뀌었다 돌아온다", async ({
    page,
  }) => {
    const inviteCard = getCard({ page, title: "팀원 초대" });
    const copyCodeButton = inviteCard.getByRole("button", {
      name: "초대 코드 복사",
    });

    await copyCodeButton.click();

    await expect(
      inviteCard.getByRole("button", { name: "복사됨", exact: true }),
    ).toBeVisible();
    // 링크 쪽 `복사` 버튼은 그대로예요
    await expect(
      inviteCard.getByRole("button", { name: "복사", exact: true }),
    ).toBeVisible();
    expect(await readClipboard(page)).toBe(INVITE_CODE);

    // 2초 뒤 원래 `초대 코드 복사`로 돌아와요
    await expect(copyCodeButton).toBeVisible();
  });
});

test.describe("홈 녹음 실패 카드", () => {
  const getRecordingCard = (page: Page) =>
    page.getByRole("region", { name: "진행 중인 녹음" });

  for (const { scenario, title } of [
    { scenario: "transcriptionFailed", title: "녹음을 글로 옮기지 못했어요" },
    { scenario: "documentFailed", title: "문서를 만들지 못했어요" },
  ]) {
    test(`[HOME-024-06] ${scenario}: 실패를 표시하고 재시도 접수 후 처리 중으로 돌아간다 @core`, async ({
      page,
    }) => {
      await page.goto(`${HOME_PATH}?mock.currentRecording=${scenario}`);
      const card = getRecordingCard(page);
      await expect(card.getByText(title, { exact: true })).toBeVisible();
      await expect(
        page.getByRole("button", { name: "회의 녹음 시작" }),
      ).toBeVisible();

      await expect(
        card.getByText("다시 시도할 수 있어요", { exact: true }),
      ).toBeVisible();
      await expect(card.getByText(/보관해요/)).toHaveCount(0);
      await card.getByRole("button", { name: "다시 시도" }).click();

      await expect(
        card.getByText("문서 정리 중", { exact: true }),
      ).toBeVisible();
      await expect(card.getByRole("button", { name: "보기" })).toBeVisible();
    });

    test(`[HOME-024-06] ${scenario}: 재시도 불가 실패도 제목을 유지하고 버튼·보관 안내를 숨긴다`, async ({
      page,
    }) => {
      await page.goto(
        `${HOME_PATH}?mock.currentRecording=${scenario}NotRetryable`,
      );
      const card = getRecordingCard(page);

      await expect(card.getByText(title, { exact: true })).toBeVisible();
      await expect(card.getByRole("button", { name: "다시 시도" })).toHaveCount(
        0,
      );
      await expect(
        card.getByText("다시 시도할 수 있어요", { exact: true }),
      ).toHaveCount(0);
      await expect(
        card.getByText("진행 중인 녹음이 없어요", { exact: true }),
      ).toHaveCount(0);
    });
  }

  test("[HOME-024-03] 처리 완료 후 이전 실패가 다시 선택되면 빈 카드 대신 실패를 표시한다", async ({
    page,
  }) => {
    await page.goto(`${HOME_PATH}?mock.currentRecording=processing`);
    const card = getRecordingCard(page);
    await expect(card.getByText("문서 정리 중", { exact: true })).toBeVisible();
    await page.evaluate(() =>
      localStorage.setItem(
        "knot.mock.scenario",
        JSON.stringify({ currentRecording: "transcriptionFailedNotRetryable" }),
      ),
    );

    await expect(
      card.getByText("녹음을 글로 옮기지 못했어요", { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(card.getByRole("button", { name: "다시 시도" })).toHaveCount(
      0,
    );
  });
});
