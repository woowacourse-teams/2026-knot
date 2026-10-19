import { GetMeResponseDto } from "@api/dto/auth";
import {
  GetInvitationPreviewResponseDto,
  PostInvitationAcceptResponseDto,
  PostWorkspaceInvitationResponseDto,
} from "@api/dto/workspaceInvitation";
import { meResponse } from "@api/mock/responses/auth";
import {
  invitationAcceptanceResponse,
  invitationPreviewResponse,
  workspaceInvitationResponse,
} from "@api/mock/responses/workspaceInvitation";
import { expect, test, type Page } from "@playwright/test";

/**
 * 초대 링크 화면(`/invite/:token`)에서 워크스페이스 홈까지의 E2E.
 * 핵심 여정 「초대 링크로 들어와 워크스페이스에 참여한다」.
 *
 * 초대 미리보기와 수락은 dev 서버의 msw mock 응답(`API_MOCKING`)에서 오므로 기대값도 같은 응답을 DTO로 변환해 가져와요.
 * 서버가 실제로 멤버로 참여시키는지는 에이전트 QA가 맡아요.
 */

// 홈 초대 카드가 보여 주는 링크와 같은 토큰으로 들어와요
const INVITE_PATH = `/invite/${
  new PostWorkspaceInvitationResponseDto(workspaceInvitationResponse).linkToken
}`;
const preview = new GetInvitationPreviewResponseDto(invitationPreviewResponse);
const accepted = new PostInvitationAcceptResponseDto(
  invitationAcceptanceResponse,
);
const expectedMe = new GetMeResponseDto(meResponse);

/** 초대 링크를 열면 참여 화면에서 워크스페이스 이름을 보여 줘요 */
async function openInviteLink(page: Page) {
  await page.goto(INVITE_PATH);

  await expect(page).toHaveURL(`/workspace/${preview.workspaceId}/join`);
  await expect(
    page.getByRole("heading", { name: preview.workspaceName, level: 1 }),
  ).toBeVisible();
}

test.describe("초대 링크로 참여", () => {
  test(
    "초대 링크를 열고 참여하면 그 워크스페이스 홈으로 들어간다",
    { tag: ["@core"] },
    async ({ page }) => {
      await openInviteLink(page);

      await page.getByRole("button", { name: "참여할게요" }).click();

      await expect(page).toHaveURL(`/workspace/${accepted.workspaceId}`);
      await expect(
        page.getByRole("heading", {
          name: `반가워요, ${expectedMe.nickname} 님`,
        }),
      ).toBeVisible();
    },
  );

  test.describe("로그인하지 않은 사람", () => {
    test.use({ storageState: { cookies: [], origins: [] } });

    test(
      "[WS-QA-025] 초대 링크를 열면 참여할 워크스페이스 이름을 미리 본다",
      { tag: ["@core"] },
      async ({ page }) => {
        await openInviteLink(page);

        await expect(
          page.getByRole("button", { name: "참여할게요" }),
        ).toBeVisible();
      },
    );
  });
});
