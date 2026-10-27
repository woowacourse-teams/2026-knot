interface RecordingDetailKeyParams {
  workspaceId: number;
  recordingId: number;
}

export const recordingKeys = {
  all: ["recordings"] as const,
  current: (workspaceId: number) =>
    [...recordingKeys.all, "current", workspaceId] as const,
  list: (workspaceId: number) =>
    [...recordingKeys.all, "list", workspaceId] as const,
  detail: ({ workspaceId, recordingId }: RecordingDetailKeyParams) =>
    [...recordingKeys.all, "detail", workspaceId, recordingId] as const,
  audioSave: ({ workspaceId, recordingId }: RecordingDetailKeyParams) =>
    [...recordingKeys.all, "audioSave", workspaceId, recordingId] as const,
};

/** 녹음 뮤테이션의 키. 정리 화면의 사용 기록이 뮤테이션 캐시에서 다시 시도 응답을 찾을 때 써요 */
export const recordingMutationKeys = {
  transcriptionRetry: () =>
    [...recordingKeys.all, "transcriptionRetry"] as const,
  documentGenerationRetry: () =>
    [...recordingKeys.all, "documentGenerationRetry"] as const,
};
