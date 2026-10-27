import { GetDocumentRecordingsResponseDto } from "@api/dto/document";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { documentRecordingsResponse } from "@api/mock/responses/document";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";

/**
 * 문서 탭 화면(`/workspace/:workspaceId/documents/recordings`, `…/documents/topics`) E2E.
 *
 * 「녹음별」 · 「주제별」 두 탭의 전환과 기본 탭, 「녹음별」 탭의 녹음 묶음 · 문서 항목 · 이동을 확인해요(DOC-R9 · DOC-R20~R24).
 * 워크스페이스 · 문서 조회는 dev 서버의 msw mock이 답해요(`API_MOCKING`).
 * dev 서버 mock은 E2E에서 응답을 바꿀 수 없어서 빈 상태 · 불러오기 실패 · 다음 20개 이어 받기는 여기서 보지 않고
 * 통합 테스트(`RecordingDocumentList/test.tsx`)와 스토리북에서 봐요.
 */

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
const DOCUMENTS_PATH = `${HOME_PATH}/documents`;
const RECORDINGS_TAB_PATH = `${DOCUMENTS_PATH}/recordings`;
const TOPICS_TAB_PATH = `${DOCUMENTS_PATH}/topics`;
const NAV_LABEL = "워크스페이스 화면 이동";

const expectedRecordings = new GetDocumentRecordingsResponseDto(
  documentRecordingsResponse,
).items;

const getTabList = (page: Page) =>
  page.getByRole("tablist", { name: "문서 보기 방식" });

const getTab = (page: Page, name: string) =>
  getTabList(page).getByRole("tab", { name });

const getGnbTab = (page: Page, name: string) =>
  page
    .getByRole("navigation", { name: NAV_LABEL })
    .getByRole("button", { name });

const getSidebarToggle = (page: Page) =>
  page.getByRole("button", { name: "사이드바" });

/** 사이드바의 주제 폴더 목록 */
const getSidebarDocumentTree = (page: Page) =>
  page
    .getByRole("complementary", { name: "워크스페이스 사이드바" })
    .getByRole("navigation", { name: "문서" });

/** 탭이 가리키는 영역. 녹음 묶음은 이 안에서 제목이 이름인 영역이에요(섹션 자체도 영역이라 안에서 찾아요) */
const getTabPanel = (page: Page) =>
  page.getByRole("main").getByRole("tabpanel");

const getRecordingGroup = (page: Page, title: string) =>
  getTabPanel(page).getByRole("region", { name: title });

