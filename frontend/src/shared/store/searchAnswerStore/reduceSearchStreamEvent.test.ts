import {
  SearchStreamAcceptedDto,
  SearchStreamCompletedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
  SearchStreamExcludedDocumentsDto,
  SearchStreamNotionEvidenceDto,
  SearchStreamNotionNoticeDto,
  SearchStreamProgressDto,
  SearchStreamSnapshotDto,
  SearchStreamWriteCardDto,
} from "@api/dto/search";
import {
  searchAnswerStreamResponse,
  searchNotionAnswerStreamResponse,
  searchWriteCardResponse,
} from "@api/mock/responses/search";
import { describe, expect, it } from "vitest";

import type { SearchAnswer } from "@/shared/types/searchAnswer";

import { reduceSearchStreamEvent } from "./reduceSearchStreamEvent";

const { conversationId, questionMessageId, answerMessageId, deltas, evidences } =
  searchAnswerStreamResponse;

const sendingAnswer: SearchAnswer = {
  status: "SENDING",
  question: "DB는 뭘로 정했어?",
  conversationId: null,
  questionMessageId: null,
  answerMessageId: null,
  text: "",
  evidences: [],
  stage: null,
  excludedDocumentCount: 0,
  failedCode: null,
  notionEvidences: [],
  notionNotice: null,
  writeCard: null,
};

const streamingAnswer: SearchAnswer = {
  ...sendingAnswer,
  status: "STREAMING",
  conversationId,
  questionMessageId,
  answerMessageId,
};

const accepted = new SearchStreamAcceptedDto({
  conversationId,
  questionMessageId,
  answerMessageId,
});

