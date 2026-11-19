import type { SearchEvidenceDto, SearchMessageDto } from "@api/dto/search";
import { describe, expect, it } from "vitest";

import { findLatestAnswerWithEvidence } from "./findLatestAnswerWithEvidence";

const evidence: SearchEvidenceDto = {
  documentId: 1,
  title: "회원 탈퇴 정책",
  topic: "회원",
  rank: 1,
};

const message = (
  id: number,
  role: SearchMessageDto["role"],
  evidences: SearchEvidenceDto[] = [],
): SearchMessageDto => ({
  id,
  role,
  sequence: id,
  content: `메시지 ${id}`,
  status: role === "USER" ? "RECEIVED" : "COMPLETED",
  createdAt: `2026-10-05T01:00:${String(id).padStart(2, "0")}Z`,
  evidences,
  notionEvidences: [],
  notionNotice: null,
  writeCard: null,
});

describe("findLatestAnswerWithEvidence", () => {
  it("근거 있는 답변 중 가장 마지막 것을 고른다 (Figma Search/FoundRecords: 닫힌 상태에서 누르면 근거 있는 가장 최근 답변을 연다)", () => {
    const messages = [
      message(1, "USER"),
      message(2, "ASSISTANT", [evidence]),
      message(3, "USER"),
      message(4, "ASSISTANT", [evidence]),
    ];

    expect(findLatestAnswerWithEvidence(messages)?.id).toBe(4);
  });

  it("마지막 답변에 근거가 없으면 그 앞의 근거 있는 답변을 고른다 (Figma Search/FoundRecords: 닫힌 상태에서 누르면 근거 있는 가장 최근 답변을 연다)", () => {
    const messages = [
      message(1, "USER"),
      message(2, "ASSISTANT", [evidence]),
      message(3, "USER"),
      message(4, "ASSISTANT"),
    ];

    expect(findLatestAnswerWithEvidence(messages)?.id).toBe(2);
  });

  it("근거 있는 답변이 없으면 undefined를 돌려준다 (Figma Search/FoundRecords: 닫힌 상태에서 누르면 근거 있는 가장 최근 답변을 연다)", () => {
    const messages = [message(1, "USER"), message(2, "ASSISTANT")];

    expect(findLatestAnswerWithEvidence(messages)).toBeUndefined();
  });

  it("질문(USER)은 고르지 않는다 (Figma Search/FoundRecords: 닫힌 상태에서 누르면 근거 있는 가장 최근 답변을 연다)", () => {
    const messages = [
      message(1, "ASSISTANT", [evidence]),
      message(2, "USER", [evidence]),
    ];

    expect(findLatestAnswerWithEvidence(messages)?.id).toBe(1);
  });

  it("입력 배열의 순서를 바꾸지 않는다 (Figma Search/FoundRecords: 닫힌 상태에서 누르면 근거 있는 가장 최근 답변을 연다)", () => {
    const messages = [
      message(1, "USER"),
      message(2, "ASSISTANT", [evidence]),
      message(3, "USER"),
    ];

    findLatestAnswerWithEvidence(messages);

    expect(messages.map(({ id }) => id)).toEqual([1, 2, 3]);
  });
});