test.describe("문서 탭 전환", () => {
  test("[DOC-R20] GNB 문서를 누르면 「녹음별」 탭이 열리고, 「녹음별」 · 「주제별」 순서로 탭이 있다", async ({
    page,
  }) => {
    await page.goto(HOME_PATH);
    await getGnbTab(page, "문서").click();

    await expect(page).toHaveURL(RECORDINGS_TAB_PATH);
    await expect(getTabList(page).getByRole("tab")).toHaveText([
      "녹음별",
      "주제별",
    ]);
    await expect(getTab(page, "녹음별")).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  test("[DOC-R20] 「주제별」 탭을 누르면 주제별 주소로 가고 주제 폴더 목록이 보이며, 「녹음별」을 누르면 돌아온다", async ({
    page,
  }) => {
    await page.goto(RECORDINGS_TAB_PATH);

    await getTab(page, "주제별").click();

    await expect(page).toHaveURL(TOPICS_TAB_PATH);
    await expect(getTab(page, "주제별")).toHaveAttribute(
      "aria-selected",
      "true",
    );
    // 주제별 목록은 폴더(주제) 이름이 제목인 영역이에요
    await expect(
      page.getByRole("main").getByRole("heading", { level: 3 }).first(),
    ).toBeVisible();

    await getTab(page, "녹음별").click();

    await expect(page).toHaveURL(RECORDINGS_TAB_PATH);
    await expect(
      getRecordingGroup(page, expectedRecordings[0].title),
    ).toBeVisible();
  });

  test("[DOC-R9] 「주제별」 탭을 보다가 홈에 갔다 문서를 다시 누르면 「녹음별」 탭이 열린다", async ({
    page,
  }) => {
    await page.goto(TOPICS_TAB_PATH);
    await expect(getTab(page, "주제별")).toHaveAttribute(
      "aria-selected",
      "true",
    );

    await getGnbTab(page, "홈").click();
    await expect(page).toHaveURL(HOME_PATH);

    await getGnbTab(page, "문서").click();

    await expect(page).toHaveURL(RECORDINGS_TAB_PATH);
    await expect(getTab(page, "녹음별")).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  test("[DOC-R9] 「주제별」 탭에서 GNB 문서를 누르면 「녹음별」 탭으로 간다", async ({
    page,
  }) => {
    await page.goto(TOPICS_TAB_PATH);

    await getGnbTab(page, "문서").click();

    await expect(page).toHaveURL(RECORDINGS_TAB_PATH);
  });

  test("탭이 없는 문서 주소로 들어오면 「녹음별」 탭으로 보낸다", async ({
    page,
  }) => {
    await page.goto(DOCUMENTS_PATH);

    await expect(page).toHaveURL(RECORDINGS_TAB_PATH);
  });

  test("[DOC-R20] 탭을 바꿔도 사이드바의 주제 목록은 그대로다", async ({
    page,
  }) => {
    await page.goto(RECORDINGS_TAB_PATH);
    await getSidebarToggle(page).click();
    const tree = getSidebarDocumentTree(page);
    await expect(tree).toBeVisible();
    const folderNames = await tree.getByRole("button").allTextContents();

    await getTab(page, "주제별").click();
    await expect(page).toHaveURL(TOPICS_TAB_PATH);

    await expect(tree).toBeVisible();
    expect(await tree.getByRole("button").allTextContents()).toEqual(
      folderNames,
    );
  });
});

test.describe("녹음별 탭", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(RECORDINGS_TAB_PATH);
  });

  test("[DOC-R21, DOC-R24] 문서가 나온 녹음이 녹음 시작 최신순으로 모두 보인다", async ({
    page,
  }) => {
    const groups = getTabPanel(page).getByRole("region");

    await expect(groups).toHaveCount(expectedRecordings.length);
    for (const [index, { title }] of expectedRecordings.entries()) {
      await expect(groups.nth(index)).toHaveAccessibleName(title);
    }
  });

  test("[DOC-R22] 녹음 행에 표시 제목 · 녹음 시작 날짜 · 녹음 길이만 보이고 문서 수는 보이지 않으며, AI 제목이 없는 녹음은 「OO 님의 녹음」으로 보인다", async ({
    page,
  }) => {
    for (const recording of expectedRecordings) {
      const group = getRecordingGroup(page, recording.title);

      // 녹음 제목과 같은 제목의 문서가 있을 수 있어, 녹음 행(「녹음 보기」가 있는 링크)에서 제목을 봐요
      await expect(
        group.getByRole("link", { name: /녹음 보기/ }),
      ).toContainText(recording.title);
      await expect(
        group.getByText(formatDate(recording.startedAt)),
      ).toBeVisible();
      await expect(
        group.getByText(
          formatDurationFromSeconds(recording.recordingDurationSeconds),
        ),
      ).toBeVisible();
      // 그 녹음의 문서는 녹음 행 아래 문서 항목으로 보여요
      await expect(group.getByText(/^문서 \d+$/)).toHaveCount(0);
    }

    const fallbackTitled = expectedRecordings.find(({ title }) =>
      title.endsWith(" 님의 녹음"),
    );

    expect(fallbackTitled).toBeDefined();
  });

  test("[DOC-R23] 녹음 안 문서 항목은 제목과 한 줄 요약만, 서버가 준 순서로 모두 보인다", async ({
    page,
  }) => {
    for (const recording of expectedRecordings) {
      const rows = getRecordingGroup(page, recording.title).getByRole(
        "listitem",
      );

      await expect(rows).toHaveCount(recording.documents.length);
      for (const [index, document] of recording.documents.entries()) {
        await expect(rows.nth(index)).toContainText(document.title);
        if (document.summary !== null) {
          await expect(rows.nth(index)).toContainText(document.summary);
        }
      }
    }
  });

  test("[DOC-R23] 문서 항목을 누르면 그 문서의 문서 보기가 열린다", async ({
    page,
  }) => {
    const [recording] = expectedRecordings;
    const [document] = recording.documents;

    await getRecordingGroup(page, recording.title)
      .getByRole("listitem")
      .first()
      .getByRole("link")
      .click();

    await expect(page).toHaveURL(`${DOCUMENTS_PATH}/${document.id}`);
  });

  test("[DOC-R22] 녹음 행을 누르면 그 녹음의 녹음 문서 주소로 간다", async ({
    page,
  }) => {
    const [recording] = expectedRecordings;

    await getRecordingGroup(page, recording.title)
      .getByRole("link", { name: /녹음 보기/ })
      .click();

    await expect(page).toHaveURL(
      `${HOME_PATH}/recordings/${recording.recordingId}/document`,
    );
  });
});
