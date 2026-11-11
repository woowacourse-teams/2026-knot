import { describe, expect, it } from "vitest";

import { GetMeResponseDto, PostNicknameRequestDto } from "./auth";
import { GetChatMessagesResponseDto } from "./chatMessage";
import {
  ConfirmationSummaryDto,
  DocumentConfirmationItemDto,
  DocumentListItemDto,
  DocumentTopicDto,
  DocumentTranscriptSegmentDto,
  GetDocumentConfirmationsResponseDto,
  GetDocumentResponseDto,
  GetDocumentsResponseDto,
  GetDocumentTranscriptResponseDto,
  PutDocumentConfirmationResponseDto,
} from "./document";
import {
  RecordingAudioFrameRangeDto,
  RecordingSourceAudioDto,
} from "./recording";
import {
  GetOwnershipTransferCandidatesResponseDto,
  GetWorkspacesResponseDto,
  OwnershipTransferCandidateDto,
  PostOwnershipTransferRequestDto,
  PostWorkspaceRequestDto,
  PutLastViewedWorkspaceRequestDto,
  WorkspaceListItemDto,
} from "./workspace";
import {
  GetWorkspaceInvitationResponseDto,
  PostInvitationAcceptRequestDto,
  PostWorkspaceInvitationReissueResponseDto,
  PostWorkspaceInvitationResponseDto,
} from "./workspaceInvitation";

