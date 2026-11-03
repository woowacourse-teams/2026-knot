import {
  GetDocumentRecordingsResponseDto,
  GetDocumentResponseDto,
} from "@api/dto/document";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import {
  documentDetailsResponse,
  documentRecordingsResponse,
} from "@api/mock/responses/document";
import { recordingDetailsResponse } from "@api/mock/responses/recording";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { expect, test } from "@playwright/test";

/**
 * 녹음 뒤 정리 화면(`/workspace/:workspaceId/recordings/:recordingId`) E2E.
 *
 * 정리가 끝난 녹음의 정리 화면에 있으면 첫 주제별 문서가 아니라 그 녹음의 녹음 문서로 넘어가는지 확인해요(STT-R18 · R20).
 * 녹음 문서의 「이 회의에서 나온 문서」로 연 문서 보기에는 같은 녹음의 문서를 넘기는 이전 · 다음이 없고, 거기서 문서를 하나씩 확인해요(CONF-R2 삭제 · CONF-R6 · CONF-R4).
 * dev 서버 mock은 E2E에서 응답을 바꿀 수 없어서, 정리 중이다가 끝나는 경우와 실패 · 정리할 내용 없음은 통합 테스트(`RecordingDocuments/test.tsx`)에서 봐요.
 * 회의 전체 요약을 아직 만드는 중인 녹음 문서도 mock에 없어, 정리 화면이 요약을 기다리지 않는 것은 통합 테스트에서 봐요(STT-R41).
 */

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
// mock 녹음은 차례로 정리 중 · 정리할 내용 없음 · 문서 만들기 실패 · 전사 실패 · 정리 완료예요
const [, , , , completedRecording] = recordingDetailsResponse;
const RECORDING_DOCUMENTS_PATH = `${HOME_PATH}/recordings/${completedRecording.recordingId}`;
const RECORDING_DOCUMENT_PATH = `${RECORDING_DOCUMENTS_PATH}/document`;
// 녹음 문서 조회 mock이 「녹음별」 mock의 제목으로 답해요
const expectedRecording = new GetDocumentRecordingsResponseDto(
  documentRecordingsResponse,
).items.find(
  ({ recordingId }) => recordingId === completedRecording.recordingId,
)!;
// 이 녹음의 문서 가운데 아직 확인하지 않은 문서예요. 나머지 문서는 mock에서 이미 확인해, 이 문서를 확인하면 남은 문서가 없어요
const pendingDocument = documentDetailsResponse
  .map((detail) => new GetDocumentResponseDto(detail))
  .find(
    ({ recordingSessionId, myConfirmationState }) =>
      recordingSessionId === completedRecording.recordingId &&
      myConfirmationState === "PENDING",
  )!;
// 없앤 이전 · 다음(같은 녹음의 문서를 넘기던 스테퍼)의 이름
const STEPPER_NAME = "같은 녹음의 문서";

test.describe("녹음 뒤 정리 화면", () => {
  test("[STT-R18, STT-R20] 정리가 끝난 녹음의 정리 화면에 있으면 첫 주제별 문서가 아니라 그 녹음의 녹음 문서로 넘어가고, 뒤로 가도 정리 화면으로 돌아오지 않는다 @core", async ({
    page,
  }) => {
    await page.goto(HOME_PATH);
    await page.goto(RECORDING_DOCUMENTS_PATH);

    await expect(page).toHaveURL(RECORDING_DOCUMENT_PATH);
    await expect(
      page
        .getByRole("main")
        .getByRole("heading", { level: 2, name: expectedRecording.title }),
    ).toBeVisible();

    await page.goBack();

    await expect(page).toHaveURL(HOME_PATH);
  });

  test("[STT-R52, CONF-R6, CONF-R4, DOC-R25] 넘어간 녹음 문서의 「이 회의에서 나온 문서」로 연 문서 보기에는 이전 · 다음이 없고, 남은 문서를 확인하면 모두 확인했다고 알린다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENTS_PATH);
    await expect(page).toHaveURL(RECORDING_DOCUMENT_PATH);

    await page
      .getByRole("main")
      .getByRole("region", { name: "이 회의에서 나온 문서" })
      .getByRole("link", { name: new RegExp(pendingDocument.title) })
      .click();

    // 예전처럼 정리 화면에서 넘어왔다는 쿼리가 붙지 않아요
    await expect(page).toHaveURL(
      `${HOME_PATH}/documents/${pendingDocument.id}`,
    );
    await expect(
      page.getByRole("region", { name: "이 문서가 나온 녹음" }),
    ).toBeVisible();
    await expect(page.getByRole("group", { name: STEPPER_NAME })).toHaveCount(
      0,
    );

    await page.getByRole("button", { name: "문서를 확인했어요" }).click();

    await expect(
      page
        .getByTestId("toast-list")
        .getByText(TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED.message),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "문서를 확인했어요" }),
    ).toHaveCount(0);
  });
});
