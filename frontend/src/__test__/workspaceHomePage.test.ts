import { GetMeResponseDto } from "@api/dto/auth";
import { GetDocumentsResponseDto } from "@api/dto/document";
import { GetRecordingsResponseDto } from "@api/dto/recording";
import {
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
} from "@api/dto/workspace";
import { PostWorkspaceInvitationResponseDto } from "@api/dto/workspaceInvitation";
import { meResponse } from "@api/mock/responses/auth";
import { documentsResponse } from "@api/mock/responses/document";
import { recordingsResponse } from "@api/mock/responses/recording";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import { workspaceInvitationResponse } from "@api/mock/responses/workspaceInvitation";
import { expect, test, type Page } from "@playwright/test";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";

/**
 * 워크스페이스 홈 화면(`/workspace/:workspaceId`) E2E.
 *
 * 홈 진입, 사이드바 열고 닫기, 문서 목록의 폴더 펼침·접힘과 문서 이동, 초대 링크·코드 복사까지
 * 홈 화면 위에서 사용자가 밟는 플로우를 페이지 단위로 확인해요.
 * 회원·워크스페이스·초대·녹음 기록·사이드바 문서 목록은 dev 서버의 msw mock 응답(`API_MOCKING`)에서 오므로
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

/** 홈 왼쪽 위의 「녹음 기록」 카드 */
const getRecordingHistoryCard = (page: Page) =>
  page.getByRole("region", { name: "녹음 기록" });

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

    await expect(getRecordingHistoryCard(page)).toBeVisible();
    await expect(getCard({ page, title: "확인이 필요한 문서" })).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "최근 추가된 문서" }),
    ).toBeHidden();

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

