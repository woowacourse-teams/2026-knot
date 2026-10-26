import { authCsrfHandlers } from "./api/v1/auth/csrf";
import { authMeHandlers } from "./api/v1/auth/me";
import { authNicknameHandlers } from "./api/v1/auth/nickname";
import { authRefreshHandlers } from "./api/v1/auth/refresh";
import { chatMessagesHandlers } from "./api/v1/conversations/[sessionId]";
import { invitationPreviewHandlers } from "./api/v1/invitations/[tokenOrCode]";
import { invitationAcceptHandlers } from "./api/v1/invitations/accept";
import { lastViewedWorkspaceHandlers } from "./api/v1/members/me/lastViewedWorkspace";
import { workspacesHandlers } from "./api/v1/workspaces";
import { workspaceHandlers } from "./api/v1/workspaces/[workspaceId]";
import { documentGenerationJobRetryHandlers } from "./api/v1/workspaces/[workspaceId]/documentGenerationJobs/[jobId]/retry";
import { documentRecordingsHandlers } from "./api/v1/workspaces/[workspaceId]/documentRecordings";
import { documentsHandlers } from "./api/v1/workspaces/[workspaceId]/documents";
import { documentHandlers } from "./api/v1/workspaces/[workspaceId]/documents/[documentId]";
import { documentConfirmationsHandlers } from "./api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations";
import { documentMyConfirmationHandlers } from "./api/v1/workspaces/[workspaceId]/documents/[documentId]/confirmations/me";
import { documentTranscriptHandlers } from "./api/v1/workspaces/[workspaceId]/documents/[documentId]/transcript";
import { workspaceInvitationHandlers } from "./api/v1/workspaces/[workspaceId]/invitation";
import { workspaceInvitationsHandlers } from "./api/v1/workspaces/[workspaceId]/invitations";
import { workspaceInvitationReissueHandlers } from "./api/v1/workspaces/[workspaceId]/invitations/reissue";
import { workspaceLeaveHandlers } from "./api/v1/workspaces/[workspaceId]/members/me";
import { ownershipTransferCandidatesHandlers } from "./api/v1/workspaces/[workspaceId]/ownershipTransferCandidates";
import { ownershipTransfersHandlers } from "./api/v1/workspaces/[workspaceId]/ownershipTransfers";
import { workspaceRecordingsHandlers } from "./api/v1/workspaces/[workspaceId]/recordings";
import { recordingHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { workspaceSearchConversationsHandlers } from "./api/v1/workspaces/[workspaceId]/search/conversations";
import { workspaceSearchAnswerEventsHandlers } from "./api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/answers/[answerId]/events";
import { workspaceSearchConversationMessagesHandlers } from "./api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/messages";
import { workspaceSearchConversationQuestionsHandlers } from "./api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/questions";
import { workspaceSearchAnswerRetryHandlers } from "./api/v1/workspaces/[workspaceId]/search/conversations/[conversationId]/questions/[questionId]/retry";
import { recordingAudioChunksHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks";
import { recordingAudioChunkCompleteHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioChunks/[sequence]/complete";
import { recordingAudioSaveHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioSave";
import { recordingAudioSaveRetryHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioSave/retry";
import { recordingAudioUploadCompleteHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioUploadComplete";
import { recordingAudioUploadUrlHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioUploadUrl";
import { recordingEndHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/end";
import { recordingHeartbeatHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/heartbeat";
import { recordingTranscriptionRetryHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/transcription/retry";
import { recordingPauseHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/pause";
import { recordingResumeHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/[recordingId]/resume";
import { currentRecordingHandlers } from "./api/v1/workspaces/[workspaceId]/recordings/current";
import { feedbackRelayHandlers } from "./relay/f";
import { releaseNotesRelayHandlers } from "./relay/n";

// 리다이렉트 엔드포인트(OAuth 시작·로그아웃)는 XHR 응답이 아니라 두지 않아요
export const handlers = [
  ...feedbackRelayHandlers,
  ...releaseNotesRelayHandlers,
  ...authMeHandlers,
  ...authCsrfHandlers,
  ...authNicknameHandlers,
  ...authRefreshHandlers,
  ...lastViewedWorkspaceHandlers,
  ...workspacesHandlers,
  ...workspaceHandlers,
  ...workspaceInvitationHandlers,
  ...workspaceInvitationsHandlers,
  ...workspaceInvitationReissueHandlers,
  ...workspaceLeaveHandlers,
  ...ownershipTransferCandidatesHandlers,
  ...ownershipTransfersHandlers,
  ...workspaceRecordingsHandlers,
  ...currentRecordingHandlers,
  ...recordingHandlers,
  ...recordingPauseHandlers,
  ...recordingResumeHandlers,
  ...recordingEndHandlers,
  ...recordingHeartbeatHandlers,
  ...recordingAudioUploadUrlHandlers,
  ...recordingAudioUploadCompleteHandlers,
  ...recordingAudioChunksHandlers,
  ...recordingAudioChunkCompleteHandlers,
  ...recordingAudioSaveHandlers,
  ...recordingAudioSaveRetryHandlers,
  ...recordingTranscriptionRetryHandlers,
  ...documentGenerationJobRetryHandlers,
  ...documentsHandlers,
  ...documentRecordingsHandlers,
  ...documentHandlers,
  ...documentConfirmationsHandlers,
  ...documentMyConfirmationHandlers,
  ...documentTranscriptHandlers,
  ...invitationAcceptHandlers,
  ...invitationPreviewHandlers,
  ...chatMessagesHandlers,
  ...workspaceSearchConversationsHandlers,
  ...workspaceSearchConversationQuestionsHandlers,
  ...workspaceSearchAnswerRetryHandlers,
  ...workspaceSearchAnswerEventsHandlers,
  ...workspaceSearchConversationMessagesHandlers,
];
