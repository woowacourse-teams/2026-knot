import {
  type SearchEvidenceDto,
  type SearchMessageDto,
  SearchNotionEvidenceDto,
  SearchWriteCardDto,
} from "@api/dto/search";
import {
  searchNotionAnswerStreamResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { describe, expect, it } from "vitest";

import { buildSearchTurns } from "./buildSearchTurns";

const CONVERSATION_ID = 10;
const QUESTION_MESSAGE_ID = 3;
const ANSWER_MESSAGE_ID = 4;

const evidence: SearchEvidenceDto = {
  documentId: 101,
  title: "회원 탈퇴 정책",
  topic: "회원 관리",
  rank: 1,
};

const message = (
  id: number,
  role: SearchMessageDto["role"],
  content: string,
  status: SearchMessageDto["status"] = role === "USER"
    ? "RECEIVED"
    : "COMPLETED",
): SearchMessageDto => ({
  id,
  role,
  sequence: id,
  content,
  status,
  createdAt: `2026-10-05T01:00:${String(id).padStart(2, "0")}Z`,
  evidences: [],
  notionEvidences: [],
  notionNotice: null,
  writeCard: null,
});

/** 이미 저장돼 있던 턴 하나 */
const savedMessages = [
  message(1, "USER", "저장된 질문"),
  message(2, "ASSISTANT", "저장된 답변"),
];

type AnswerState = Parameters<typeof buildSearchTurns>[0]["answer"];

const IDLE_ANSWER: AnswerState = {
  status: "IDLE",
  question: "",
  conversationId: null,
  questionMessageId: null,
  answerMessageId: null,
  text: "",
  evidences: [],
  notionEvidences: [],
  notionNotice: null,
  writeCard: null,
  stage: null,
};

const pendingCard = new SearchWriteCardDto(searchWriteCardResponse);
const doneCard = new SearchWriteCardDto({
  ...searchWriteCardResponse,
  status: "DONE",
  createdPageUrl: "https://www.notion.so/knot-team/created",
});

/** 지금 대화에서 질문을 보내 접수를 기다리는 저장소 상태 */
const sendingAnswer = (conversationId: number | null): AnswerState => ({
  ...IDLE_ANSWER,
  status: "SENDING",
  question: "방금 보낸 질문",
  conversationId,
});

/** 접수된 뒤 답변이 흘러드는 저장소 상태 */
const streamingAnswer: AnswerState = {
  ...sendingAnswer(CONVERSATION_ID),
  status: "STREAMING",
  questionMessageId: QUESTION_MESSAGE_ID,
  answerMessageId: ANSWER_MESSAGE_ID,
  text: "흘러드는 답",
  evidences: [evidence],
};

describe("buildSearchTurns", () => {
  it("보낸 질문이 없으면 저장된 턴만 돌려준다 (근거 없음)", () => {
    const turns = buildSearchTurns({
      messages: savedMessages,
      answer: IDLE_ANSWER,
      conversationId: CONVERSATION_ID,
    });

    expect(turns.map(({ question }) => question)).toEqual(["저장된 질문"]);
  });

  it("새 대화에서 첫 질문의 접수를 기다리는 동안 질문만 있는 턴을 붙인다 (SEARCH-R6)", () => {
    const turns = buildSearchTurns({
      messages: [],
      answer: sendingAnswer(null),
    });

    expect(turns).toEqual([
      expect.objectContaining({ question: "방금 보낸 질문", answer: null }),
    ]);
  });

  it("이어 가는 대화에서 보낸 질문은 저장된 턴 뒤에 붙인다 (SEARCH-R6)", () => {
    const turns = buildSearchTurns({
      messages: savedMessages,
      answer: sendingAnswer(CONVERSATION_ID),
      conversationId: CONVERSATION_ID,
    });

    expect(turns.map(({ question }) => question)).toEqual([
      "저장된 질문",
      "방금 보낸 질문",
    ]);
  });

  it("다른 대화에서 보낸 질문은 이 대화에 붙이지 않는다 (근거 없음)", () => {
    const otherConversation = buildSearchTurns({
      messages: savedMessages,
      answer: streamingAnswer,
      conversationId: CONVERSATION_ID + 1,
    });
    const firstQuestionElsewhere = buildSearchTurns({
      messages: savedMessages,
      answer: sendingAnswer(null),
      conversationId: CONVERSATION_ID,
    });

    expect(otherConversation.map(({ question }) => question)).toEqual([
      "저장된 질문",
    ]);
    expect(firstQuestionElsewhere.map(({ question }) => question)).toEqual([
      "저장된 질문",
    ]);
  });

  it("생성 중인 턴의 답변은 저장소의 답변 메시지 ID·텍스트·상태·근거로 채운다 (SEARCH-R1)", () => {
    const turns = buildSearchTurns({
      messages: savedMessages,
      answer: streamingAnswer,
      conversationId: CONVERSATION_ID,
    });

    expect(turns[turns.length - 1].answer).toEqual({
      id: ANSWER_MESSAGE_ID,
      content: "흘러드는 답",
      status: "STREAMING",
      evidences: [evidence],
      notionEvidences: [],
      notionNotice: null,
      writeCard: null,
      stage: null,
    });
  });

  it("생성 중인 턴의 답변은 저장소의 노션 근거, 노션 안내, 쓰기 확인 카드, 진행 단계로 채운다 (SEARCH-R37, SEARCH-R38, SEARCH-R39, SEARCH-R40)", () => {
    const notionEvidences = searchNotionAnswerStreamResponse.notionEvidences.map(
      (notionEvidence) => new SearchNotionEvidenceDto(notionEvidence),
    );

    const turns = buildSearchTurns({
      messages: savedMessages,
      answer: {
        ...streamingAnswer,
        notionEvidences,
        notionNotice: "READ_FAILED",
        writeCard: pendingCard,
        stage: "GENERATING",
      },
      conversationId: CONVERSATION_ID,
    });

    expect(turns[turns.length - 1].answer).toMatchObject({
      notionEvidences,
      notionNotice: "READ_FAILED",
      writeCard: pendingCard,
      stage: "GENERATING",
    });
  });

  it("저장소가 들고 있는 답변의 카드가 조회 결과에도 있으면 조회한 카드를 쓴다. 실행과 취소 뒤 다시 조회한 카드가 더 새롭다 (SEARCH-R50)", () => {
    const turns = buildSearchTurns({
      messages: [
        ...savedMessages,
        message(QUESTION_MESSAGE_ID, "USER", "방금 보낸 질문"),
        {
          ...message(ANSWER_MESSAGE_ID, "ASSISTANT", "흘러드는 답"),
          writeCard: doneCard,
        },
      ],
      answer: { ...streamingAnswer, status: "COMPLETED", writeCard: pendingCard },
      conversationId: CONVERSATION_ID,
    });

    expect(turns[turns.length - 1].answer?.writeCard).toBe(doneCard);
  });

  it("조회 결과의 그 답변에 카드가 아직 없으면 저장소의 카드를 쓴다 (SEARCH-R40)", () => {
    const turns = buildSearchTurns({
      messages: [
        ...savedMessages,
        message(QUESTION_MESSAGE_ID, "USER", "방금 보낸 질문"),
        message(ANSWER_MESSAGE_ID, "ASSISTANT", "저장된 데까지의 답", "STREAMING"),
      ],
      answer: { ...streamingAnswer, writeCard: pendingCard },
      conversationId: CONVERSATION_ID,
    });

    expect(turns[turns.length - 1].answer?.writeCard).toBe(pendingCard);
  });

  it("조회 결과에 생성 중인 질문·답변이 이미 있으면 빼고 저장소의 턴 하나만 맨 뒤에 둔다 (근거 없음)", () => {
    const turns = buildSearchTurns({
      messages: [
        ...savedMessages,
        message(QUESTION_MESSAGE_ID, "USER", "방금 보낸 질문"),
        message(ANSWER_MESSAGE_ID, "ASSISTANT", "저장된 데까지의 답", "STREAMING"),
      ],
      answer: { ...streamingAnswer, status: "COMPLETED" },
      conversationId: CONVERSATION_ID,
    });

    expect(
      turns.map(({ question, answer }) => [question, answer?.content]),
    ).toEqual([
      ["저장된 질문", "저장된 답변"],
      ["방금 보낸 질문", "흘러드는 답"],
    ]);
  });

  it("재시도하는 답변은 접수 전부터 저장된 실패 턴 자리에 그리고, 뒤의 턴은 그대로 둔다 (SEARCH-R5)", () => {
    const failedTurnMessages = [
      message(QUESTION_MESSAGE_ID, "USER", "실패한 질문"),
      message(ANSWER_MESSAGE_ID, "ASSISTANT", "끊긴 답", "FAILED"),
    ];
    const laterMessages = [
      message(5, "USER", "나중 질문"),
      message(6, "ASSISTANT", "나중 답변"),
    ];
    const retryingAnswer: AnswerState = {
      ...sendingAnswer(CONVERSATION_ID),
      question: "실패한 질문",
      questionMessageId: QUESTION_MESSAGE_ID,
      answerMessageId: ANSWER_MESSAGE_ID,
    };

    const turns = buildSearchTurns({
      messages: [...savedMessages, ...failedTurnMessages, ...laterMessages],
      answer: retryingAnswer,
      conversationId: CONVERSATION_ID,
    });

    expect(
      turns.map(({ question, answer }) => [question, answer?.content ?? null]),
    ).toEqual([
      ["저장된 질문", "저장된 답변"],
      ["실패한 질문", null],
      ["나중 질문", "나중 답변"],
    ]);
  });

  it("턴은 답변을 다시 요청할 때 쓰도록 질문 메시지 ID를 담는다. 접수 전 새 질문은 아직 없다 (SEARCH-R5)", () => {
    const savedTurns = buildSearchTurns({
      messages: savedMessages,
      answer: IDLE_ANSWER,
      conversationId: CONVERSATION_ID,
    });
    const [sendingTurn] = buildSearchTurns({
      messages: [],
      answer: sendingAnswer(null),
    });
    const streamingTurns = buildSearchTurns({
      messages: savedMessages,
      answer: streamingAnswer,
      conversationId: CONVERSATION_ID,
    });

    expect(savedTurns.map(({ questionId }) => questionId)).toEqual([1]);
    expect(sendingTurn.questionId).toBeNull();
    expect(streamingTurns[streamingTurns.length - 1].questionId).toBe(
      QUESTION_MESSAGE_ID,
    );
  });

  it("접수 전후로 생성 중인 턴의 key가 같다 (근거 없음)", () => {
    const [sendingTurn] = buildSearchTurns({
      messages: [],
      answer: sendingAnswer(null),
    });
    const [streamingTurn] = buildSearchTurns({
      messages: [],
      answer: { ...streamingAnswer, conversationId: CONVERSATION_ID },
      conversationId: CONVERSATION_ID,
    });

    expect(streamingTurn.key).toBe(sendingTurn.key);
  });
});
