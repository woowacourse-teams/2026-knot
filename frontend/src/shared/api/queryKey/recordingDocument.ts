import { recordingKeys } from "./recording";

interface RecordingDocumentKeyParams {
  workspaceId: number;
  recordingId: number;
}

/**
 * 녹음 문서 쿼리 키. 녹음 쿼리 키(`recordingKeys.all`) 아래에 둬요.
 * 전사 · 문서 생성 다시 시도가 녹음 쿼리 전체를 무효화할 때 녹음 문서도 함께 다시 받게 하려는 것이에요
 */
export const recordingDocumentKeys = {
  all: [...recordingKeys.all, "document"] as const,
  detail: ({ workspaceId, recordingId }: RecordingDocumentKeyParams) =>
    [...recordingDocumentKeys.all, "detail", workspaceId, recordingId] as const,
  transcript: ({ workspaceId, recordingId }: RecordingDocumentKeyParams) =>
    [
      ...recordingDocumentKeys.all,
      "transcript",
      workspaceId,
      recordingId,
    ] as const,
  audioPlayback: ({ workspaceId, recordingId }: RecordingDocumentKeyParams) =>
    [
      ...recordingDocumentKeys.all,
      "audioPlayback",
      workspaceId,
      recordingId,
    ] as const,
};
