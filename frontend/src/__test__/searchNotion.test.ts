import {
  SearchNotionEvidenceDto,
  SearchWriteCardDto,
} from "@api/dto/search";
import {
  searchNotionAnswerStreamResponse,
  searchWriteCardCreatedPageUrl,
  searchWriteCardPageResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { expect, test, type Page } from "@playwright/test";

const SCENARIO_KEY = "knot.mock.searchNotion";
const STATE_KEY = "knot.mock.searchNotion.state";
const CHAT_PATH = "/workspace/1/chat";
const APPS_PATH = "/workspace/1/apps";
const CARD_NAME = "노션 페이지 만들기";
const RUNNING_TEXT = "노션에 페이지를 만드는 중이에요.";

const card = new SearchWriteCardDto(searchWriteCardResponse);
const [notionEvidence] = searchNotionAnswerStreamResponse.notionEvidences.map(
  (item) => new SearchNotionEvidenceDto(item),
);

interface SetScenarioParams {
  page: Page;
  scenario: string;
}

/** 새로고침 뒤에도 카드 상태가 이어지도록 시나리오는 비어 있을 때만 적어요 */
const setScenario = async ({ page, scenario }: SetScenarioParams) => {
  await page.addInitScript(
    ([key, value]) => {
      if (!localStorage.getItem(key)) localStorage.setItem(key, value);
    },
    [SCENARIO_KEY, scenario],
  );
};

const send = async (page: Page, question: string) => {
  await page.getByRole("textbox", { name: "무엇이든 요청하세요" }).fill(question);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
};

/** 시나리오 첫 질문을 보내고, 답변이 끝나 다시 받은 대화의 첫 답변 턴을 돌려줘요 */
const askFirstQuestion = async (page: Page, question: string) => {
  await page.goto(CHAT_PATH);
  await send(page, question);
  await page.waitForURL(/\/workspace\/1\/chat\/100$/);

  return page.getByRole("article").first();
};

const countRequests = (page: Page, pathEnd: string) => {
  const counter = { count: 0 };
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith(pathEnd)) counter.count += 1;
  });

  return counter;
};

/** 연결된 앱 화면에서 노션 연결을 마치고 돌아온 것처럼 mock 연결 상태를 연결됨으로 바꿔요 */
const connectNotion = async (page: Page) => {
  await page.evaluate((key) => {
    const state = JSON.parse(localStorage.getItem(key) ?? "{}");
    localStorage.setItem(key, JSON.stringify({ ...state, connection: "CONNECTED" }));
  }, STATE_KEY);
};

test.beforeEach(async ({ context }) => {
  // 새 탭으로 열린 노션 페이지가 실제 노션에 접속하지 않게 막아요
  await context.route("https://www.notion.so/**", (route) =>
    route.fulfill({ contentType: "text/html", body: "<p>노션</p>" }),
  );
});

test("[SEARCH-065][SEARCH-R35, SEARCH-R37] 회의 문서와 노션으로 답하면 노션 페이지가 근거 옆 노션 버튼으로 보이고 누르면 새 탭으로 열린다", async ({ page }) => {
  await setScenario({ page, scenario: "notion-evidence" });
  const turn = await askFirstQuestion(page, "기획서 출시일이 회의 결정과 같아?");

  await expect(turn.getByRole("button", { name: "기록 2개에서 찾았어요" })).toBeVisible();
  const notionLink = turn.getByRole("link", { name: notionEvidence.title });
  await expect(notionLink).toBeVisible();

  const popupPromise = page.waitForEvent("popup");
  await notionLink.click();
  const popup = await popupPromise;
  expect(popup.url()).toBe(notionEvidence.url);
});