describe("DTO 생성자 변환", () => {
  describe("워크스페이스", () => {
    it("목록 응답의 항목을 WorkspaceListItemDto로 감싼다", () => {
      const dto = new GetWorkspacesResponseDto({
        lastViewedWorkspaceId: null,
        workspaces: [{ id: 1, name: "Knot 팀" }],
      });

      expect(dto.lastViewedWorkspaceId).toBeNull();
      expect(dto.workspaces[0]).toBeInstanceOf(WorkspaceListItemDto);
      expect(dto.workspaces[0]).toEqual({ id: 1, name: "Knot 팀" });
    });

    it("생성 요청 본문의 이름은 앞뒤 공백을 제거한다", () => {
      expect(new PostWorkspaceRequestDto({ name: "  Knot 팀 " })).toEqual({
        name: "Knot 팀",
      });
    });

    it("마지막 본 워크스페이스 요청 본문은 workspaceId만 담는다", () => {
      expect(new PutLastViewedWorkspaceRequestDto({ workspaceId: 1 })).toEqual({
        workspaceId: 1,
      });
    });

    it("승계 후보 응답의 배열을 candidates 필드에 OwnershipTransferCandidateDto로 담는다", () => {
      const dto = new GetOwnershipTransferCandidatesResponseDto([
        { memberId: 2, nickname: "흑곰" },
      ]);

      expect(dto.candidates[0]).toBeInstanceOf(OwnershipTransferCandidateDto);
      expect(dto.candidates).toEqual([{ memberId: 2, nickname: "흑곰" }]);
    });

    it("승계 요청 본문은 successorMemberId만 담는다", () => {
      expect(
        new PostOwnershipTransferRequestDto({ successorMemberId: 2 }),
      ).toEqual({ successorMemberId: 2 });
    });
  });

  describe("인증", () => {
    it("회원 정보 응답 필드를 그대로 옮긴다", () => {
      const raw = {
        memberId: 1,
        nickname: "노티드",
        profileImageUrl: "https://example.com/1.png",
      };

      expect(new GetMeResponseDto(raw)).toEqual(raw);
    });

    it("닉네임 요청 본문은 nickname만 담는다", () => {
      expect(new PostNicknameRequestDto({ nickname: "노티드" })).toEqual({
        nickname: "노티드",
      });
    });
  });

  describe("워크스페이스 초대", () => {
    const raw = {
      code: "X35D3S",
      linkToken: "Xk3vQ9mZp2LrT7wB1nHc4A",
      expiresAt: "2026-09-08T00:00:00.000Z",
    };

    it("조회·발급·재발급 응답이 같은 초대 모양을 돌려준다", () => {
      expect(new GetWorkspaceInvitationResponseDto(raw)).toEqual(raw);
      expect(new PostWorkspaceInvitationResponseDto(raw)).toEqual(raw);
      expect(new PostWorkspaceInvitationReissueResponseDto(raw)).toEqual(raw);
    });

    it("참여 요청 본문은 credential만 담는다", () => {
      expect(
        new PostInvitationAcceptRequestDto({ credential: "X35D3S" }),
      ).toEqual({ credential: "X35D3S" });
    });
  });

  describe("대화", () => {
    it("메시지 목록 응답을 messages 필드에 담는다", () => {
      const message = {
        id: 1001,
        role: "USER" as const,
        content: "안녕",
        createdAt: "2026-09-01T00:00:00.000Z",
      };

      expect(new GetChatMessagesResponseDto([message]).messages).toEqual([
        message,
      ]);
    });
  });

  describe("문서", () => {
    it("목록 응답의 주제를 DocumentTopicDto로, 문서를 DocumentListItemDto로, 문서의 확인 집계를 ConfirmationSummaryDto로 감싸고 나머지 필드는 그대로 옮긴다", () => {
      const raw = {
        topics: [{ topic: "회원", documentCount: 12 }],
        items: [
          {
            id: 301,
            recordingSessionId: 42,
            topic: "회원",
            title: "회원 탈퇴 정책",
            summary: null,
            status: "DRAFT" as const,
            createdAt: "2026-09-15T03:00:00.000Z",
            recordingDurationSeconds: 1920,
            myConfirmationState: "PENDING" as const,
            confirmationSummary: {
              confirmedCount: 1,
              pendingCount: 2,
              excludedCount: 0,
            },
          },
        ],
        nextCursor: "opaque-cursor-value",
      };

      const dto = new GetDocumentsResponseDto(raw);

      expect(dto.topics[0]).toBeInstanceOf(DocumentTopicDto);
      expect(dto.items[0]).toBeInstanceOf(DocumentListItemDto);
      expect(dto.items[0].confirmationSummary).toBeInstanceOf(
        ConfirmationSummaryDto,
      );
      expect(dto).toEqual(raw);
    });

    it("상세 응답의 확인 집계를 ConfirmationSummaryDto로 감싸고 나머지 필드는 그대로 옮기며, sourceAudio를 주지 않는 서버면 null로 맞춘다", () => {
      const raw = {
        id: 101,
        recordingSessionId: 42,
        topic: "회원",
        title: "회원 탈퇴 정책",
        summary: null,
        content: "## 결정\n탈퇴한 사용자의 게시글은 유지해요.",
        status: "DRAFT" as const,
        createdAt: "2026-09-15T03:00:00.000Z",
        archivedAt: null,
        recordingDurationSeconds: 1920,
        sourceTranscriptId: 81,
        myConfirmationState: "PENDING" as const,
        confirmationSummary: {
          confirmedCount: 2,
          pendingCount: 2,
          excludedCount: 0,
        },
      };

      const dto = new GetDocumentResponseDto(raw);

      expect(dto.confirmationSummary).toBeInstanceOf(ConfirmationSummaryDto);
      expect(dto).toEqual({ ...raw, sourceAudio: null });
    });

    it("상세 응답의 원본 오디오 품질을 RecordingSourceAudioDto로, 누락 구간을 RecordingAudioFrameRangeDto로 감싼다", () => {
      const sourceAudio = {
        quality: "PARTIAL" as const,
        missingRanges: [{ start: 80000, endExclusive: 160000 }],
        tailUnknown: false,
      };

      const dto = new GetDocumentResponseDto({
        id: 101,
        recordingSessionId: 42,
        topic: "회원",
        title: "회원 탈퇴 정책",
        summary: null,
        content: "## 결정\n탈퇴한 사용자의 게시글은 유지해요.",
        status: "DRAFT",
        createdAt: "2026-09-15T03:00:00.000Z",
        archivedAt: null,
        recordingDurationSeconds: 1920,
        sourceTranscriptId: 81,
        myConfirmationState: "PENDING",
        confirmationSummary: {
          confirmedCount: 2,
          pendingCount: 2,
          excludedCount: 0,
        },
        sourceAudio,
      });

      expect(dto.sourceAudio).toBeInstanceOf(RecordingSourceAudioDto);
      expect(dto.sourceAudio?.missingRanges[0]).toBeInstanceOf(
        RecordingAudioFrameRangeDto,
      );
      expect(dto.sourceAudio).toEqual(sourceAudio);
    });

    it("확인 대상 조회 응답의 대상을 DocumentConfirmationItemDto로 감싸고, 집계 세 값은 ConfirmationSummaryDto로 묶고, confirmedByMe는 옮기지 않는다", () => {
      const items = [
        {
          memberId: 2,
          nickname: "도넛",
          profileImageUrl: null,
          confirmedAt: "2026-09-15T04:00:00.000Z",
          state: "CONFIRMED" as const,
        },
        {
          memberId: 1,
          nickname: "노티드",
          profileImageUrl: "https://example.com/profiles/1.png",
          confirmedAt: null,
          state: "PENDING" as const,
        },
      ];

      const dto = new GetDocumentConfirmationsResponseDto({
        documentId: 101,
        confirmedCount: 1,
        pendingCount: 1,
        excludedCount: 0,
        confirmedByMe: false,
        items,
        nextCursor: null,
      });

      expect(dto.items[0]).toBeInstanceOf(DocumentConfirmationItemDto);
      expect(dto.confirmationSummary).toBeInstanceOf(ConfirmationSummaryDto);
      expect(dto).toEqual({
        documentId: 101,
        confirmationSummary: {
          confirmedCount: 1,
          pendingCount: 1,
          excludedCount: 0,
        },
        items,
        nextCursor: null,
      });
    });

    it("내 확인 응답의 확인 집계를 ConfirmationSummaryDto로 감싸고 나머지 필드는 그대로 옮긴다", () => {
      const raw = {
        documentId: 101,
        confirmedAt: "2026-09-24T09:40:00.000Z",
        documentStatus: "DRAFT" as const,
        archivedAt: null,
        confirmationSummary: {
          confirmedCount: 3,
          pendingCount: 1,
          excludedCount: 0,
        },
      };

      const dto = new PutDocumentConfirmationResponseDto(raw);

      expect(dto.confirmationSummary).toBeInstanceOf(ConfirmationSummaryDto);
      expect(dto).toEqual(raw);
    });

    it("원문 응답의 구간을 DocumentTranscriptSegmentDto로 감싸고, 전체 텍스트와 isPartial은 옮기지 않는다", () => {
      const segments = [
        {
          startMillis: 1200,
          endMillis: 4600,
          speakerNumber: 1,
          text: "문서 보관 기준부터 정리하겠습니다.",
        },
        {
          startMillis: 4800,
          endMillis: null,
          speakerNumber: null,
          text: "좋습니다.",
        },
      ];

      const dto = new GetDocumentTranscriptResponseDto({
        transcriptId: 81,
        isPartial: false,
        recordingDurationSeconds: 1850,
        transcriptText: "문서 보관 기준부터 정리하겠습니다. 좋습니다.",
        segments,
      });

      expect(dto.segments[0]).toBeInstanceOf(DocumentTranscriptSegmentDto);
      expect(dto).toEqual({
        transcriptId: 81,
        recordingDurationSeconds: 1850,
        segments: segments.map((segment) => ({
          ...segment,
          speakerDisplayName: null,
        })),
      });
    });
  });
});
