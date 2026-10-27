import { PostWorkspaceResponseDto } from "@api/dto/workspace";
import { workspaceCreateResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";

/**
 * 워크스페이스 생성 화면(`/workspace/create`) E2E(WS-R2, WS-R4).
 *
 * 이름에 한글, 영어, 숫자(0~9), 공백만 받고 최대 20자인지, 허용하지 않은 문자(기호, 전각 숫자, 원 숫자)를 넣으면
 * 입력창 아래에 형식 문구가 뜨고 고치면 사라지는지 확인해요.
 * 생성 요청은 dev 서버의 msw mock이 답하므로(`API_MOCKING`) 이동할 초대 화면 주소도 같은 응답을 DTO로 변환해 가져와요.
 */

const CREATE_PATH = "/workspace/create";
const INVITE_PATH = `/workspace/${new PostWorkspaceResponseDto(workspaceCreateResponse).id}/invite`;
const MAX_LENGTH = 20;
const FORMAT_ERROR_MESSAGE = "한글, 영어, 숫자와 공백만 사용할 수 있어요.";
// 한글, 영어, 숫자, 공백을 섞은 21자. 앞에서부터 잘라 1자, 19자, 20자로도 써요
const MIXED_21_CHARS = "노트 Team 2026 회의록 정리방1";

const getNameInput = (page: Page) =>
  page.getByRole("textbox", { name: "워크스페이스 이름" });

const getSubmitButton = (page: Page) =>
  page.getByRole("button", { name: "워크스페이스 생성" });

/** 이름 입력과 글자 수 표시가 함께 맞는지 */
const expectAcceptedName = async (page: Page, name: string) => {
  await expect(getNameInput(page)).toHaveValue(name);
  await expect(getNameInput(page)).toHaveAccessibleDescription("");
  await expect(page.getByText(`${name.length}/${MAX_LENGTH}`)).toBeVisible();
  await expect(getSubmitButton(page)).toBeEnabled();
};

test.describe("워크스페이스 생성 화면", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(CREATE_PATH);
  });

  test("[WS-QA-003] 한글, 영어, 숫자, 공백을 섞은 이름과 숫자만으로 된 이름은 에러 없이 버튼이 켜진다", async ({
    page,
  }) => {
    await getNameInput(page).fill("노트 Team 2026");
    await expectAcceptedName(page, "노트 Team 2026");

    await getNameInput(page).fill("2026");
    await expectAcceptedName(page, "2026");
  });

  test("[WS-QA-003] 글자 수를 보여주고 20자를 넘는 글은 20자까지만 들어간다", async ({
    page,
  }) => {
    for (const length of [1, 19, 20]) {
      await getNameInput(page).fill(MIXED_21_CHARS.slice(0, length));
      await expectAcceptedName(page, MIXED_21_CHARS.slice(0, length));
    }

    await getNameInput(page).fill(MIXED_21_CHARS);
    await expectAcceptedName(page, MIXED_21_CHARS.slice(0, MAX_LENGTH));
  });

  test("[WS-QA-003] 숫자가 든 이름으로 만들면 초대 코드 공유 단계로 넘어간다", async ({
    page,
  }) => {
    await getNameInput(page).fill("Knot 2026");
    await getSubmitButton(page).click();

    await expect(page).toHaveURL(INVITE_PATH);
    await expect(
      page.getByRole("heading", { name: "팀원을 초대하세요" }),
    ).toBeVisible();
  });

  for (const name of ["팀!", "２０２６", "①팀"]) {
    test(`[WS-QA-005] 「${name}」을 넣으면 형식 문구를 보여주고, 고치면 사라진다`, async ({
      page,
    }) => {
      await getNameInput(page).fill(name);

      await expect(getNameInput(page)).toHaveAccessibleDescription(
        FORMAT_ERROR_MESSAGE,
      );
      await expect(getSubmitButton(page)).toBeDisabled();

      await getNameInput(page).fill("팀 2026");

      await expect(page.getByText(FORMAT_ERROR_MESSAGE)).toBeHidden();
      await expectAcceptedName(page, "팀 2026");
    });
  }
});
