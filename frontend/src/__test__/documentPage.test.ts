import {
  GetDocumentRecordingsResponseDto,
  GetDocumentResponseDto,
} from "@api/dto/document";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import {
  documentDetailsResponse,
  documentRecordingsResponse,
} from "@api/mock/responses/document";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test } from "@playwright/test";

/**
 * 문서 보기 화면(`/workspace/:workspaceId/documents/:documentId`) E2E.
 *
 * 문서 목록에서 문서를 연 직후, 화면이 바뀌기 전에 GNB의 문서를 눌러도 문서 목록으로 돌아오는지 확인해요.
 * 본문 아래 「이 문서가 나온 녹음」 카드로 녹음 문서에 가는지도 확인해요(DOC-R25).
 * dev 서버 mock은 E2E에서 응답을 바꿀 수 없어서, 녹음 제목을 받는 동안과 받지 못했을 때는 통합 테스트(`DocumentViewer/test.tsx`)에서 봐요.
 * 라우터는 주소를 먼저 바꾸고 화면은 transition으로 나중에 그려서, 그 사이 GNB는 아직 목록 화면 기준이에요.
 */

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
// 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9)
const DOCUMENTS_PATH = `/workspace/${WORKSPACE_ID}/documents/recordings`;
const NAV_LABEL = "워크스페이스 화면 이동";

test("문서를 연 직후 화면이 바뀌기 전에 문서를 눌러도 문서 목록으로 돌아온다", async ({
  page,
}) => {
  await page.goto(DOCUMENTS_PATH);
  // 「녹음별」 탭에서 문서 행은 목록 항목 안의 링크예요. 녹음 행(목록 밖)은 녹음 문서로 가요
  const documentLink = page
    .getByRole("main")
    .getByRole("listitem")
    .first()
    .getByRole("link");
  await expect(documentLink).toBeVisible();
  const documentsTab = page
    .getByRole("navigation", { name: NAV_LABEL })
    .getByRole("button", { name: "문서" });
  await expect(documentsTab).toHaveAttribute("aria-current", "page");

  // 문서를 누른 다음 작업에서 바로 문서를 눌러, 문서 화면이 그려지기 전에 누른 순간을 만들어요
  await page.evaluate(
    async ({ navLabel }) => {
      document.querySelector<HTMLAnchorElement>("main li a")!.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
      const tabs = document.querySelectorAll<HTMLButtonElement>(
        `nav[aria-label="${navLabel}"] button`,
      );
      [...tabs].find((tab) => tab.textContent === "문서")!.click();
    },
    { navLabel: NAV_LABEL },
  );

  await expect(page).toHaveURL(DOCUMENTS_PATH);
  await expect(
    page.getByRole("main").getByRole("listitem").first().getByRole("link"),
  ).toBeVisible();
});

test.describe("이 문서가 나온 녹음 (DOC-R25)", () => {
  const expectedDocument = new GetDocumentResponseDto(
    documentDetailsResponse[0],
  );
  // 녹음 문서 조회 mock이 「녹음별」 mock의 제목으로 답해요
  const expectedRecording = new GetDocumentRecordingsResponseDto(
    documentRecordingsResponse,
  ).items.find(
    ({ recordingId }) => recordingId === expectedDocument.recordingSessionId,
  )!;
  const DOCUMENT_PATH = `/workspace/${WORKSPACE_ID}/documents/${expectedDocument.id}`;
  const RECORDING_DOCUMENT_PATH = `/workspace/${WORKSPACE_ID}/recordings/${expectedRecording.recordingId}/document`;
  const SOURCE_RECORDING_NAME = "이 문서가 나온 녹음";

  test("[DOC-R25, STT-R40] 본문 아래 「이 문서가 나온 녹음」 카드에 녹음 제목이 보이고, 누르면 그 녹음의 녹음 문서가 열린다", async ({
    page,
  }) => {
    await page.goto(DOCUMENT_PATH);

    const sourceRecording = page.getByRole("region", {
      name: SOURCE_RECORDING_NAME,
    });
    const card = sourceRecording.getByRole("link");
    await expect(card).toContainText(expectedRecording.title);
    await expect(card).toContainText(
      "회의 전체 요약, 원문, 녹음 음성을 볼 수 있어요.",
    );

    await card.click();

    await expect(page).toHaveURL(RECORDING_DOCUMENT_PATH);
    await expect(
      page.getByRole("heading", { level: 1, name: expectedRecording.title }),
    ).toBeVisible();
  });

  test("[DOC-R25] 녹음 직후 문서 화면에도 같은 카드가 보인다", async ({
    page,
  }) => {
    await page.goto(`${DOCUMENT_PATH}?from=recording`);

    await expect(
      page
        .getByRole("region", { name: SOURCE_RECORDING_NAME })
        .getByRole("link"),
    ).toContainText(expectedRecording.title);
  });

  test("[DOC-R25] 폭 390에서는 카드가 본문 폭을 채운다", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(DOCUMENT_PATH);

    const sourceRecording = page.getByRole("region", {
      name: SOURCE_RECORDING_NAME,
    });
    const card = sourceRecording.getByRole("link");
    await expect(card).toContainText(expectedRecording.title);

    const regionBox = await sourceRecording.boundingBox();
    const cardBox = await card.boundingBox();
    expect(cardBox!.width).toBeCloseTo(regionBox!.width, 0);
  });
});
