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

import { toSearchTurns } from "./toSearchTurns";

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
): SearchMessageDto => ({
  id,
  role,
  sequence: id,
  content,
  status: role === "USER" ? "RECEIVED" : "COMPLETED",
  createdAt: `2026-10-05T01:00:${String(id).padStart(2, "0")}Z`,
  evidences: role === "USER" ? [] : [evidence],
  notionEvidences: [],
  notionNotice: null,
  writeCard: null,
});

describe("toSearchTurns", () => {
  it("메시지가 없으면 빈 배열을 돌려준다 (근거 없음)", () => {
    expect(toSearchTurns([])).toEqual([]);
  });

  it("질문과 답변 한 쌍을 턴 한 개로 묶고, 턴은 질문 ID를, 답변은 ID, 본문, 상태, 근거, 노션 근거, 노션 안내, 카드를 담는다 (근거 없음)", () => {
    const question = message(
      1,
      "USER",
      "탈퇴한 사용자 게시글은 어떻게 처리해?",
    );
    const answer = message(
      2,
      "ASSISTANT",
      "작성자를 ‘탈퇴한 사용자’로 표시해요.",
    );

    expect(toSearchTurns([question, answer])).toEqual([
      {
        key: "1",
        questionId: question.id,
        question: question.content,
        answer: {
          id: answer.id,
          content: answer.content,
          status: answer.status,
          evidences: answer.evidences,
          notionEvidences: answer.notionEvidences,
          notionNotice: answer.notionNotice,
          writeCard: answer.writeCard,
          stage: null,
        },
      },
    ]);
  });

  it("저장된 답변의 노션 근거, 노션 안내, 쓰기 확인 카드를 그대로 담는다 (SEARCH-R37, SEARCH-R39, SEARCH-R42)", () => {
    const answer: SearchMessageDto = {
      ...message(2, "ASSISTANT", "노션과 함께 찾은 답"),
      notionEvidences: searchNotionAnswerStreamResponse.notionEvidences.map(
        (notionEvidence) => new SearchNotionEvidenceDto(notionEvidence),
      ),
      notionNotice: "PAGE_NOT_FOUND",
      writeCard: new SearchWriteCardDto(searchWriteCardResponse),
    };

    const [turn] = toSearchTurns([message(1, "USER", "질문"), answer]);

    expect(turn.answer).toMatchObject({
      notionEvidences: answer.notionEvidences,
      notionNotice: answer.notionNotice,
      writeCard: answer.writeCard,
    });
  });

  it("여러 쌍을 받은 순서대로 턴으로 묶는다 (근거 없음)", () => {
    const turns = toSearchTurns([
      message(1, "USER", "첫 질문"),
      message(2, "ASSISTANT", "첫 답변"),
      message(3, "USER", "둘째 질문"),
      message(4, "ASSISTANT", "둘째 답변"),
    ]);

    expect(
      turns.map(({ question, answer }) => [question, answer?.content]),
    ).toEqual([
      ["첫 질문", "첫 답변"],
      ["둘째 질문", "둘째 답변"],
    ]);
  });

  it("답변이 아직 없는 질문은 답변이 null인 턴이 된다 (근거 없음)", () => {
    const turns = toSearchTurns([
      message(1, "USER", "첫 질문"),
      message(2, "ASSISTANT", "첫 답변"),
      message(3, "USER", "방금 보낸 질문"),
    ]);

    expect(turns[turns.length - 1]).toEqual({
      key: "3",
      questionId: 3,
      question: "방금 보낸 질문",
      answer: null,
    });
  });

  it("답변 없이 질문이 연달아 있으면 각각 별도의 턴이 된다 (근거 없음)", () => {
    const turns = toSearchTurns([
      message(1, "USER", "답변을 못 받은 질문"),
      message(2, "USER", "다시 보낸 질문"),
      message(3, "ASSISTANT", "답변"),
    ]);

    expect(turns.map(({ answer }) => answer?.id ?? null)).toEqual([null, 3]);
  });

  it("짝이 없는 답변은 턴을 만들지 않고 건너뛴다 (근거 없음)", () => {
    const turns = toSearchTurns([
      message(1, "ASSISTANT", "질문보다 먼저 온 답변"),
      message(2, "USER", "질문"),
      message(3, "ASSISTANT", "답변"),
    ]);

    expect(turns.map(({ key, answer }) => [key, answer?.id])).toEqual([
      ["2", 3],
    ]);
  });

  it("한 턴에 답변이 두 번 오면 첫 답변만 쓴다 (근거 없음)", () => {
    const turns = toSearchTurns([
      message(1, "USER", "질문"),
      message(2, "ASSISTANT", "첫 답변"),
      message(3, "ASSISTANT", "덧붙인 답변"),
    ]);

    expect(turns.map(({ answer }) => answer?.content)).toEqual(["첫 답변"]);
  });
});
