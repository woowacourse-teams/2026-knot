import { GetMeResponseDto } from "@api/dto/auth";
import { PostWorkspaceResponseDto } from "@api/dto/workspace";
import { PostWorkspaceInvitationResponseDto } from "@api/dto/workspaceInvitation";
import { meResponse } from "@api/mock/responses/auth";
import { workspaceCreateResponse } from "@api/mock/responses/workspace";
import { workspaceInvitationResponse } from "@api/mock/responses/workspaceInvitation";
import { expect, test } from "@playwright/test";

/**
 * 온보딩 화면(`/onboarding`)에서 워크스페이스 홈까지의 E2E.
 * 핵심 여정 「로그인 후 온보딩을 마치고 워크스페이스를 만든다」.
 *
 * GitHub 로그인은 실제 OAuth라 에이전트 QA가 맡고, 여기서는 로그인 직후의 온보딩 쿠키 상태에서 시작해요.
 * 닉네임 저장은 dev 서버의 mock이 쿠키를 회원으로 바꿔 흉내내요(src/shared/api/mock/handlers/dev).
 */

const NICKNAME = "새친구";
const WORKSPACE_NAME = "우리 팀";

const createdWorkspaceId = new PostWorkspaceResponseDto(workspaceCreateResponse)
  .id;
const expectedInvitation = new PostWorkspaceInvitationResponseDto(
  workspaceInvitationResponse,
);
// 홈 인사말은 회원 조회 mock의 닉네임이라, 방금 입력한 닉네임이 아니라 그 값을 기대해요
const expectedMe = new GetMeResponseDto(meResponse);

// 로그인을 마치고 닉네임을 정하기 전의 온보딩 쿠키로 시작해요
test.use({
  storageState: {
    cookies: [
      {
        name: "KNOT_MOCK_AUTH",
        value: "onboarding",
        domain: "localhost",
        path: "/",
        expires: -1,
        httpOnly: false,
        secure: false,
        sameSite: "Lax",
      },
    ],
    origins: [],
  },
});

test.describe("가입부터 첫 워크스페이스까지", () => {
  test(
    "[ONB-QA-004] 닉네임을 정해 가입하고 새 워크스페이스를 만들어 홈에 들어간다",
    { tag: ["@core"] },
    async ({ page }) => {
      await page.goto("/onboarding");

      await page.getByRole("textbox", { name: "닉네임" }).fill(NICKNAME);
      await page.getByRole("button", { name: "확인" }).click();

      await expect(page).toHaveURL("/onboarding/complete");
      await expect(
        page.getByRole("heading", { name: new RegExp(`${NICKNAME}님`) }),
      ).toBeVisible();

      await page.getByRole("button", { name: "시작하기" }).click();
      await expect(page).toHaveURL("/workspace");
      await page
        .getByRole("button", { name: "새 워크스페이스 만들기" })
        .click();

      await expect(page).toHaveURL("/workspace/create");
      await page
        .getByRole("textbox", { name: "워크스페이스 이름" })
        .fill(WORKSPACE_NAME);
      await page.getByRole("button", { name: "워크스페이스 생성" }).click();

      // 만든 직후에는 팀원에게 보낼 초대를 보여 주고, 다음으로 홈에 들어가요
      await expect(page).toHaveURL(`/workspace/${createdWorkspaceId}/invite`);
      await expect(
        page.getByRole("textbox", { name: "초대 링크" }),
      ).toHaveValue(`/invite/${expectedInvitation.linkToken}`);
      await page.getByRole("button", { name: "다음" }).click();

      await expect(page).toHaveURL(`/workspace/${createdWorkspaceId}`);
      await expect(
        page.getByRole("heading", {
          name: `반가워요, ${expectedMe.nickname} 님`,
        }),
      ).toBeVisible();
    },
  );
});
