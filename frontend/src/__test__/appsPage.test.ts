import {
  GetSearchNotionConnectionResponseDto,
  PostSearchNotionConnectionAuthorizationResponseDto,
} from "@api/dto/search";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { NOTION_CONNECTION_SCENARIO_KEY } from "@api/mock/handlers/dev/notionConnection";
import {
  searchNotionConnectionAuthorizationResponse,
  searchNotionConnectionResponse,
} from "@api/mock/responses/search";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { expect, test, type Locator, type Page } from "@playwright/test";

/**
 * 연결된 앱 화면 E2E(QA 시트 SEARCH-057, 058, 059, 061, 062, 063, 064의 연결 화면 부분).
 *
 * 노션 연결 조회, 시작, 끊기는 dev 서버의 msw mock이 답해요(`API_MOCKING`). 연결 상태는 localStorage의
 * mock 상황(`knot.mock.notionConnection`)으로 고르고, 값이 없으면 연결한 사람이 보는 연결됨이에요.
 * 노션 동의 화면은 Knot 밖이라 그 주소로의 이동을 막고 빈 화면을 대신 보여 줘요. 노션에서 돌아오는 일은
 * 서버 callback이 맡아서, 서버가 보내는 주소(`?notionConnection=`)를 직접 열어 대신해요.
 */

// mock 조회는 워크스페이스 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const APPS_PATH = `/workspace/${WORKSPACE_ID}/apps`;
const CONNECTION = new GetSearchNotionConnectionResponseDto(
  searchNotionConnectionResponse,
);
const AUTHORIZATION_URL = new PostSearchNotionConnectionAuthorizationResponseDto(
  searchNotionConnectionAuthorizationResponse,
).authorizationUrl;
// dev mock의 다른 멤버 상황에서 연결한 사람
const OTHER_CONNECTOR = "모카";

const CONSENT_TITLE = "노션을 연결하기 전에 확인해 주세요";
const GUEST_NOTICE = "노션 워크스페이스의 멤버만 연결할 수 있어요";

type NotionConnectionScenario =
  | "notConnected"
  | "connectedByOther"
  | "expired"
  | "expiredOwner"
  | "alreadyConnected";

interface SetScenarioParams {
  page: Page;
  /** 고를 상황. null이면 기본(연결한 사람이 보는 연결됨)으로 돌려요 */
  scenario: NotionConnectionScenario | null;
}

/**
 * 다음에 여는 화면부터 mock 연결 상황을 바꿔요.
 * 초기화 스크립트는 더한 순서대로 돌아서, 나중에 더한 상황이 앞의 상황을 덮어요
 */
const setScenario = ({ page, scenario }: SetScenarioParams) =>
  page.addInitScript(
    ({ key, value }) => {
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    },
    { key: NOTION_CONNECTION_SCENARIO_KEY, value: scenario },
  );

/** 노션 동의 화면으로의 이동을 막고, 간 주소를 모아요 */
const blockNotionAuthorization = async (page: Page) => {
  const visitedUrls: string[] = [];
  await page.route(`${new URL(AUTHORIZATION_URL).origin}/**`, (route) => {
    visitedUrls.push(route.request().url());

    return route.fulfill({
      contentType: "text/html",
      body: "<p>노션 동의 화면</p>",
    });
  });

  return visitedUrls;
};

const getCard = (page: Page) => page.getByRole("article", { name: "노션" });

const getConsentDialog = (page: Page) =>
  page.getByRole("dialog", { name: CONSENT_TITLE });

/** [연결]이나 [다시 연결]을 눌러 보이는 범위 안내에 동의해요 */
const agreeAndConnect = async (card: Locator, buttonName: string) => {
  const page = card.page();
  await card.getByRole("button", { name: buttonName, exact: true }).click();
  const dialog = getConsentDialog(page);
  await expect(
    dialog.getByText(
      "이 워크스페이스 멤버 모두에게 탐색 답변으로 보일 수 있어요.",
      { exact: false },
    ),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "동의하고 연결" }).click();
};