describe("reduceSearchStreamEvent", () => {
  // SEARCH-R6 접수 대기와 생성 중 구분
  it("[SEARCH-R6] 접수 대기 중에 accepted를 받으면 대화·메시지 ID를 담고 생성 중으로 바꾼다", () => {
    expect(
      reduceSearchStreamEvent(sendingAnswer, { event: "accepted", data: accepted }),
    ).toEqual({
      ...sendingAnswer,
      status: "STREAMING",
      conversationId: accepted.conversationId,
      questionMessageId: accepted.questionMessageId,
      answerMessageId: accepted.answerMessageId,
    });
  });

  it("[SEARCH-R6] 이미 생성 중이면 accepted를 다시 받아도 바꾸지 않는다", () => {
    const otherAccepted = new SearchStreamAcceptedDto({
      conversationId: conversationId + 1,
      questionMessageId: questionMessageId + 10,
      answerMessageId: answerMessageId + 10,
    });

    expect(
      reduceSearchStreamEvent(streamingAnswer, { event: "accepted", data: otherAccepted }),
    ).toBe(streamingAnswer);
  });

  // SEARCH-R1 문서 근거 답변
  it("[SEARCH-R1] delta를 도착 순서대로 이어 붙인다", () => {
    const answer = deltas.reduce(
      (current, text) =>
        reduceSearchStreamEvent(current, {
          event: "delta",
          data: new SearchStreamDeltaDto({ answerMessageId, text }),
        }),
      streamingAnswer,
    );

    expect(answer.text).toBe(deltas.join(""));
  });

  it("[SEARCH-R1] 다른 답변 메시지의 delta는 이어 붙이지 않는다", () => {
    const answer = reduceSearchStreamEvent(streamingAnswer, {
      event: "delta",
      data: new SearchStreamDeltaDto({ answerMessageId: answerMessageId + 1, text: deltas[0] }),
    });

    expect(answer).toBe(streamingAnswer);
  });

  it("[SEARCH-R6] accepted보다 먼저 도착한 delta는 반영하지 않는다", () => {
    const answer = reduceSearchStreamEvent(sendingAnswer, {
      event: "delta",
      data: new SearchStreamDeltaDto({ answerMessageId, text: deltas[0] }),
    });

    expect(answer).toBe(sendingAnswer);
  });

  // SEARCH-R2 근거 최대 3개
  it("[SEARCH-R2] evidence의 근거 문서를 담는다", () => {
    const evidence = new SearchStreamEvidenceDto({ answerMessageId, items: evidences });

    expect(
      reduceSearchStreamEvent(streamingAnswer, { event: "evidence", data: evidence }).evidences,
    ).toEqual(evidence.items);
  });

  it("[SEARCH-R2] 다른 답변 메시지의 evidence는 담지 않는다", () => {
    const evidence = new SearchStreamEvidenceDto({
      answerMessageId: answerMessageId + 1,
      items: evidences,
    });

    expect(reduceSearchStreamEvent(streamingAnswer, { event: "evidence", data: evidence })).toBe(
      streamingAnswer,
    );
  });

  it("[SEARCH-R37] notion_evidence의 노션 근거를 담는다", () => {
    const notionEvidence = new SearchStreamNotionEvidenceDto({
      answerMessageId,
      items: searchNotionAnswerStreamResponse.notionEvidences,
    });

    expect(
      reduceSearchStreamEvent(streamingAnswer, {
        event: "notion_evidence",
        data: notionEvidence,
      }).notionEvidences,
    ).toEqual(notionEvidence.items);
  });

  it("[SEARCH-R39] notion_notice의 노션을 읽지 못한 사유를 담는다", () => {
    const notionNotice = new SearchStreamNotionNoticeDto({
      answerMessageId,
      reason: "CONNECTION_EXPIRED",
    });

    expect(
      reduceSearchStreamEvent(streamingAnswer, {
        event: "notion_notice",
        data: notionNotice,
      }).notionNotice,
    ).toBe(notionNotice.reason);
  });

  it("[SEARCH-R40] write_card의 쓰기 확인 카드를 담는다", () => {
    const writeCard = new SearchStreamWriteCardDto({
      answerMessageId,
      card: searchWriteCardResponse,
    });

    expect(
      reduceSearchStreamEvent(streamingAnswer, {
        event: "write_card",
        data: writeCard,
      }).writeCard,
    ).toEqual(writeCard.card);
  });

  it("[SEARCH-R37, SEARCH-R39, SEARCH-R40] 다른 답변 메시지의 노션 근거, 안내, 카드는 담지 않는다", () => {
    const otherAnswerMessageId = answerMessageId + 1;
    const events = [
      {
        event: "notion_evidence" as const,
        data: new SearchStreamNotionEvidenceDto({
          answerMessageId: otherAnswerMessageId,
          items: searchNotionAnswerStreamResponse.notionEvidences,
        }),
      },
      {
        event: "notion_notice" as const,
        data: new SearchStreamNotionNoticeDto({
          answerMessageId: otherAnswerMessageId,
          reason: "NOT_CONNECTED",
        }),
      },
      {
        event: "write_card" as const,
        data: new SearchStreamWriteCardDto({
          answerMessageId: otherAnswerMessageId,
          card: searchWriteCardResponse,
        }),
      },
    ];

    for (const event of events) {
      expect(reduceSearchStreamEvent(streamingAnswer, event)).toBe(
        streamingAnswer,
      );
    }
  });

  it("[SEARCH-R9] excluded_documents의 빠진 문서 수를 담는다", () => {
    const excluded = new SearchStreamExcludedDocumentsDto({ count: 2 });

    expect(
      reduceSearchStreamEvent(streamingAnswer, { event: "excluded_documents", data: excluded })
        .excludedDocumentCount,
    ).toBe(excluded.count);
  });

  // SEARCH-R9 진행 표시
  it("[BE 계약 progress.stage] progress의 진행 단계를 마지막으로 받은 값으로 바꾼다", () => {
    const { stages } = searchAnswerStreamResponse;
    const answer = stages.reduce(
      (current, stage) =>
        reduceSearchStreamEvent(current, {
          event: "progress",
          data: new SearchStreamProgressDto({ stage }),
        }),
      streamingAnswer,
    );

    expect(answer.stage).toBe(stages[stages.length - 1]);
  });

  it("[SEARCH-R8] 생성 중에 받는 답변의 snapshot을 받으면 받던 본문을 저장된 본문으로 바꾼다", () => {
    const receivedAnswer = { ...streamingAnswer, text: deltas[0] };
    const snapshot = new SearchStreamSnapshotDto({
      answerId: answerMessageId,
      content: deltas.slice(0, 3).join(""),
      status: "STREAMING",
    });

    expect(
      reduceSearchStreamEvent(receivedAnswer, { event: "snapshot", data: snapshot }),
    ).toEqual({ ...receivedAnswer, text: snapshot.content });
  });

  it("[SEARCH-R8] 다른 답변의 snapshot은 반영하지 않는다", () => {
    const snapshot = new SearchStreamSnapshotDto({
      answerId: answerMessageId + 1,
      content: "다른 답변",
      status: "STREAMING",
    });

    expect(
      reduceSearchStreamEvent(streamingAnswer, { event: "snapshot", data: snapshot }),
    ).toBe(streamingAnswer);
  });

  it("끝 이벤트는 뮤테이션 결과로 판정하므로 반영하지 않는다", () => {
    const completed = new SearchStreamCompletedDto({ answerMessageId, status: "COMPLETED" });

    expect(reduceSearchStreamEvent(streamingAnswer, { event: "completed", data: completed })).toBe(
      streamingAnswer,
    );
  });
});