test.describe("홈 녹음 기록 카드", () => {
  // dev 서버의 녹음 기록 mock은 20개씩 잘라 주므로 첫 묶음은 앞의 20개예요
  const PAGE_SIZE = 20;
  const expectedRecordings = new GetRecordingsResponseDto(recordingsResponse)
    .items;
  const firstPage = expectedRecordings.slice(0, PAGE_SIZE);
  const findRecording = (
    predicate: (recording: (typeof expectedRecordings)[number]) => boolean,
  ) => expectedRecordings.find(predicate)!;
  const COMPLETED = findRecording(
    ({ processingStatus }) => processingStatus === "COMPLETED",
  );
  const PROCESSING = findRecording(
    ({ processingStatus }) => processingStatus === "PROCESSING",
  );
  const TRANSCRIPTION_FAILED = findRecording(
    ({ processingStatus }) => processingStatus === "TRANSCRIPTION_FAILED",
  );
  const DOCUMENT_FAILED_RETRYABLE = findRecording(
    ({ processingStatus, isRetryable }) =>
      processingStatus === "DOCUMENT_FAILED" && isRetryable,
  );
  const DOCUMENT_FAILED_NOT_RETRYABLE = findRecording(
    ({ processingStatus, isRetryable }) =>
      processingStatus === "DOCUMENT_FAILED" && !isRetryable,
  );
  const NO_CONTENT = findRecording(
    ({ processingStatus }) => processingStatus === "NO_CONTENT",
  );

  /** 그 녹음의 행. 행은 서버가 준 순서 그대로예요 */
  const getRow = (page: Page, recordingId: number) =>
    getRecordingHistoryCard(page)
      .getByRole("listitem")
      .nth(
        firstPage.findIndex(
          (recording) => recording.recordingId === recordingId,
        ),
      );

  /** 카드 아래 끝이 화면에 들어오게 한 뒤 카드 안 목록을 끝까지 내려요. 사람이 휠로 내리면 페이지도 이어서 내려가요 */
  const scrollListToEnd = (page: Page) =>
    getRecordingHistoryCard(page)
      .getByRole("list")
      .evaluate((list) => {
        const scrollArea = list.parentElement!;
        scrollArea.closest("section")!.scrollIntoView({ block: "end" });
        scrollArea.scrollTo({ top: scrollArea.scrollHeight });
      });

  test("[STT-R32, STT-R33] 행은 서버가 준 최신순이고, 완료 행은 날짜와 길이 칩, 그 밖의 행은 처리 상태를 보여 준다 @core", async ({
    page,
  }) => {
    const card = getRecordingHistoryCard(page);

    await expect(card.getByRole("listitem")).toHaveCount(firstPage.length);
    for (const [index, { title }] of firstPage.entries()) {
      await expect(
        card.getByRole("listitem").nth(index).getByTitle(title),
      ).toBeVisible();
    }

    const completedRow = getRow(page, COMPLETED.recordingId);
    await expect(completedRow).toContainText(formatDate(COMPLETED.startedAt));
    await expect(completedRow).toContainText(
      formatDurationFromSeconds(COMPLETED.recordingDurationSeconds),
    );
    await expect(getRow(page, PROCESSING.recordingId)).not.toContainText(
      formatDate(PROCESSING.startedAt),
    );
    await expect(card.getByRole("link", { name: "전체 보기" })).toHaveCount(0);
  });

  test("[STT-R32, STT-R33] 목록을 끝까지 내려도 카드 제목과 맨 위 줄은 그대로 보인다", async ({
    page,
  }) => {
    await page.goto(`${HOME_PATH}?mock.currentRecording=recording`);
    const card = getRecordingHistoryCard(page);
    await expect(card.getByRole("listitem").first()).toBeVisible();

    await scrollListToEnd(page);

    await expect(
      card.getByRole("heading", { name: "녹음 기록" }),
    ).toBeInViewport();
    await expect(
      card.getByRole("button", { name: "녹음 화면으로" }),
    ).toBeInViewport();
  });

  test("[STT-R51] 행마다 녹음 문서와 같은 처리 상태를 보여 준다 @core", async ({
    page,
  }) => {
    for (const [recording, status] of [
      [PROCESSING, "문서 정리 중"],
      [TRANSCRIPTION_FAILED, "녹음을 글로 옮기지 못했어요"],
      [DOCUMENT_FAILED_RETRYABLE, "문서를 만들지 못했어요"],
      [NO_CONTENT, "변환된 음성 기록이 없어요."],
    ] as const) {
      await expect(getRow(page, recording.recordingId)).toContainText(status);
    }
  });

  test("[STT-R51, STT-R34] 다시 시도할 수 있는 실패 행에만 「다시 시도」가 있고, 「다시 시도할 수 있어요」 문구는 없다", async ({
    page,
  }) => {
    for (const { recordingId } of [
      TRANSCRIPTION_FAILED,
      DOCUMENT_FAILED_RETRYABLE,
    ]) {
      const row = getRow(page, recordingId);
      await expect(
        row.getByRole("button", { name: "다시 시도" }),
      ).toBeVisible();
      await expect(row.getByRole("link", { name: "녹음 보기" })).toHaveCount(0);
    }

    const notRetryableRow = getRow(
      page,
      DOCUMENT_FAILED_NOT_RETRYABLE.recordingId,
    );
    await expect(notRetryableRow).toContainText("문서를 만들지 못했어요");
    await expect(
      notRetryableRow.getByRole("button", { name: "다시 시도" }),
    ).toHaveCount(0);
    await expect(
      notRetryableRow.getByRole("link", { name: "녹음 보기" }),
    ).toBeVisible();
    await expect(
      getRecordingHistoryCard(page).getByText("다시 시도할 수 있어요"),
    ).toHaveCount(0);
  });

  for (const { failure, recording } of [
    { failure: "전사 실패", recording: TRANSCRIPTION_FAILED },
    { failure: "문서 생성 실패", recording: DOCUMENT_FAILED_RETRYABLE },
  ]) {
    test(`[STT-R34, STT-R42] ${failure} 행에서 「다시 시도」를 누르면 그 행이 문서 정리 중으로 바뀐다 @core`, async ({
      page,
    }) => {
      const row = getRow(page, recording.recordingId);

      await row.getByRole("button", { name: "다시 시도" }).click();

      await expect(row).toContainText("문서 정리 중");
      await expect(row.getByRole("button", { name: "다시 시도" })).toHaveCount(
        0,
      );
    });
  }

  test("[STT-R34] 「녹음 보기」를 누르면 그 행의 녹음 문서로 간다", async ({
    page,
  }) => {
    await getRow(page, COMPLETED.recordingId)
      .getByRole("link", { name: "녹음 보기" })
      .click();

    await expect(page).toHaveURL(
      `${HOME_PATH}/recordings/${COMPLETED.recordingId}/document`,
    );
  });

  test("[STT-R34, STT-R21] 문서 정리 중 행의 「녹음 보기」는 누를 수 없다", async ({
    page,
  }) => {
    const viewRecording = getRow(page, PROCESSING.recordingId).getByRole(
      "link",
      { name: "녹음 보기" },
    );

    await expect(viewRecording).toHaveAttribute("aria-disabled", "true");
    await expect(viewRecording).not.toHaveAttribute("href");
  });

  test("[STT-R51, STT-R33] 목록 끝까지 내리면 다음 묶음을 받아, 그 행도 같은 규칙으로 보여 준다", async ({
    page,
  }) => {
    const card = getRecordingHistoryCard(page);
    await expect(card.getByRole("listitem")).toHaveCount(firstPage.length);

    await scrollListToEnd(page);

    await expect(card.getByRole("listitem")).toHaveCount(
      expectedRecordings.length,
    );
    const lastRecording = expectedRecordings[expectedRecordings.length - 1];
    await expect(card.getByRole("listitem").last()).toContainText(
      formatDate(lastRecording.startedAt),
    );
  });

  test("[STT-R24] 내 진행 중 녹음이 없으면 맨 위 줄이 없다", async ({
    page,
  }) => {
    const card = getRecordingHistoryCard(page);
    await expect(card.getByRole("listitem").first()).toBeVisible();

    await expect(
      card.getByRole("button", { name: "녹음 화면으로" }),
    ).toHaveCount(0);
  });

  for (const { scenario, status } of [
    { scenario: "recording", status: "녹음 중" },
    { scenario: "paused", status: "일시정지" },
  ]) {
    test(`[STT-R24] 녹음 기록 맨 위 줄에 내 ${status} 녹음을 보여 주고, 다른 탭의 녹음이면 「녹음 화면으로」를 누를 수 없다`, async ({
      page,
    }) => {
      await page.goto(`${HOME_PATH}?mock.currentRecording=${scenario}`);
      const card = getRecordingHistoryCard(page);

      await expect(
        card.getByText(`${expectedMe.nickname} 님의 녹음`).first(),
      ).toBeVisible();
      await expect(card.getByText(new RegExp(`^${status} · `))).toBeVisible();
      await expect(
        card.getByRole("button", { name: "녹음 화면으로" }),
      ).toBeDisabled();
    });
  }
});
