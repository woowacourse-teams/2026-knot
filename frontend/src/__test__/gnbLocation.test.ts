import { GetDocumentResponseDto } from "@api/dto/document";
import {
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
} from "@api/dto/workspace";
import { documentDetailsResponse } from "@api/mock/responses/document";
import {
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * GNB 위치 표시 E2E(WS-R15).
 *
 * GNB 왼쪽 패널 버튼 옆에 워크스페이스 아이콘과 이름, 지금 탭이 보이는지 화면마다 확인해요.
 * 문서 하나를 보는 화면은 `문서 › 문서 제목`까지, 탭이 아닌 화면과 1024 미만은 이름만 보여요. 767 이하에서는 숨겨요.
 * 워크스페이스 · 문서 조회는 dev 서버의 msw mock이 답해요(`API_MOCKING`).
 */

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const WORKSPACE_NAME = new GetWorkspaceResponseDto(workspaceDetailResponse)
  .name;
const DOCUMENT = new GetDocumentResponseDto(documentDetailsResponse[0]);
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
// 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9). 문서 하나의 주소는 탭 없이 문서 id가 붙어요
const DOCUMENTS_PATH = `${HOME_PATH}/documents/recordings`;
const DOCUMENT_PATH = `${HOME_PATH}/documents/${DOCUMENT.id}`;
// 녹음 직후 문서 확인 화면. 녹음 뒤 정리 화면이 문서를 다 만들면 이 주소로 보내요
const FROM_RECORDING_PATH = `${DOCUMENT_PATH}?${DOCUMENT_FROM_PARAM}=${DOCUMENT_FROM_RECORDING}`;
const TAB_NAMES = ["홈", "탐색", "문서"];
// 워크스페이스 이름의 최대 길이(20자)
const LONGEST_WORKSPACE_NAME = "가나다라마바사아자차카타파하가나다라마바";
const LONG_DOCUMENT_TITLE =
  "다음 분기 온보딩 개편 범위와 일정, 담당자, 남은 결정 사항을 정리한 회의";

const getLocation = (page: Page) =>
  page.getByRole("group", { name: "지금 위치" });

/** 위치 표시 안에서 글자가 정확히 같은 조각 */
const getPart = (location: Locator, text: string) =>
  location.getByText(text, { exact: true });

/** 글자가 칸보다 길어 잘렸고, 한 줄에 말줄임으로 그려졌는지 */
const expectEllipsis = async (part: Locator) => {
  const style = await part.evaluate((element) => {
    const computed = getComputedStyle(element);

    return {
      isClipped: element.scrollWidth > element.clientWidth,
      whiteSpace: computed.whiteSpace,
      textOverflow: computed.textOverflow,
    };
  });

  expect(style).toEqual({
    isClipped: true,
    whiteSpace: "nowrap",
    textOverflow: "ellipsis",
  });
};

/**
 * 위치 표시의 한 조각 글자를 바꿔요.
 * dev 서버 mock은 E2E에서 응답을 바꿀 수 없어서, 가장 긴 이름 · 제목은 그려진 글자를 바꿔 만들어요
 */
const replaceText = (part: Locator, text: string) =>
  part.evaluate((element, nextText) => {
    element.textContent = nextText;
  }, text);

test.describe("GNB 위치 표시", () => {
  for (const [tab, path] of [
    ["홈", HOME_PATH],
    ["탐색", `${HOME_PATH}/chat`],
    ["문서", DOCUMENTS_PATH],
  ]) {
    test(`[WS-QA-058] ${tab} 탭에서는 워크스페이스 이름 뒤에 ${tab}을 보여준다`, async ({
      page,
    }) => {
      await page.goto(path);
      const location = getLocation(page);

      await expect(getPart(location, WORKSPACE_NAME)).toBeVisible();
      await expect(getPart(location, tab)).toBeVisible();
    });
  }

  test("[WS-QA-059] 문서 하나를 보는 화면에서는 이름 › 문서 › 문서 제목을 보여주고, 본문 위에는 경로가 없다", async ({
    page,
  }) => {
    await page.goto(DOCUMENT_PATH);
    const location = getLocation(page);

    await expect(getPart(location, WORKSPACE_NAME)).toBeVisible();
    await expect(getPart(location, "문서")).toBeVisible();
    await expect(getPart(location, DOCUMENT.title)).toBeVisible();
    await expect(
      page.getByRole("heading", { level: 2, name: DOCUMENT.title }),
    ).toBeVisible();
    await expect(
      page.getByRole("main").getByText("문서", { exact: true }),
    ).toHaveCount(0);
  });

  test("[WS-QA-060] 녹음 직후 문서 확인 화면처럼 탭이 아닌 화면에서는 아이콘과 워크스페이스 이름만 보여준다", async ({
    page,
  }) => {
    await page.goto(FROM_RECORDING_PATH);
    await expect(
      page.getByRole("heading", { level: 2, name: DOCUMENT.title }),
    ).toBeVisible();
    const location = getLocation(page);

    await expect(getPart(location, WORKSPACE_NAME)).toBeVisible();
    for (const tab of TAB_NAMES) {
      await expect(getPart(location, tab)).toHaveCount(0);
    }
  });

  test("[WS-QA-061] 워크스페이스 이름을 눌러도 이동하거나 워크스페이스 메뉴가 열리지 않는다", async ({
    page,
  }) => {
    await page.goto(HOME_PATH);
    const location = getLocation(page);

    await getPart(location, WORKSPACE_NAME).click();

    await expect(page).toHaveURL(HOME_PATH);
    await expect(
      page.getByRole("region", { name: "워크스페이스 메뉴" }),
    ).toHaveCount(0);
    await expect(location.getByRole("button")).toHaveCount(0);
  });

  test("[WS-QA-062] 긴 문서 제목은 한 줄로 두고 넘치는 부분을 말줄임한다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1024, height: 768 });
    await page.goto(DOCUMENT_PATH);
    const location = getLocation(page);
    const title = getPart(location, DOCUMENT.title);
    await expect(title).toBeVisible();

    await replaceText(
      getPart(location, WORKSPACE_NAME),
      LONGEST_WORKSPACE_NAME,
    );
    await replaceText(title, LONG_DOCUMENT_TITLE);

    await expectEllipsis(getPart(location, LONG_DOCUMENT_TITLE));
  });

  test("[WS-QA-062] 20자 워크스페이스 이름도 한 줄로 두고 넘치는 부분을 말줄임한다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 834, height: 1194 });
    await page.goto(HOME_PATH);
    const location = getLocation(page);
    const name = getPart(location, WORKSPACE_NAME);
    await expect(name).toBeVisible();

    await replaceText(name, LONGEST_WORKSPACE_NAME);

    await expectEllipsis(getPart(location, LONGEST_WORKSPACE_NAME));
  });

  test("[WS-QA-063] 태블릿 834에서는 탭 이름과 문서 제목 없이 아이콘과 이름만 보여준다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 834, height: 1194 });
    await page.goto(DOCUMENT_PATH);
    const location = getLocation(page);

    await expect(getPart(location, WORKSPACE_NAME)).toBeVisible();
    await expect(getPart(location, "문서")).toBeHidden();
    await expect(getPart(location, DOCUMENT.title)).toBeHidden();
  });

  test("[WS-QA-063] 모바일 390에서는 GNB 왼쪽 자리가 모자라 위치 표시를 숨긴다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(DOCUMENT_PATH);
    await expect(
      page.getByRole("heading", { level: 2, name: DOCUMENT.title }),
    ).toBeVisible();

    await expect(getLocation(page)).toBeHidden();
  });

  test("[WS-QA-063] 1024에서는 탭 이름과 문서 제목이 다시 보인다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 834, height: 1194 });
    await page.goto(DOCUMENT_PATH);
    const location = getLocation(page);
    await expect(getPart(location, DOCUMENT.title)).toBeHidden();

    await page.setViewportSize({ width: 1024, height: 768 });

    await expect(getPart(location, "문서")).toBeVisible();
    await expect(getPart(location, DOCUMENT.title)).toBeVisible();
  });
});