test("[SEARCH-067][SEARCH-R38] 기다리는 동안 「찾는 중」에서 「작성 중」으로만 바뀌고 「쓰는 중」은 나오지 않는다", async ({ page }) => {
  await setScenario({ page, scenario: "progress" });
  await page.goto(CHAT_PATH);
  await send(page, "기획서 출시일이 회의 결정과 같아?");

  await expect(page.getByRole("status").filter({ hasText: "찾는 중" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "작성 중" })).toBeVisible();
  await expect(page.getByText("찾는 중")).toHaveCount(0);
  await expect(page.getByText(/쓰는 중/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "기록 3개에서 찾았어요" })).toBeVisible();
  await expect(page.getByText("작성 중")).toHaveCount(0);
});

for (const { id, reason, text, action } of [
  { id: "SEARCH-068", reason: "NOT_CONNECTED", text: "회의 문서만으로 답했어요. 노션을 연결하면 노션 내용도 함께 찾아요.", action: "노션 연결" },
  { id: "SEARCH-068", reason: "PAGE_NOT_FOUND", text: "노션에서는 맞는 페이지를 찾지 못해 회의 문서만으로 답했어요.", action: null },
  { id: "SEARCH-069", reason: "READ_FAILED", text: "노션을 읽지 못해 회의 문서만으로 답했어요.", action: null },
  { id: "SEARCH-064", reason: "CONNECTION_EXPIRED", text: "노션 연결이 만료돼 회의 문서만으로 답했어요.", action: "다시 연결" },
]) {
  test(`[${id}][SEARCH-R39] 노션을 읽지 못한 사유가 ${reason}이면 회의 문서 답 아래에 안내${action ? `와 [${action}]` : ""}를 보여준다`, async ({ page }) => {
    await setScenario({ page, scenario: `notice:${reason}` });
    const turn = await askFirstQuestion(page, "기획서 출시일이 회의 결정과 같아?");

    await expect(turn.getByText(text)).toBeVisible();
    await expect(turn.getByRole("button", { name: "기록 3개에서 찾았어요" })).toBeVisible();

    if (!action) {
      await expect(turn.getByRole("button", { name: /^(노션 연결|다시 연결)$/ })).toHaveCount(0);
      return;
    }

    await turn.getByRole("button", { name: action }).click();
    await expect(page).toHaveURL(APPS_PATH);
  });
}

test("[SEARCH-070][SEARCH-R35, SEARCH-R40, SEARCH-R44] 만들기 요청에는 답글과 대기 확인 카드만 나오고 누르기 전에는 실행하지 않는다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  const executes = countRequests(page, "/execute");
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");

  const cardRegion = turn.getByRole("region", { name: CARD_NAME });
  await expect(cardRegion).toBeVisible();
  await expect(cardRegion.getByRole("button", { name: "만들기" })).toBeEnabled();
  await expect(page.getByRole("region", { name: CARD_NAME })).toHaveCount(1);
  expect(executes.count).toBe(0);
});

test("[SEARCH-071][SEARCH-R41, SEARCH-R45] 카드는 고칠 수 없고, 채팅으로 다시 요청하면 앞선 카드는 취소됨으로 바뀌고 새 카드가 나온다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const firstCard = turn.getByRole("region", { name: CARD_NAME });
  await expect(firstCard).toBeVisible();
  await expect(firstCard.getByRole("textbox")).toHaveCount(0);

  await send(page, "제목을 주간 회의 결정 정리로 바꿔 줘");

  await expect(firstCard.getByText("취소됨")).toBeVisible();
  await expect(firstCard.getByRole("button", { name: "만들기" })).toHaveCount(0);
  const newCard = page.getByRole("region", { name: CARD_NAME }).last();
  await expect(newCard.getByText("주간 회의 결정 정리")).toBeVisible();
  await expect(newCard.getByRole("button", { name: "만들기" })).toBeEnabled();
});

test("[SEARCH-072][SEARCH-R42] 새로고침해도 카드가 같은 내용과 상태로 보이고 대기이면 실행된다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  await expect(page.getByRole("region", { name: CARD_NAME })).toBeVisible();

  await page.reload();

  const cardRegion = page.getByRole("region", { name: CARD_NAME });
  await expect(cardRegion.getByText(card.title, { exact: true })).toBeVisible();
  await cardRegion.getByRole("button", { name: "만들기" }).click();
  await expect(cardRegion.getByText("만들었어요")).toBeVisible();
});

