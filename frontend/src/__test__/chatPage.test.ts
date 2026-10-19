import { GetDocumentResponseDto } from "@api/dto/document";
import {
  GetSearchConversationsResponseDto,
  GetSearchMessagesResponseDto,
  SearchStreamAcceptedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
} from "@api/dto/search";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { documentDetailsResponse } from "@api/mock/responses/document";
import {
  searchAnswerStreamResponse,
  searchConversationsResponse,
  searchMessagesResponse,
} from "@api/mock/responses/search";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";

/**
 * 탐색 화면(`/workspace/:workspaceId/chat`) E2E. 핵심 여정 「탐색에서 질문하고, 근거가 된 문서와 함께 답을 받는다」.
 *
 * 답변 스트림과 대화 기록은 dev 서버의 msw mock 응답(`API_MOCKING`)에서 오므로
 * 기대값도 같은 응답을 DTO로 변환해 가져와요.
 */

const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const CHAT_PATH = `/workspace/${WORKSPACE_ID}/chat`;
const documentPath = (documentId: number) =>
  `/workspace/${WORKSPACE_ID}/documents/${documentId}`;

const QUESTION = "DB는 무엇으로 정했어?";
// 첫 질문의 답변은 조각(delta)으로 도착해 화면에서 한 문단으로 이어 붙어요
const accepted = new SearchStreamAcceptedDto(searchAnswerStreamResponse);
const ANSWER = searchAnswerStreamResponse.deltas
  .map(
    (text) =>
      new SearchStreamDeltaDto({
        answerMessageId: accepted.answerMessageId,
        text,
      }).text,
  )
  .join("");
const EVIDENCES = new SearchStreamEvidenceDto({
  answerMessageId: accepted.answerMessageId,
  items: searchAnswerStreamResponse.evidences,
}).items;

// 대화 목록의 첫 대화를 열면 이미 오간 메시지(메시지 조회 mock)가 보여요
const [PAST_CONVERSATION] = new GetSearchConversationsResponseDto(
  searchConversationsResponse,
).items;
const pastMessages = new GetSearchMessagesResponseDto(searchMessagesResponse)
  .items;
// 그 가운데 근거 문서의 상세 mock이 있는 답변을 골라, 근거 카드를 눌렀을 때 문서가 열리게 해요
const documents = documentDetailsResponse.map(
  (document) => new GetDocumentResponseDto(document),
);
const findOpenableDocument = (documentId: number) =>
  documents.find(({ id }) => id === documentId);
const answerIndex = pastMessages.findIndex(({ evidences }) =>
  evidences.some(({ documentId }) => findOpenableDocument(documentId)),
);
const PAST_ANSWER = pastMessages[answerIndex];
// 답변 바로 앞의 메시지가 그 답변을 받은 질문이에요
const PAST_QUESTION = pastMessages[answerIndex - 1];
const EVIDENCE = PAST_ANSWER.evidences.find(({ documentId }) =>
  findOpenableDocument(documentId),
)!;
const EVIDENCE_DOCUMENT = findOpenableDocument(EVIDENCE.documentId)!;

const getQuestionField = (page: Page) =>
  page.getByRole("textbox", { name: "무엇이든 요청하세요" });

const getConversation = (page: Page) =>
  page.getByRole("region", { name: "대화" });

const getEvidencePanel = (page: Page) =>
  page.getByRole("region", { name: "찾은 기록" });

test.beforeEach(async ({ page }) => {
  await page.goto(CHAT_PATH);
  await expect(
    page.getByRole("heading", { name: "무엇을 찾고 있나요?" }),
  ).toBeVisible();
});

test.describe("질문과 답변", () => {
  test(
    "[SEARCH-R6] 질문을 입력하고 Enter로 보내면 답변과 근거가 된 기록을 받는다",
    { tag: ["@core"] },
    async ({ page }) => {
      await getQuestionField(page).fill(QUESTION);
      await getQuestionField(page).press("Enter");

      await expect(page).toHaveURL(
        new RegExp(`${CHAT_PATH}/${accepted.conversationId}(\\?|$)`),
      );
      const turn = getConversation(page)
        .getByRole("article")
        .filter({ hasText: QUESTION });
      await expect(turn.getByText(ANSWER)).toBeVisible();
      await expect(
        turn.getByRole("button", {
          name: `기록 ${EVIDENCES.length}개에서 찾았어요`,
        }),
      ).toBeVisible();

      // 방금 받은 답변의 근거는 찾은 기록 패널에 관련도 순서대로 문서 보기 링크로 놓여요
      const evidenceLinks = getEvidencePanel(page).getByRole("link");
      await expect(evidenceLinks).toHaveCount(EVIDENCES.length);
      for (const [index, { documentId, title }] of EVIDENCES.entries()) {
        await expect(evidenceLinks.nth(index)).toContainText(title);
        await expect(evidenceLinks.nth(index)).toHaveAttribute(
          "href",
          documentPath(documentId),
        );
      }
      await expect(getQuestionField(page)).toHaveValue("");
    },
  );

  test(
    "[SEARCH-R10] 답변의 근거 카드를 누르면 그 문서 보기로 이동한다",
    { tag: ["@core"] },
    async ({ page }) => {
      await page.getByRole("button", { name: "대화 목록" }).click();
      await page
        .getByRole("complementary", { name: "대화 목록" })
        .getByRole("button", {
          name: new RegExp(`^${PAST_CONVERSATION.title}`),
        })
        .click();

      const turn = getConversation(page)
        .getByRole("article")
        .filter({ hasText: PAST_QUESTION.content });
      await turn
        .getByRole("button", {
          name: `기록 ${PAST_ANSWER.evidences.length}개에서 찾았어요`,
        })
        .click();
      // 근거 제목이 서로의 앞부분일 수 있어(회원 탈퇴 정책, 회원 탈퇴 정책 논의) 제목이 꼭 맞는 카드를 골라요
      await getEvidencePanel(page)
        .getByRole("link")
        .filter({ has: page.getByText(EVIDENCE.title, { exact: true }) })
        .click();

      await expect(page).toHaveURL(documentPath(EVIDENCE_DOCUMENT.id));
      await expect(
        page.getByRole("heading", { name: EVIDENCE_DOCUMENT.title, level: 2 }),
      ).toBeVisible();
    },
  );
});