test.describe("연결된 앱 화면", () => {
  test("[SEARCH-057, SEARCH-R29, SEARCH-R30] 보이는 범위 안내에 동의해야 노션 동의 화면으로 넘어가고, 돌아오면 연결된 노션과 연결한 사람이 보인다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "notConnected" });
    const visitedUrls = await blockNotionAuthorization(page);
    await page.goto(APPS_PATH);
    const card = getCard(page);

    // 안내를 보고 그만두면 노션으로 넘어가지 않아요
    await card.getByRole("button", { name: "연결", exact: true }).click();
    await getConsentDialog(page).getByRole("button", { name: "취소" }).click();
    await expect(getConsentDialog(page)).toBeHidden();
    expect(visitedUrls).toEqual([]);

    await agreeAndConnect(card, "연결");
    await expect(page).toHaveURL(AUTHORIZATION_URL);
    expect(visitedUrls).toEqual([AUTHORIZATION_URL]);

    // 노션에서 허용하면 서버가 연결을 마치고 연결된 앱 화면으로 돌려보내요
    await setScenario({ page, scenario: null });
    await page.goto(`${APPS_PATH}?notionConnection=CONNECTED`);

    await expect(
      page.getByText(TOAST_MESSAGES.NOTION_CONNECTED.message),
    ).toBeVisible();
    await expect(page).toHaveURL(APPS_PATH);
    await expect(card.getByText("연결됨", { exact: true })).toBeVisible();
    await expect(
      card.getByText(CONNECTION.notionWorkspaceName ?? "", { exact: true }),
    ).toBeVisible();
    await expect(
      card.getByText(CONNECTION.notionWorkspaceIcon ?? "", { exact: true }),
    ).toBeVisible();
    await expect(
      card.getByText(CONNECTION.connectedBy?.nickname ?? "", { exact: true }),
    ).toBeVisible();
  });

  test("[SEARCH-057, SEARCH-R29] 사이드바 아래 「연결된 앱」으로 연결된 앱 화면에 들어가고 GNB에 지금 위치를 보여 준다", async ({
    page,
  }) => {
    await page.goto(`/workspace/${WORKSPACE_ID}`);

    await page.getByRole("button", { name: "사이드바" }).click();
    await page
      .getByRole("complementary", { name: "워크스페이스 사이드바" })
      .getByRole("link", { name: "연결된 앱" })
      .click();

    await expect(page).toHaveURL(APPS_PATH);
    await expect(
      page.getByRole("heading", { level: 1, name: "연결된 앱" }),
    ).toBeVisible();
    await expect(
      page.getByRole("group", { name: "지금 위치" }),
    ).toContainText("연결된 앱");
  });

  test("[SEARCH-058, SEARCH-R29] 다른 멤버가 연결했어도 연결된 노션과 연결한 사람이 보이고 따로 연결할 버튼이 없다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "connectedByOther" });
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByText(OTHER_CONNECTOR, { exact: true })).toBeVisible();
    await expect(
      card.getByText(CONNECTION.notionWorkspaceName ?? "", { exact: true }),
    ).toBeVisible();
    await expect(
      card.getByText(`${OTHER_CONNECTOR} 님이 노션에서 볼 수 있는 내용 전부`),
    ).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결", exact: true }),
    ).toHaveCount(0);
  });

  test("[SEARCH-059, SEARCH-R30] 노션 게스트라 막혀 돌아오면 연결되지 않고 멤버만 연결할 수 있다고 안내한다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "notConnected" });
    await page.goto(`${APPS_PATH}?notionConnection=NOTION_GUEST_NOT_ALLOWED`);
    const card = getCard(page);

    await expect(card.getByText(GUEST_NOTICE)).toBeVisible();
    await expect(
      card.getByText(
        "노션에서 멤버로 바꿔 달라고 요청하거나, 노션 멤버인 다른 Knot 멤버가 연결해 주세요.",
      ),
    ).toBeVisible();
    await expect(card.getByText("연결 전", { exact: true })).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결", exact: true }),
    ).toBeVisible();
    await expect(page).toHaveURL(APPS_PATH);
  });

  test("[SEARCH-061, SEARCH-R30, SEARCH-R33] 연결돼 있는 동안 [연결]이 없고, 끊은 뒤에는 [연결]로 새로 연결한다", async ({
    page,
  }) => {
    const visitedUrls = await blockNotionAuthorization(page);
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByText("연결됨", { exact: true })).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결", exact: true }),
    ).toHaveCount(0);

    await card.getByRole("button", { name: "연결 끊기" }).click();

    await expect(card.getByText("연결 전", { exact: true })).toBeVisible();
    await agreeAndConnect(card, "연결");
    await expect(page).toHaveURL(AUTHORIZATION_URL);
    expect(visitedUrls).toEqual([AUTHORIZATION_URL]);
  });

  test("[SEARCH-061, SEARCH-R30] 옛 화면에서 [연결]을 눌렀는데 그사이 다른 멤버가 연결했으면 연결된 카드로 바뀐다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "alreadyConnected" });
    const visitedUrls = await blockNotionAuthorization(page);
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await agreeAndConnect(card, "연결");

    await expect(card.getByText(OTHER_CONNECTOR, { exact: true })).toBeVisible();
    await expect(getConsentDialog(page)).toBeHidden();
    await expect(
      card.getByRole("button", { name: "연결", exact: true }),
    ).toHaveCount(0);
    expect(visitedUrls).toEqual([]);
  });

  test("[SEARCH-062, SEARCH-R33] 연결한 사람과 소유자만 [연결 끊기]가 있고, 끊으면 연결되지 않은 상태로 보인다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "connectedByOther" });
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByText(OTHER_CONNECTOR, { exact: true })).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결 끊기" }),
    ).toHaveCount(0);

    await setScenario({ page, scenario: null });
    await page.reload();

    await card.getByRole("button", { name: "연결 끊기" }).click();

    await expect(card.getByText("연결 전", { exact: true })).toBeVisible();
    await expect(
      card.getByText(CONNECTION.notionWorkspaceName ?? "", { exact: true }),
    ).toHaveCount(0);
  });

  test("[SEARCH-063, SEARCH-R33] 연결한 사람이 남아 있으면 연결이 보이고, 서버가 연결을 끊었으면 다른 멤버에게 [연결]이 보인다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "connectedByOther" });
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByText("연결됨", { exact: true })).toBeVisible();

    await setScenario({ page, scenario: "notConnected" });
    await page.reload();

    await expect(card.getByText("연결 전", { exact: true })).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결", exact: true }),
    ).toBeVisible();
  });

  test("[SEARCH-064, SEARCH-R34] 만료되면 연결한 사람이 아닌 멤버에게도 [다시 연결]이 있고, 같은 안내에 동의해야 노션으로 넘어가며 돌아오면 연결한 사람이 바뀐다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "expired" });
    const visitedUrls = await blockNotionAuthorization(page);
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByText("만료", { exact: true })).toBeVisible();
    await expect(card.getByText("노션 연결이 만료됐어요")).toBeVisible();
    await expect(
      card.getByRole("button", { name: "연결 끊기" }),
    ).toHaveCount(0);

    await agreeAndConnect(card, "다시 연결");
    await expect(page).toHaveURL(AUTHORIZATION_URL);
    expect(visitedUrls).toEqual([AUTHORIZATION_URL]);

    await setScenario({ page, scenario: null });
    await page.goto(`${APPS_PATH}?notionConnection=CONNECTED`);

    await expect(card.getByText("연결됨", { exact: true })).toBeVisible();
    await expect(
      card.getByText(CONNECTION.connectedBy?.nickname ?? "", { exact: true }),
    ).toBeVisible();
    await expect(card.getByText(OTHER_CONNECTOR, { exact: true })).toHaveCount(
      0,
    );
  });

  test("[SEARCH-064, SEARCH-R34] 만료된 연결을 연결한 사람과 소유자에게는 [연결 끊기]와 [다시 연결]이 함께 있다", async ({
    page,
  }) => {
    await setScenario({ page, scenario: "expiredOwner" });
    await page.goto(APPS_PATH);
    const card = getCard(page);

    await expect(card.getByRole("button", { name: "연결 끊기" })).toBeVisible();
    await expect(card.getByRole("button", { name: "다시 연결" })).toBeVisible();
  });
});