test("[SEARCH-074][SEARCH-R43] [취소]를 누르면 카드가 취소됨으로 바뀌고 다시 실행할 수 없다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await cardRegion.getByRole("button", { name: "취소" }).click();

  await expect(cardRegion.getByText("취소됨")).toBeVisible();
  await expect(cardRegion.getByRole("button", { name: /^(취소|만들기)$/ })).toHaveCount(0);
});

test("[SEARCH-075][SEARCH-R43, SEARCH-R50] [만들기]를 빠르게 두 번 눌러도 실행 요청은 한 번이고 다른 탭도 같은 결과를 본다", async ({ page, context }) => {
  await setScenario({ page, scenario: "card:done" });
  const executes = countRequests(page, "/execute");
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await cardRegion.getByRole("button", { name: "만들기" }).dblclick();

  await expect(cardRegion.getByText("만들었어요")).toBeVisible();
  expect(executes.count).toBe(1);

  const otherTab = await context.newPage();
  await otherTab.goto(page.url());
  await expect(otherTab.getByRole("region", { name: CARD_NAME }).getByText("만들었어요")).toBeVisible();
});

test("[SEARCH-077][SEARCH-R46, SEARCH-R48] 일반 페이지 아래 카드는 위치, 제목, 펼칠 수 있는 내용, 근거, [취소]와 [만들기]를 보여주고 DB 칸은 없다", async ({ page }) => {
  await setScenario({ page, scenario: "card:page" });
  const pageCard = new SearchWriteCardDto(searchWriteCardPageResponse);
  const turn = await askFirstQuestion(page, "11월 6일 회의 결정 사항만 노션 기획 문서 아래에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await expect(cardRegion.getByText(pageCard.location.path, { exact: true })).toBeVisible();
  await expect(cardRegion.getByText(pageCard.title)).toBeVisible();
  await expect(cardRegion.getByText(pageCard.sourceDocument.title)).toBeVisible();
  await expect(cardRegion.getByText("DB 칸")).toHaveCount(0);
  await cardRegion.getByRole("button", { name: "전체 보기" }).click();
  await expect(cardRegion.getByRole("button", { name: "접기" })).toHaveAttribute("aria-expanded", "true");
  await expect(cardRegion.getByRole("button", { name: "취소" })).toBeVisible();
  await expect(cardRegion.getByRole("button", { name: "만들기" })).toBeVisible();
});

test("[SEARCH-078][SEARCH-R49] DB 아래 카드는 채워질 DB 칸과 값을 보여준다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await expect(cardRegion.getByText(`${card.location.path} DB`)).toBeVisible();
  await expect(cardRegion.getByText("날짜  11월 13일")).toBeVisible();
});

test("[SEARCH-079][SEARCH-R50] [만들기]를 누르면 처리 중을 거쳐 「만들었어요」와 [노션에서 열기]로 바뀐다", async ({ page }) => {
  await setScenario({ page, scenario: "card:done" });
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await cardRegion.getByRole("button", { name: "만들기" }).click();

  await expect(cardRegion.getByText(RUNNING_TEXT)).toBeVisible();
  await expect(cardRegion.getByRole("button", { name: "취소" })).toBeDisabled();
  await expect(cardRegion.getByText("만들었어요")).toBeVisible();

  const popupPromise = page.waitForEvent("popup");
  await cardRegion.getByRole("button", { name: "노션에서 열기" }).click();
  expect((await popupPromise).url()).toBe(searchWriteCardCreatedPageUrl);
});

