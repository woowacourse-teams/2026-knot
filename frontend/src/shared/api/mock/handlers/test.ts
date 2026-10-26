import { GetCsrfTokenResponseDto, GetMeResponseDto } from "@api/dto/auth";
import { GetChatMessagesResponseDto } from "@api/dto/chatMessage";
import { PostDocumentGenerationJobRetryResponseDto } from "@api/dto/documentGenerationJob";
import {
  GetDocumentConfirmationsResponseDto,
  GetDocumentRecordingsResponseDto,
  GetDocumentResponseDto,
  GetDocumentsResponseDto,
  PutDocumentConfirmationResponseDto,
} from "@api/dto/document";
import {
  GetCurrentRecordingResponseDto,
  GetRecordingAudioSaveResponseDto,
  GetRecordingResponseDto,
  PostRecordingAudioChunkCompleteResponseDto,
  PostRecordingAudioChunkResponseDto,
  PostRecordingAudioSaveRetryResponseDto,
  PostRecordingAudioUploadCompleteResponseDto,
  PostRecordingAudioUploadUrlResponseDto,
  PostRecordingEndResponseDto,
  PostRecordingHeartbeatResponseDto,
  PostRecordingPauseResponseDto,
  PostRecordingResponseDto,
  PostRecordingResumeResponseDto,
  PostRecordingTranscriptionRetryResponseDto,
} from "@api/dto/recording";
import {
  GetSearchConversationsResponseDto,
  GetSearchMessagesResponseDto,
  SearchStreamAcceptedDto,
  SearchStreamCompletedDto,
  SearchStreamDeltaDto,
  SearchStreamEvidenceDto,
  SearchStreamProgressDto,
  type SearchEvidenceRaw,
  type SearchStreamStage,
} from "@api/dto/search";
import {
  GetOwnershipTransferCandidatesResponseDto,
  GetWorkspaceResponseDto,
  GetWorkspacesResponseDto,
  PostWorkspaceResponseDto,
} from "@api/dto/workspace";
import {
  GetInvitationPreviewResponseDto,
  GetWorkspaceInvitationResponseDto,
  PostInvitationAcceptResponseDto,
  PostWorkspaceInvitationReissueResponseDto,
  PostWorkspaceInvitationResponseDto,
} from "@api/dto/workspaceInvitation";
import { getCsrfTokenApi } from "@api/fetch/api/v1/auth/csrf";
import { getMeApi } from "@api/fetch/api/v1/auth/me";
import { completeNicknameApi } from "@api/fetch/api/v1/auth/nickname";
import { getChatMessagesApi } from "@api/fetch/api/v1/conversations/[sessionId]";
import { getInvitationPreviewApi } from "@api/fetch/api/v1/invitations/[tokenOrCode]";
import { acceptInvitationApi } from "@api/fetch/api/v1/invitations/accept";
import { updateLastViewedWorkspaceApi } from "@api/fetch/api/v1/members/me/lastViewedWorkspace";
import {
  createWorkspaceApi,
  getWorkspacesApi,
} from "@api/fetch/api/v1/workspaces";
import { getWorkspaceApi } from "@api/fetch/api/v1/workspaces/[workspaceId]";
import { getDocumentRecordingsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentRecordings";
import {
  getAllDocumentsApi,
  getDocumentsApi,
} from "@api/fetch/api/v1/workspaces/[workspaceId]/documents";
import { getDocumentApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { getDocumentConfirmationsApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations";
import { confirmDocumentApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations/me";
import { getWorkspaceInvitationApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/invitation";
import { leaveWorkspaceApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/members/me";
import { getOwnershipTransferCandidatesApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/ownershipTransferCandidates";
import { transferWorkspaceOwnershipApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/ownershipTransfers";
import {
  createSearchConversationApi,
  getSearchConversationsApi,
} from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations";
import { getSearchMessagesApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/messages";
import { createSearchQuestionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/questions";
import { retrySearchAnswerApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/questions/[questionId]/retry";
import { issueWorkspaceInvitationApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/invitations";
import { reissueWorkspaceInvitationApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/invitations/reissue";
import { retryDocumentGenerationJobApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/documentGenerationJobs/[jobId]/retry";
import { startRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings";
import { getRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { reserveRecordingAudioChunkApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks";
import { completeRecordingAudioChunkApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks/[sequence]/complete";
import { getRecordingAudioSaveApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioSave";
import { retryRecordingAudioSaveApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioSave/retry";
import { retryRecordingTranscriptionApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcription/retry";
import { completeRecordingAudioUploadApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioUploadComplete";
import {
  issueRecordingAudioUploadUrlApi,
  uploadRecordingAudioApi,
} from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioUploadUrl";
import { endRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/end";
import { sendRecordingHeartbeatApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/heartbeat";
import { pauseRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/pause";
import { resumeRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/resume";
import { getCurrentRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import { HTTP_ERROR_TYPE } from "@api/httpClient/error";
import { csrfTokenResponse, meResponse } from "@api/mock/responses/auth";
import { documentGenerationJobRetryResponse } from "@api/mock/responses/documentGenerationJob";
import { chatMessagesResponse } from "@api/mock/responses/chatMessage";
import {
  documentConfirmationsResponse,
  documentDetailsResponse,
  documentRecordingsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import {
  currentRecordingResponse,
  recordingAudioChunkCompleteResponse,
  recordingAudioChunkReserveResponse,
  recordingAudioSaveResponse,
  recordingAudioUploadCompleteResponse,
  recordingAudioUploadUrlResponse,
  recordingDetailsResponse,
  recordingEndResponse,
  recordingHeartbeatResponse,
  recordingPauseResponse,
  recordingResumeResponse,
  recordingStartResponse,
  recordingTranscriptionRetryResponse,
} from "@api/mock/responses/recording";
import type { RecordingDetailEntry } from "@api/mock/types/recording";
import {
  searchAnswerRetryStreamResponse,
  searchAnswerStreamResponse,
  searchConversationsResponse,
  searchMessagesResponse,
  searchQuestionAnswerStreamResponse,
} from "@api/mock/responses/search";
import {
  ownershipTransferCandidatesResponse,
  workspaceCreateResponse,
  workspaceDetailResponse,
  workspacesResponse,
} from "@api/mock/responses/workspace";
import {
  invitationAcceptanceResponse,
  invitationPreviewResponse,
  workspaceInvitationResponse,
} from "@api/mock/responses/workspaceInvitation";
import { describe, expect, it } from "vitest";

const WORKSPACE_ID = 1;
const SESSION_ID = 100;
const RECORDING_ID = 10;

// 첫 질문 mock의 대화 ID와 달라야 경로 값으로 답하는지 드러나요
const SEARCH_CONVERSATION_ID = 200;

const CONTROL_PROOF = {
  tabId: "6f1c2a4e-1b2d-4c3e-9f80-1a2b3c4d5e6f",
  controlToken: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
};

/** 스트림이 끝날 때까지 받아 이벤트를 순서대로 모아요 */
const collectEvents = async <T>(stream: AsyncGenerator<T>) => {
  const events: T[] = [];

  for await (const event of stream) events.push(event);

  return events;
};

interface ToAnswerEventsParams {
  answerMessageId: number;
  stages: SearchStreamStage[];
  deltas: string[];
  evidences: SearchEvidenceRaw[];
}

/** 첫 질문·후속 질문·재시도가 accepted 뒤로 같은 순서로 내는 이벤트예요 */
const toAnswerEvents = ({
  answerMessageId,
  stages,
  deltas,
  evidences,
}: ToAnswerEventsParams) => [
  ...stages.map((stage) => ({
    event: "progress",
    data: new SearchStreamProgressDto({ stage }),
  })),
  ...deltas.map((text) => ({
    event: "delta",
    data: new SearchStreamDeltaDto({ answerMessageId, text }),
  })),
  {
    event: "evidence",
    data: new SearchStreamEvidenceDto({ answerMessageId, items: evidences }),
  },
  {
    event: "completed",
    data: new SearchStreamCompletedDto({ answerMessageId, status: "COMPLETED" }),
  },
];

// 기본 핸들러가 fetch 요청 함수와 같은 경로·메서드에 응답하는지 확인해요
// 기대값은 mock 응답을 응답 DTO로 변환한 값이에요 (test-strategy.md 「기대값」)
describe("mock 기본 핸들러와 fetch 요청 함수의 대응", () => {
  describe("인증", () => {
    it("GET /api/v1/auth/me는 meResponse를 돌려준다", async () => {
      await expect(getMeApi()).resolves.toEqual(
        new GetMeResponseDto(meResponse),
      );
    });

    it("GET /api/v1/auth/csrf는 csrfTokenResponse를 돌려준다", async () => {
      await expect(getCsrfTokenApi()).resolves.toEqual(
        new GetCsrfTokenResponseDto(csrfTokenResponse),
      );
    });

    it("POST /api/v1/auth/nickname은 본문 없이 성공한다", async () => {
      await expect(
        completeNicknameApi({ nickname: "노티드" }),
      ).resolves.toBeUndefined();
    });
  });

  describe("워크스페이스", () => {
    it("GET /api/v1/workspaces는 workspacesResponse를 돌려준다", async () => {
      await expect(getWorkspacesApi()).resolves.toEqual(
        new GetWorkspacesResponseDto(workspacesResponse),
      );
    });

    it("POST /api/v1/workspaces는 workspaceCreateResponse를 돌려준다", async () => {
      await expect(createWorkspaceApi({ name: "Knot 팀" })).resolves.toEqual(
        new PostWorkspaceResponseDto(workspaceCreateResponse),
      );
    });

    it("GET /api/v1/workspaces/:workspaceId는 workspaceDetailResponse를 돌려준다", async () => {
      await expect(getWorkspaceApi(WORKSPACE_ID)).resolves.toEqual(
        new GetWorkspaceResponseDto(workspaceDetailResponse),
      );
    });

    it("PUT /api/v1/members/me/last-viewed-workspace는 본문 없이 성공한다", async () => {
      await expect(
        updateLastViewedWorkspaceApi({ workspaceId: WORKSPACE_ID }),
      ).resolves.toBeUndefined();
    });

    it("DELETE /api/v1/workspaces/:workspaceId/members/me는 본문 없이 성공한다", async () => {
      await expect(leaveWorkspaceApi(WORKSPACE_ID)).resolves.toBeUndefined();
    });

    it("GET /api/v1/workspaces/:workspaceId/ownership-transfer-candidates는 ownershipTransferCandidatesResponse를 돌려준다", async () => {
      await expect(
        getOwnershipTransferCandidatesApi(WORKSPACE_ID),
      ).resolves.toEqual(
        new GetOwnershipTransferCandidatesResponseDto(
          ownershipTransferCandidatesResponse,
        ),
      );
    });

    it("POST /api/v1/workspaces/:workspaceId/ownership-transfers는 본문 없이 성공한다", async () => {
      await expect(
        transferWorkspaceOwnershipApi({
          workspaceId: WORKSPACE_ID,
          body: { successorMemberId: 2 },
        }),
      ).resolves.toBeUndefined();
    });
  });

  describe("워크스페이스 초대", () => {
    it("GET /api/v1/workspaces/:workspaceId/invitation은 workspaceInvitationResponse를 돌려준다", async () => {
      await expect(getWorkspaceInvitationApi(WORKSPACE_ID)).resolves.toEqual(
        new GetWorkspaceInvitationResponseDto(workspaceInvitationResponse),
      );
    });

    it("POST /api/v1/workspaces/:workspaceId/invitations는 workspaceInvitationResponse를 돌려준다", async () => {
      await expect(issueWorkspaceInvitationApi(WORKSPACE_ID)).resolves.toEqual(
        new PostWorkspaceInvitationResponseDto(workspaceInvitationResponse),
      );
    });

    it("POST /api/v1/workspaces/:workspaceId/invitations/reissue는 workspaceInvitationResponse를 돌려준다", async () => {
      await expect(
        reissueWorkspaceInvitationApi(WORKSPACE_ID),
      ).resolves.toEqual(
        new PostWorkspaceInvitationReissueResponseDto(
          workspaceInvitationResponse,
        ),
      );
    });

    it("GET /api/v1/invitations/:tokenOrCode는 invitationPreviewResponse를 돌려준다", async () => {
      await expect(
        getInvitationPreviewApi(workspaceInvitationResponse.code),
      ).resolves.toEqual(
        new GetInvitationPreviewResponseDto(invitationPreviewResponse),
      );
    });

    it("POST /api/v1/invitations/accept는 invitationAcceptanceResponse를 돌려준다", async () => {
      await expect(
        acceptInvitationApi({ credential: workspaceInvitationResponse.code }),
      ).resolves.toEqual(
        new PostInvitationAcceptResponseDto(invitationAcceptanceResponse),
      );
    });
  });

  describe("문서", () => {
    describe("GET /api/v1/workspaces/:workspaceId/document-recordings", () => {
      const expected = new GetDocumentRecordingsResponseDto(
        documentRecordingsResponse,
      );

      it("documentRecordingsResponse를 돌려준다. 녹음 하나의 문서는 모두 한 묶음에 담겨 있다", async () => {
        const page = await getDocumentRecordingsApi({
          workspaceId: WORKSPACE_ID,
        });

        expect(page).toEqual(expected);
        page.items.forEach(({ documents, documentCount }) => {
          expect(documents).toHaveLength(documentCount);
          expect(documentCount).toBeGreaterThanOrEqual(1);
        });
      });

      it("녹음별 묶음의 문서를 모두 모으면 문서 목록 조회의 문서 집합과 같다", async () => {
        const documentIds = expected.items
          .flatMap(({ documents }) => documents)
          .map(({ id }) => id)
          .sort((a, b) => a - b);
        const listDocumentIds = new GetDocumentsResponseDto(documentsResponse)
          .items.map(({ id }) => id)
          .sort((a, b) => a - b);

        expect(documentIds).toEqual(listDocumentIds);
      });

      it("없는 cursor면 400 INVALID_PARAMETER로 답한다", async () => {
        await expect(
          getDocumentRecordingsApi({
            workspaceId: WORKSPACE_ID,
            cursor: "없는 커서",
          }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_PARAMETER",
        });
      });
    });

    describe("GET /api/v1/workspaces/:workspaceId/documents", () => {
      const expected = new GetDocumentsResponseDto(documentsResponse);

      it("documentsResponse를 돌려준다", async () => {
        await expect(
          getDocumentsApi({ workspaceId: WORKSPACE_ID }),
        ).resolves.toEqual(expected);
      });

      it("문서가 size보다 많으면 앞에서 size개만 주고 nextCursor를 준다. 주제 폴더는 전체를 준다", async () => {
        const page = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          size: 3,
        });

        expect(page.items).toEqual(expected.items.slice(0, 3));
        expect(page.topics).toEqual(expected.topics);
        expect(page.nextCursor).not.toBeNull();
      });

      it("nextCursor로 요청하면 그다음 문서부터 주고, 마지막 페이지의 nextCursor는 null이다", async () => {
        const { nextCursor } = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          size: 4,
        });

        const lastPage = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          size: 4,
          cursor: nextCursor ?? undefined,
        });

        expect(lastPage.items).toEqual(expected.items.slice(4));
        expect(lastPage.nextCursor).toBeNull();
      });

      it("없는 cursor면 400 INVALID_PARAMETER로 답한다", async () => {
        await expect(
          getDocumentsApi({ workspaceId: WORKSPACE_ID, cursor: "없는 커서" }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_PARAMETER",
        });
      });

      it("recordingSessionId를 주면 그 녹음에서 나온 문서만 주고, 주제 폴더도 그 문서들만 센다", async () => {
        // 기본 응답에서 같은 녹음에서 나온 문서는 상세 mock이 있는 두 문서예요
        const [{ recordingSessionId }] = documentDetailsResponse;
        const recordingItems = expected.items.filter(
          (item) => item.recordingSessionId === recordingSessionId,
        );

        const page = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          recordingSessionId,
        });

        expect(recordingItems).toHaveLength(documentDetailsResponse.length);
        expect(page.items).toEqual(recordingItems);
        const recordingTopics = recordingItems.map(({ topic }) => topic);

        expect(page.topics).toEqual(
          // 순서는 전체 응답의 주제 순서를 따르고, 두 문서의 주제가 서로 달라 주제마다 문서가 하나예요
          expected.topics
            .filter(({ topic }) => recordingTopics.includes(topic))
            .map((topic) => ({ ...topic, documentCount: 1 })),
        );
        expect(page.nextCursor).toBeNull();
      });

      it("끝까지 받는 getAllDocumentsApi도 recordingSessionId를 주면 그 녹음에서 나온 문서만 받는다", async () => {
        const [{ recordingSessionId }] = documentDetailsResponse;

        const { items, nextCursor } = await getAllDocumentsApi({
          workspaceId: WORKSPACE_ID,
          recordingSessionId,
        });

        expect(items).toEqual(
          expected.items.filter(
            (item) => item.recordingSessionId === recordingSessionId,
          ),
        );
        expect(nextCursor).toBeNull();
      });

      it("recordingSessionId가 1 이상의 정수가 아니면 400 INVALID_PARAMETER로 답한다", async () => {
        await expect(
          getDocumentsApi({ workspaceId: WORKSPACE_ID, recordingSessionId: 0 }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_PARAMETER",
        });
      });

      it("myConfirmation을 주면 내 확인 상태가 그 값인 문서만 주고, 주제 폴더도 그 문서들만 센다", async () => {
        const pendingItems = expected.items.filter(
          ({ myConfirmationState }) => myConfirmationState === "PENDING",
        );
        const pendingTopics = pendingItems.map(({ topic }) => topic);

        const page = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          myConfirmation: "PENDING",
        });

        // 기본 응답에서 내가 아직 확인하지 않은 문서는 주제가 서로 다른 두 개예요
        expect(pendingItems).toHaveLength(2);
        expect(page.items).toEqual(pendingItems);
        expect(page.topics).toEqual(
          expected.topics
            .filter(({ topic }) => pendingTopics.includes(topic))
            .map((topic) => ({ ...topic, documentCount: 1 })),
        );
        expect(page.nextCursor).toBeNull();
      });

      it("myConfirmation과 recordingSessionId를 함께 주면 두 조건을 모두 만족하는 문서만 준다", async () => {
        // 같은 녹음에서 나온 두 문서 가운데 내가 아직 확인하지 않은 문서는 하나예요
        const [{ recordingSessionId }] = documentDetailsResponse;
        const pendingRecordingItems = expected.items.filter(
          (item) =>
            item.recordingSessionId === recordingSessionId &&
            item.myConfirmationState === "PENDING",
        );

        const page = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          myConfirmation: "PENDING",
          recordingSessionId,
        });

        expect(pendingRecordingItems).toHaveLength(1);
        expect(page.items).toEqual(pendingRecordingItems);
      });

      it("끝까지 받는 getAllDocumentsApi도 myConfirmation을 주면 그 상태의 문서만 받는다", async () => {
        const { items, nextCursor } = await getAllDocumentsApi({
          workspaceId: WORKSPACE_ID,
          myConfirmation: "PENDING",
        });

        expect(items).toEqual(
          expected.items.filter(
            ({ myConfirmationState }) => myConfirmationState === "PENDING",
          ),
        );
        expect(nextCursor).toBeNull();
      });

      it("myConfirmation이 PENDING · CONFIRMED · NOT_REQUIRED가 아니면 400 INVALID_PARAMETER로 답한다", async () => {
        await expect(
          getDocumentsApi({
            workspaceId: WORKSPACE_ID,
            // @ts-expect-error 타입이 막는 값을 서버가 어떻게 답하는지 보려고 일부러 보내요
            myConfirmation: "DONE",
          }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_PARAMETER",
        });
      });

      it("size가 1~100을 벗어나면 400 INVALID_PARAMETER로 답한다", async () => {
        await expect(
          getDocumentsApi({ workspaceId: WORKSPACE_ID, size: 101 }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.badRequest,
          code: "INVALID_PARAMETER",
        });
      });
    });

    it("GET /api/v1/workspaces/:workspaceId/documents/:documentId는 documentDetailsResponse에서 그 id의 문서를 돌려준다", async () => {
      const [document] = documentDetailsResponse;

      await expect(
        getDocumentApi({ workspaceId: WORKSPACE_ID, documentId: document.id }),
      ).resolves.toEqual(new GetDocumentResponseDto(document));
    });

    it("documentDetailsResponse에 없는 id면 404 DOCUMENT_NOT_FOUND로 답한다", async () => {
      await expect(
        getDocumentApi({ workspaceId: WORKSPACE_ID, documentId: 999 }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.notFound,
        code: "DOCUMENT_NOT_FOUND",
      });
    });

    it("GET /api/v1/workspaces/:workspaceId/documents/:documentId/confirmations는 documentConfirmationsResponse에서 그 문서의 확인 대상을 돌려준다", async () => {
      const [confirmations] = documentConfirmationsResponse;

      await expect(
        getDocumentConfirmationsApi({
          workspaceId: WORKSPACE_ID,
          documentId: confirmations.documentId,
        }),
      ).resolves.toEqual(
        new GetDocumentConfirmationsResponseDto(confirmations),
      );
    });

    it("documentConfirmationsResponse에 없는 문서의 확인 대상을 물으면 404 DOCUMENT_NOT_FOUND로 답한다", async () => {
      await expect(
        getDocumentConfirmationsApi({
          workspaceId: WORKSPACE_ID,
          documentId: 999,
        }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.notFound,
        code: "DOCUMENT_NOT_FOUND",
      });
    });

    describe("PUT /api/v1/workspaces/:workspaceId/documents/:documentId/confirmations/me", () => {
      // 내가 아직 확인하지 않은 문서(101)와 이미 확인한 문서(102)
      const [pendingDocument, confirmedDocument] = documentDetailsResponse;
      const pendingParams = {
        workspaceId: WORKSPACE_ID,
        documentId: pendingDocument.id,
      };

      it("확인하지 않은 문서를 확인하면 확인 수가 1 늘고 미확인 수가 1 준 집계를 돌려준다", async () => {
        const { id, confirmationSummary } = new GetDocumentResponseDto(
          pendingDocument,
        );

        const result = await confirmDocumentApi(pendingParams);

        expect(result).toBeInstanceOf(PutDocumentConfirmationResponseDto);
        expect(result.documentId).toBe(id);
        expect(result.confirmationSummary).toEqual({
          ...confirmationSummary,
          confirmedCount: confirmationSummary.confirmedCount + 1,
          pendingCount: confirmationSummary.pendingCount - 1,
        });
      });

      it("확인한 뒤에는 문서 상세의 내 상태와 집계, 확인 대상의 내 상태가 함께 바뀐다", async () => {
        const { confirmedAt, confirmationSummary } =
          await confirmDocumentApi(pendingParams);

        const document = await getDocumentApi(pendingParams);
        const { items } = await getDocumentConfirmationsApi(pendingParams);
        const myItem = items.find(
          ({ memberId }) => memberId === meResponse.memberId,
        );

        expect(document.myConfirmationState).toBe("CONFIRMED");
        expect(document.confirmationSummary).toEqual(confirmationSummary);
        expect(myItem).toMatchObject({ state: "CONFIRMED", confirmedAt });
        // 서버 정렬처럼 확인한 사람이 미확인인 사람보다 앞에 와요
        expect(items.map(({ state }) => state)).toEqual([
          "CONFIRMED",
          "CONFIRMED",
          "CONFIRMED",
          "PENDING",
        ]);
      });

      it("확인한 뒤에는 문서 목록의 그 문서도 내 상태와 집계가 함께 바뀐다", async () => {
        const { confirmationSummary } = await confirmDocumentApi(pendingParams);

        const { items } = await getDocumentsApi({ workspaceId: WORKSPACE_ID });
        const confirmedItem = items.find(({ id }) => id === pendingDocument.id);

        expect(confirmedItem).toMatchObject({
          myConfirmationState: "CONFIRMED",
          confirmationSummary,
        });
      });

      it("확인한 뒤에는 확인 대기 문서 목록에서 그 문서가 빠지고, 주제 폴더도 남은 문서만 센다", async () => {
        const pendingBefore = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          myConfirmation: "PENDING",
        });

        await confirmDocumentApi(pendingParams);

        const pendingAfter = await getDocumentsApi({
          workspaceId: WORKSPACE_ID,
          myConfirmation: "PENDING",
        });

        expect(pendingBefore.items.map(({ id }) => id)).toContain(
          pendingDocument.id,
        );
        expect(pendingAfter.items).toEqual(
          pendingBefore.items.filter(({ id }) => id !== pendingDocument.id),
        );
        // 그 주제에서 확인을 기다리던 문서가 이것 하나라, 주제 폴더에서도 빠져요
        expect(pendingAfter.topics).toEqual(
          pendingBefore.topics.filter(
            ({ topic }) => topic !== pendingDocument.topic,
          ),
        );
      });

      it("다시 확인해도 처음 확인한 시각과 같은 집계를 돌려준다", async () => {
        const first = await confirmDocumentApi(pendingParams);
        const second = await confirmDocumentApi(pendingParams);

        expect(second).toEqual(first);
      });

      it("이미 확인한 문서를 확인하면 집계를 바꾸지 않고 처음 확인한 시각을 돌려준다", async () => {
        const [, confirmations] = documentConfirmationsResponse;
        const { items } = new GetDocumentConfirmationsResponseDto(
          confirmations,
        );
        const myItem = items.find(
          ({ memberId }) => memberId === meResponse.memberId,
        );
        const expected = new GetDocumentResponseDto(confirmedDocument);

        const result = await confirmDocumentApi({
          workspaceId: WORKSPACE_ID,
          documentId: expected.id,
        });

        expect(result.confirmedAt).toBe(myItem?.confirmedAt);
        expect(result.confirmationSummary).toEqual(
          expected.confirmationSummary,
        );
      });

      it("확인한 기록은 테스트가 끝나면 지워져, 다음 테스트는 기본 응답을 받는다", async () => {
        // 위 테스트들이 101을 확인했지만 vitest.setup의 afterEach가 기록을 지웠어요
        await expect(getDocumentApi(pendingParams)).resolves.toEqual(
          new GetDocumentResponseDto(pendingDocument),
        );
      });

      it("없는 문서를 확인하면 404 DOCUMENT_NOT_FOUND로 답한다", async () => {
        await expect(
          confirmDocumentApi({ workspaceId: WORKSPACE_ID, documentId: 999 }),
        ).rejects.toMatchObject({
          type: HTTP_ERROR_TYPE.notFound,
          code: "DOCUMENT_NOT_FOUND",
        });
      });
    });
  });

  describe("대화", () => {
    it("GET /api/v1/conversations/:sessionId는 chatMessagesResponse를 돌려준다", async () => {
      await expect(getChatMessagesApi(SESSION_ID)).resolves.toEqual(
        new GetChatMessagesResponseDto(chatMessagesResponse),
      );
    });
  });

  describe("탐색", () => {
    it("GET /api/v1/workspaces/:workspaceId/search/conversations는 searchConversationsResponse를 돌려준다", async () => {
      await expect(
        getSearchConversationsApi({ workspaceId: WORKSPACE_ID }),
      ).resolves.toEqual(
        new GetSearchConversationsResponseDto(searchConversationsResponse),
      );
    });

    it("POST /api/v1/workspaces/:workspaceId/search/conversations는 searchAnswerStreamResponse를 순서대로 흘려보낸다", async () => {
      const { answerMessageId, stages, deltas, evidences, ...accepted } =
        searchAnswerStreamResponse;

      await expect(
        collectEvents(
          createSearchConversationApi({
            workspaceId: WORKSPACE_ID,
            body: { content: "DB 기술 선정 관련 문서 있어?", requestId: "req-1" },
          }),
        ),
      ).resolves.toEqual([
        {
          event: "accepted",
          data: new SearchStreamAcceptedDto({ ...accepted, answerMessageId }),
        },
        ...toAnswerEvents({ answerMessageId, stages, deltas, evidences }),
      ]);
    });

    it("POST .../search/conversations/:conversationId/questions는 searchQuestionAnswerStreamResponse를 경로의 대화 ID로 순서대로 흘려보낸다", async () => {
      const { questionMessageId, answerMessageId, stages, deltas, evidences } =
        searchQuestionAnswerStreamResponse;

      await expect(
        collectEvents(
          createSearchQuestionApi({
            workspaceId: WORKSPACE_ID,
            conversationId: SEARCH_CONVERSATION_ID,
            body: { content: "그럼 초기 스키마는 누가 정리했어?", requestId: "req-2" },
          }),
        ),
      ).resolves.toEqual([
        {
          event: "accepted",
          data: new SearchStreamAcceptedDto({
            conversationId: SEARCH_CONVERSATION_ID,
            questionMessageId,
            answerMessageId,
          }),
        },
        ...toAnswerEvents({ answerMessageId, stages, deltas, evidences }),
      ]);
    });

    it("POST .../questions/:questionId/retry는 searchAnswerRetryStreamResponse를 경로의 질문과 그다음 답변 메시지 ID로 순서대로 흘려보낸다", async () => {
      const { stages, deltas, evidences } = searchAnswerRetryStreamResponse;
      const questionMessageId = 3;
      const answerMessageId = questionMessageId + 1;

      await expect(
        collectEvents(
          retrySearchAnswerApi({
            workspaceId: WORKSPACE_ID,
            conversationId: SEARCH_CONVERSATION_ID,
            questionId: questionMessageId,
            body: { requestId: "req-3" },
          }),
        ),
      ).resolves.toEqual([
        {
          event: "accepted",
          data: new SearchStreamAcceptedDto({
            conversationId: SEARCH_CONVERSATION_ID,
            questionMessageId,
            answerMessageId,
          }),
        },
        ...toAnswerEvents({ answerMessageId, stages, deltas, evidences }),
      ]);
    });

    it("GET .../search/conversations/:conversationId/messages는 searchMessagesResponse를 경로의 대화 ID로 돌려준다 (근거 없음)", async () => {
      await expect(
        getSearchMessagesApi({
          workspaceId: WORKSPACE_ID,
          conversationId: SEARCH_CONVERSATION_ID,
        }),
      ).resolves.toEqual(
        new GetSearchMessagesResponseDto({
          ...searchMessagesResponse,
          conversationId: SEARCH_CONVERSATION_ID,
        }),
      );
    });
  });

  describe("녹음", () => {
    it("POST /api/v1/workspaces/:workspaceId/recordings는 recordingStartResponse를 돌려준다", async () => {
      await expect(
        startRecordingApi({
          workspaceId: WORKSPACE_ID,
          body: {
            requestId: "0b8e0c55-6f0a-4d0b-8f1e-2f6a8f3c9d10",
            ...CONTROL_PROOF,
          },
        }),
      ).resolves.toEqual(
        new PostRecordingResponseDto(recordingStartResponse, 201),
      );
    });

    it("GET /api/v1/workspaces/:workspaceId/recordings/current는 currentRecordingResponse를 돌려준다", async () => {
      await expect(getCurrentRecordingApi(WORKSPACE_ID)).resolves.toEqual(
        new GetCurrentRecordingResponseDto(currentRecordingResponse),
      );
    });

    it("POST .../recordings/:recordingId/pause는 recordingPauseResponse를 돌려준다", async () => {
      await expect(
        pauseRecordingApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: CONTROL_PROOF,
        }),
      ).resolves.toEqual(
        new PostRecordingPauseResponseDto(recordingPauseResponse),
      );
    });

    it("POST .../recordings/:recordingId/resume은 recordingResumeResponse를 돌려준다", async () => {
      await expect(
        resumeRecordingApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: CONTROL_PROOF,
        }),
      ).resolves.toEqual(
        new PostRecordingResumeResponseDto(recordingResumeResponse),
      );
    });

    it("POST .../recordings/:recordingId/heartbeat는 recordingHeartbeatResponse를 돌려준다", async () => {
      await expect(
        sendRecordingHeartbeatApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: CONTROL_PROOF,
        }),
      ).resolves.toEqual(
        new PostRecordingHeartbeatResponseDto(recordingHeartbeatResponse),
      );
    });

    it("POST .../recordings/:recordingId/end는 recordingEndResponse를 돌려준다", async () => {
      await expect(
        endRecordingApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: CONTROL_PROOF,
        }),
      ).resolves.toEqual(new PostRecordingEndResponseDto(recordingEndResponse));
    });

    it("POST .../recordings/:recordingId/audio-upload-url은 recordingAudioUploadUrlResponse를 돌려준다", async () => {
      await expect(
        issueRecordingAudioUploadUrlApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: { contentType: "audio/webm", contentLength: 4 },
        }),
      ).resolves.toEqual(
        new PostRecordingAudioUploadUrlResponseDto(
          recordingAudioUploadUrlResponse,
        ),
      );
    });

    it("발급한 업로드 URL로 오디오를 PUT하면 본문 없이 성공한다", async () => {
      await expect(
        uploadRecordingAudioApi({
          uploadUrl: recordingAudioUploadUrlResponse.uploadUrl,
          audio: new Blob(["test"], { type: "audio/webm" }),
        }),
      ).resolves.toBeUndefined();
    });

    it("POST .../recordings/:recordingId/audio-upload-complete는 recordingAudioUploadCompleteResponse를 돌려준다", async () => {
      await expect(
        completeRecordingAudioUploadApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: { uploadId: recordingAudioUploadUrlResponse.uploadId },
        }),
      ).resolves.toEqual(
        new PostRecordingAudioUploadCompleteResponseDto(
          recordingAudioUploadCompleteResponse,
        ),
      );
    });

    it("POST .../recordings/:recordingId/audio-chunks는 요청한 조각 번호로 recordingAudioChunkReserveResponse를 돌려준다", async () => {
      // 기본 응답(1번)과 다른 번호여야 요청 값을 되돌려 주는지 드러나요
      const sequence = 2;

      await expect(
        reserveRecordingAudioChunkApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          body: {
            ...CONTROL_PROOF,
            sequence,
            sampleOffset: 80000,
            frameCount: 80000,
            byteLength: 160000,
            checksumSha256:
              recordingAudioChunkReserveResponse.requiredHeaders[
                "x-amz-checksum-sha256"
              ],
          },
        }),
      ).resolves.toEqual(
        new PostRecordingAudioChunkResponseDto({
          ...recordingAudioChunkReserveResponse,
          sequence,
        }),
      );
    });

    it("POST .../audio-chunks/:sequence/complete는 경로의 조각 번호로 recordingAudioChunkCompleteResponse를 돌려준다", async () => {
      const sequence = 2;

      await expect(
        completeRecordingAudioChunkApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
          sequence,
          body: CONTROL_PROOF,
        }),
      ).resolves.toEqual(
        new PostRecordingAudioChunkCompleteResponseDto({
          ...recordingAudioChunkCompleteResponse,
          sequence,
        }),
      );
    });

    it("GET .../recordings/:recordingId/audio-save는 recordingAudioSaveResponse를 돌려준다", async () => {
      await expect(
        getRecordingAudioSaveApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
        }),
      ).resolves.toEqual(
        new GetRecordingAudioSaveResponseDto(recordingAudioSaveResponse),
      );
    });

    it("POST .../recordings/:recordingId/audio-save/retry는 recordingAudioSaveResponse를 돌려준다", async () => {
      await expect(
        retryRecordingAudioSaveApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
        }),
      ).resolves.toEqual(
        new PostRecordingAudioSaveRetryResponseDto(recordingAudioSaveResponse),
      );
    });

    // 전사에 실패한 mock 녹음이에요
    const transcriptionFailedRecordingParams = {
      workspaceId: WORKSPACE_ID,
      recordingId: recordingTranscriptionRetryResponse.recordingId,
    };

    it("POST .../recordings/:recordingId/transcription/retry는 전사에 실패한 녹음의 전사를 접수하고 recordingTranscriptionRetryResponse를 돌려준다", async () => {
      await expect(
        retryRecordingTranscriptionApi(transcriptionFailedRecordingParams),
      ).resolves.toEqual(
        new PostRecordingTranscriptionRetryResponseDto(
          recordingTranscriptionRetryResponse,
        ),
      );
    });

    it("전사를 다시 접수한 뒤에는 그 녹음이 새 시도 ID로 전사 중이 되고 실패 코드가 지워진다", async () => {
      await retryRecordingTranscriptionApi(transcriptionFailedRecordingParams);

      await expect(
        getRecordingApi(transcriptionFailedRecordingParams),
      ).resolves.toMatchObject({
        transcriptionAttemptId: recordingTranscriptionRetryResponse.attemptId,
        transcriptionStatus: "PROCESSING",
        transcriptionFailureCode: null,
      });
    });

    it("전사가 실패 상태가 아닌 녹음의 전사를 다시 시도하면 409 TRANSCRIPTION_RETRY_NOT_ALLOWED로 답한다", async () => {
      await expect(
        retryRecordingTranscriptionApi({
          workspaceId: WORKSPACE_ID,
          recordingId: RECORDING_ID,
        }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.conflict,
        code: "TRANSCRIPTION_RETRY_NOT_ALLOWED",
      });
    });

    it("GET .../recordings/:recordingId는 recordingDetailsResponse에서 그 녹음을 찾아 돌려준다", async () => {
      // 상태가 서로 다른 녹음을 모두 물어, 핸들러가 id로 골라 주는지 확인해요
      for (const { recordingId, detail } of recordingDetailsResponse) {
        await expect(
          getRecordingApi({ workspaceId: WORKSPACE_ID, recordingId }),
        ).resolves.toEqual(new GetRecordingResponseDto(detail));
      }
    });

    it("recordingDetailsResponse에 없는 녹음을 물으면 404 RECORDING_NOT_FOUND로 답한다", async () => {
      await expect(
        getRecordingApi({ workspaceId: WORKSPACE_ID, recordingId: 999 }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.notFound,
        code: "RECORDING_NOT_FOUND",
      });
    });
  });

  describe("문서 생성 작업", () => {
    // 문서 만들기에 실패한 녹음(실패한 작업이 다시 시도 mock 응답의 작업) · 실패한 작업이 둘인 녹음 · 실패한 작업이 다시 시도 횟수를 다 쓴 녹음
    const [
      ,
      ,
      failedRecording,
      ,
      ,
      twoJobsFailedRecording,
      retryExhaustedRecording,
    ] = recordingDetailsResponse;
    const failedJobParams = {
      workspaceId: WORKSPACE_ID,
      jobId: documentGenerationJobRetryResponse.jobId,
    };
    // mock 녹음은 상태가 서로 달라 문서 생성 결과가 없는 녹음도 있어요. 그래서 없으면 빈 배열로 읽어요
    const getFailedJobIds = ({ detail }: RecordingDetailEntry) =>
      detail.documentGeneration?.failedJobIds ?? [];
    const failedRecordingParams = {
      workspaceId: WORKSPACE_ID,
      recordingId: failedRecording.recordingId,
    };

    it("POST .../document-generation-jobs/:jobId/retry는 실패한 작업을 접수하고 documentGenerationJobRetryResponse를 돌려준다", async () => {
      await expect(
        retryDocumentGenerationJobApi(failedJobParams),
      ).resolves.toEqual(
        new PostDocumentGenerationJobRetryResponseDto(
          documentGenerationJobRetryResponse,
        ),
      );
    });

    it("다시 시도한 뒤에는 그 녹음의 문서 생성 결과가 대기로 바뀌고 그 작업이 실패 작업에서 빠진다", async () => {
      await retryDocumentGenerationJobApi(failedJobParams);

      await expect(
        getRecordingApi(failedRecordingParams),
      ).resolves.toMatchObject({
        documentGeneration: { status: "QUEUED", failedJobIds: [] },
      });
    });

    it("이미 다시 시도해 실패 상태가 아닌 작업을 또 시도하면 409 RETRY_NOT_ALLOWED로 답한다", async () => {
      await retryDocumentGenerationJobApi(failedJobParams);

      await expect(
        retryDocumentGenerationJobApi(failedJobParams),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.conflict,
        code: "RETRY_NOT_ALLOWED",
      });
    });

    it("실패한 작업이 여러 개인 녹음에서 한 작업만 다시 시도하면 문서 생성 결과가 대기로 바뀌고 그 작업만 실패 작업에서 빠진다", async () => {
      const [retriedJobId, remainingJobId] = getFailedJobIds(
        twoJobsFailedRecording,
      );

      await retryDocumentGenerationJobApi({
        workspaceId: WORKSPACE_ID,
        jobId: retriedJobId,
      });

      await expect(
        getRecordingApi({
          workspaceId: WORKSPACE_ID,
          recordingId: twoJobsFailedRecording.recordingId,
        }),
      ).resolves.toMatchObject({
        documentGeneration: {
          status: "QUEUED",
          failedJobIds: [remainingJobId],
        },
      });
    });

    it("다시 시도할 수 있는 횟수를 다 쓴 작업을 다시 시도하면 409 RETRY_NOT_ALLOWED로 답하고, 그 작업은 실패 작업에 남는다", async () => {
      const exhaustedRecordingParams = {
        workspaceId: WORKSPACE_ID,
        recordingId: retryExhaustedRecording.recordingId,
      };
      const [exhaustedJobId] = getFailedJobIds(retryExhaustedRecording);

      await expect(
        retryDocumentGenerationJobApi({
          workspaceId: WORKSPACE_ID,
          jobId: exhaustedJobId,
        }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.conflict,
        code: "RETRY_NOT_ALLOWED",
      });
      await expect(getRecordingApi(exhaustedRecordingParams)).resolves.toEqual(
        new GetRecordingResponseDto(retryExhaustedRecording.detail),
      );
    });

    it("없는 작업을 다시 시도하면 404 DOCUMENT_GENERATION_JOB_NOT_FOUND로 답한다", async () => {
      await expect(
        retryDocumentGenerationJobApi({
          workspaceId: WORKSPACE_ID,
          jobId: 999,
        }),
      ).rejects.toMatchObject({
        type: HTTP_ERROR_TYPE.notFound,
        code: "DOCUMENT_GENERATION_JOB_NOT_FOUND",
      });
    });

    it("다시 시도한 기록은 테스트가 끝나면 지워져, 다음 테스트는 기본 응답을 받는다", async () => {
      // 위 테스트들이 작업을 다시 시도했지만 vitest.setup의 afterEach가 기록을 지웠어요
      await expect(getRecordingApi(failedRecordingParams)).resolves.toEqual(
        new GetRecordingResponseDto(failedRecording.detail),
      );
    });
  });
});
