import {
  GetDocumentResponseDto,
  GetDocumentsResponseDto,
} from "@api/dto/document";
import { GetMeResponseDto } from "@api/dto/auth";
import { PostRecordingResponseDto } from "@api/dto/recording";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { meResponse } from "@api/mock/responses/auth";
import {
  documentDetailsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import { recordingStartResponse } from "@api/mock/responses/recording";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";

/**
 * 녹음 화면(`/workspace/:workspaceId/recording`)과 정리 화면(`/recordings/:recordingId`) E2E.
 * 핵심 여정 「독의 마이크로 녹음을 시작하고 끝내면, 문서 정리 중을 거쳐 주제별 문서가 만들어진다」.
 *
 * mock 녹음 상세는 끝낸 녹음(시작 응답과 같은 ID)을 늘 정리 중으로 두고, 정리가 끝난 녹음은 다른 ID로 따로 둬요.
 * 그래서 여정을 「끝내면 정리 중 화면」과 「정리가 끝나면 주제별 문서」 두 테스트로 나눠요.
 * 마이크는 Playwright 설정의 가짜 장치와 자동 허용으로 받아요.
 */

const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
const RECORDING_PATH = `${HOME_PATH}/recording`;
const recordingDocumentsPath = (recordingId: number) =>
  `${HOME_PATH}/recordings/${recordingId}`;

const expectedMe = new GetMeResponseDto(meResponse);
// 녹음 시작 mock은 새 세션(201)으로 답해요
const STARTED_RECORDING_ID = new PostRecordingResponseDto(
  recordingStartResponse,
  201,
).recordingId;

// 정리가 끝난 녹음은 상세 mock이 있는 문서가 나온 녹음이에요. 정리 화면은 그 녹음의 문서를 문서 목록 순서대로 보여 줘요
const openableDocuments = documentDetailsResponse.map(
  (document) => new GetDocumentResponseDto(document),
);
const [{ recordingSessionId: COMPLETED_RECORDING_ID }] = openableDocuments;
const RECORDING_DOCUMENTS = new GetDocumentsResponseDto(
  documentsResponse,
).items.filter(
  ({ recordingSessionId }) => recordingSessionId === COMPLETED_RECORDING_ID,
);

// 독은 이미 진행 중인 녹음이 있으면 마이크 대신 녹음 칩을 둬요. mock은 진행 중인 녹음을 늘 돌려주므로,
// 새로 시작하는 사람처럼 마이크가 보이게 현재 녹음 조회만 닿지 않는 주소로 보내 실패시켜요.
// mock 워커가 먼저 받는 요청은 page.route가 잡지 못해요. 조회에 실패하면 독은 마이크를 둬요
async function openHomeWithoutRecording(page: Page) {
  await page.addInitScript(() => {
    const open = XMLHttpRequest.prototype.open;

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: [boolean, string?, string?]
    ) {
      const target = String(url).includes("/recordings/current")
        ? "http://127.0.0.1:1/"
        : url;

      return open.call(this, method, target, ...rest);
    } as typeof XMLHttpRequest.prototype.open;
  });
  await page.goto(HOME_PATH);
}

test.describe("녹음에서 문서까지", () => {
  test(
    "[STT-R25] 독의 마이크로 녹음을 시작하고 끝내기를 확정하면 문서 정리 화면으로 간다",
    { tag: ["@core"] },
    async ({ page }) => {
      await openHomeWithoutRecording(page);

      await page.getByRole("button", { name: "회의 녹음 시작" }).click();

      await expect(page).toHaveURL(RECORDING_PATH);
      const controls = page.getByRole("region", { name: "녹음 조작" });
      await expect(
        controls.getByText("녹음 중", { exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole("heading", {
          name: `${expectedMe.nickname} 님의 녹음`,
          level: 1,
        }),
      ).toBeVisible();

      await controls.getByRole("button", { name: "녹음 끝내기" }).click();
      await page
        .getByRole("dialog", { name: "녹음을 끝낼까요?" })
        .getByRole("button", { name: "녹음 끝내기" })
        .click();

      await expect(page).toHaveURL(
        recordingDocumentsPath(STARTED_RECORDING_ID),
      );
      await expect(
        page.getByRole("heading", {
          name: "회의 내용을 바탕으로 문서를 정리하고 있어요",
        }),
      ).toBeVisible();
    },
  );

  test(
    "[CONF-R2, CONF-R3] 정리가 끝난 녹음은 주제별 문서로 이어지고 같은 녹음의 문서를 넘겨 본다",
    { tag: ["@core"] },
    async ({ page }) => {
      await page.goto(recordingDocumentsPath(COMPLETED_RECORDING_ID));

      const pager = page.getByRole("group", { name: "같은 녹음의 문서" });
      for (const [
        index,
        { id, title, myConfirmationState },
      ] of RECORDING_DOCUMENTS.entries()) {
        if (index > 0) await pager.getByRole("button").last().click();

        await expect(page).toHaveURL(
          new RegExp(`${HOME_PATH}/documents/${id}(\\?|$)`),
        );
        await expect(
          page.getByRole("heading", { name: title, level: 2 }),
        ).toBeVisible();
        await expect(pager).toContainText(
          `${index + 1} / ${RECORDING_DOCUMENTS.length}`,
        );
        // 내가 확인한 문서는 확인을 마친 상태로, 확인할 문서는 확인 버튼으로 보여요
        if (myConfirmationState === "CONFIRMED") {
          await expect(
            page.getByRole("button", { name: "확인했어요", exact: true }),
          ).toBeDisabled();
        } else if (myConfirmationState === "PENDING") {
          await expect(
            page.getByRole("button", { name: "문서를 확인했어요" }),
          ).toBeEnabled();
        }
      }
    },
  );
});