test("[SEARCH-080][SEARCH-R50] 만들 때 DB 구조가 달라졌으면 바로 만들지 않고 DB 칸을 새로 보여준 뒤 다시 누르면 만든다", async ({ page }) => {
  await setScenario({ page, scenario: "card:fields-changed" });
  const executes = countRequests(page, "/execute");
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await cardRegion.getByRole("button", { name: "만들기" }).click();

  await expect(cardRegion.getByText("DB 칸이 바뀌어 미리보기를 새로 불러왔어요. 확인하고 다시 눌러 주세요.")).toBeVisible();
  await expect(cardRegion.getByText("회의 날짜  11월 13일")).toBeVisible();
  await cardRegion.getByRole("button", { name: "만들기" }).click();
  await expect(cardRegion.getByText("만들었어요")).toBeVisible();
  expect(executes.count).toBe(2);
});

test("[SEARCH-081][SEARCH-R51] 같은 대화에서 이미 만든 페이지가 있으면 안내와 [노션에서 열기]를 보여주고 만들기는 막지 않는다", async ({ page }) => {
  await setScenario({ page, scenario: "card:existing" });
  const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 다시 만들어 줘");
  const cardRegion = turn.getByRole("region", { name: CARD_NAME });

  await expect(cardRegion.getByText("이미 만든 페이지가 있어요")).toHaveCount(2);
  await expect(cardRegion.getByRole("button", { name: "노션에서 열기" })).toHaveCount(2);
  await cardRegion.getByRole("button", { name: "만들기" }).click();
  await expect(cardRegion.getByText("만들었어요")).toBeVisible();
});

for (const { id, scenario, title, action } of [
  { id: "[SEARCH-082][SEARCH-085]", scenario: "card:reconnect", title: "노션 연결이 만료됐어요", action: "다시 연결" },
  { id: "[SEARCH-082]", scenario: "card:connection-required", title: "노션이 연결돼 있지 않아요", action: "연결" },
]) {
  test(`${id}[SEARCH-R52] ${title}면 카드가 실패가 아니라 대기로 돌아와 [${action}]을 보여주고, 연결한 뒤 같은 카드로 만든다`, async ({ page }) => {
    await setScenario({ page, scenario });
    const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
    const cardRegion = turn.getByRole("region", { name: CARD_NAME });

    await cardRegion.getByRole("button", { name: "만들기" }).click();

    await expect(cardRegion.getByText(title)).toBeVisible();
    await expect(cardRegion.getByText("만들지 못했어요")).toHaveCount(0);
    await cardRegion.getByRole("button", { name: action, exact: true }).click();
    await expect(page).toHaveURL(APPS_PATH);

    await connectNotion(page);
    await page.goBack();
    // 실제로는 노션 동의 화면을 다녀오며 페이지를 새로 불러와, 앞서 받은 연결 상태가 남지 않아요
    await page.reload();
    await page.getByRole("region", { name: CARD_NAME }).getByRole("button", { name: "만들기" }).click();
    await expect(page.getByRole("region", { name: CARD_NAME }).getByText("만들었어요")).toBeVisible();
  });
}

for (const { id, scenario, text } of [
  { id: "[SEARCH-083][SEARCH-085]", scenario: "card:permission-lost", text: "이 위치에 페이지를 만들 권한이 없어요. 채팅에 다른 위치를 알려 주세요." },
  { id: "[SEARCH-083]", scenario: "card:connection-changed", text: "카드를 만든 뒤 노션 연결이 바뀌었어요. 다시 요청해 주세요." },
]) {
  test(`${id}[SEARCH-R53] 다시 실행할 수 없는 오류는 「만들지 못했어요」와 이유를 보여주고 같은 카드로 다시 실행할 수 없다(${scenario})`, async ({ page }) => {
    await setScenario({ page, scenario });
    const turn = await askFirstQuestion(page, "오늘 회의록 노션 회의록 DB에 만들어 줘");
    const cardRegion = turn.getByRole("region", { name: CARD_NAME });

    await cardRegion.getByRole("button", { name: "만들기" }).click();

    await expect(cardRegion.getByText("만들지 못했어요")).toBeVisible();
    await expect(cardRegion.getByText(text)).toBeVisible();
    await expect(cardRegion.getByRole("button", { name: /^(취소|만들기)$/ })).toHaveCount(0);
  });
}
