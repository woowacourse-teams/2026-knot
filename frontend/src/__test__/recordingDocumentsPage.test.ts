import { GetDocumentRecordingsResponseDto } from "@api/dto/document";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { documentRecordingsResponse } from "@api/mock/responses/document";
import { recordingDetailsResponse } from "@api/mock/responses/recording";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test } from "@playwright/test";

/**
 * 녹음 뒤 정리 화면(`/workspace/:workspaceId/recordings/:recordingId`) E2E.
 *
 * 정리가 끝난 녹음의 정리 화면에 있으면 첫 주제별 문서가 아니라 그 녹음의 녹음 문서로 넘어가는지 확인해요(STT-R18 · R20).
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
});
