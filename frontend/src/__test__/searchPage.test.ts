import { SearchStreamDeltaDto, SearchStreamEvidenceDto } from "@api/dto/search";
import { searchQuestionAnswerStreamResponse, searchGeneralAnswerStreamResponse } from "@api/mock/responses/search";
import { expect, test, type Page } from "@playwright/test";

const GUIDANCE = "지금은 답변을 정리하기 어려워요. 질문을 조금 더 구체적으로 이어서 해주시면 도와드릴게요.";
const PARTIAL = "검증되지 않은 임시 답변";

interface SetScenarioParams { page: Page; scenario: string; }
interface SendParams { page: Page; question: string; }
const setScenario = async ({ page, scenario }: SetScenarioParams) => {
  await page.addInitScript((value) => localStorage.setItem("knot.mock.searchChat", value), scenario);
};
const send = async ({ page, question }: SendParams) => {
  const field = page.getByRole("textbox", { name: "무엇이든 요청하세요" });
  await field.fill(question);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
};

for (const scenario of ["legacy", "failed", "disconnected", "resume500"]) {
  test(`[SEARCH-R5,SEARCH-R8] ${scenario} 상태는 안내 문단 뒤 후속 질문을 이어간다`, async ({ page }) => {
    await setScenario({ page, scenario });
    let posts = 0;
    let resumes = 0;
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().includes("/search/conversations")) posts += 1;
      if (request.url().endsWith("/events")) resumes += 1;
    });
    await page.goto(scenario === "legacy" ? "/workspace/1/chat/10" : "/workspace/1/chat");
    if (scenario !== "legacy") await send({ page, question: "안녕하세요" });
    await expect(page.getByText(GUIDANCE)).toBeVisible();
    await expect(page.getByText(PARTIAL)).toHaveCount(0);
    await expect(page.getByText("답변을 만들지 못했어요.", { exact: false })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "다시 시도", exact: true })).toHaveCount(0);
    expect(posts).toBe(scenario === "legacy" ? 0 : 1);
    if (scenario === "disconnected" || scenario === "resume500") expect(resumes).toBe(3);
    if (scenario === "legacy" || scenario === "failed") {
      await page.reload();
      await expect(page.getByText(GUIDANCE)).toBeVisible();
      await expect(page.getByText(PARTIAL)).toHaveCount(0);
      expect(posts).toBe(scenario === "legacy" ? 0 : 1);
    }
    await send({ page, question: "관련 기록을 알려줘" });
    await expect(page.getByText(searchQuestionAnswerStreamResponse.deltas.map((text) => new SearchStreamDeltaDto({ answerMessageId: searchQuestionAnswerStreamResponse.answerMessageId, text }).text).join(""))).toBeVisible();
    await expect(page.getByRole("button", { name: `기록 ${new SearchStreamEvidenceDto({ answerMessageId: searchQuestionAnswerStreamResponse.answerMessageId, items: searchQuestionAnswerStreamResponse.evidences }).items.length}개에서 찾았어요` })).toBeVisible();
    expect(posts).toBe(scenario === "legacy" ? 1 : 2);
  });
}

test("[SEARCH-R4] 접수 전 오류는 입력을 복원하고 자동으로 재전송하지 않는다", async ({ page }) => {
  await setScenario({ page, scenario: "preaccept" });
  await page.goto("/workspace/1/chat");
  const question = "복원할 질문";
  let posts = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/search/conversations")) posts += 1;
  });
  await send({ page, question });
  await expect(page.getByRole("textbox", { name: "무엇이든 요청하세요" })).toHaveValue(question);
  await expect(page.getByText(GUIDANCE)).toHaveCount(0);
  expect(posts).toBe(1);
});

test("[SEARCH-R1,SEARCH-R2,SEARCH-R3] 일반 대화는 출처 버튼 없이 정상 답변을 보여준다", async ({ page }) => {
  await setScenario({ page, scenario: "general" });
  await page.goto("/workspace/1/chat");
  await send({ page, question: "안녕하세요" });
  const expected = searchGeneralAnswerStreamResponse.deltas.map((text) =>
    new SearchStreamDeltaDto({ answerMessageId: searchGeneralAnswerStreamResponse.answerMessageId, text }).text,
  ).join("");
  await expect(page.getByRole("article").last().getByRole("paragraph")).toHaveText(expected);
  await expect(page.getByText("답변을 준비하고 있어요…")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /기록 .*개에서 찾았어요/ })).toHaveCount(0);
  await expect(page.getByText(GUIDANCE)).toHaveCount(0);
});
