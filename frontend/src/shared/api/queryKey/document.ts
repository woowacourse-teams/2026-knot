interface DocumentListKeyParams {
  workspaceId: number;
  /** 이 녹음에서 나온 문서만 담은 목록. 없으면 워크스페이스의 문서 전체 */
  recordingSessionId?: number;
}

interface DocumentRecentListKeyParams {
  workspaceId: number;
}

interface DocumentPendingListKeyParams {
  workspaceId: number;
}

interface DocumentRecordingListKeyParams {
  workspaceId: number;
}

interface DocumentDetailKeyParams {
  workspaceId: number;
  documentId: number;
}

export const documentKeys = {
  all: ["documents"] as const,
  list: ({ workspaceId, recordingSessionId }: DocumentListKeyParams) =>
    [...documentKeys.all, "list", workspaceId, { recordingSessionId }] as const,
  // 문서 탭 「녹음별」 탭의 녹음 묶음 목록. 같은 문서를 녹음 단위로 다시 받는 다른 조회라 키를 따로 둬요
  recordingList: ({ workspaceId }: DocumentRecordingListKeyParams) =>
    [...documentKeys.all, "recordingList", workspaceId] as const,
  // 홈 카드의 최근 문서. 문서 전체 목록과 받는 범위가 달라 키를 따로 둬요
  recentList: ({ workspaceId }: DocumentRecentListKeyParams) =>
    [...documentKeys.all, "recentList", workspaceId] as const,
  // 내가 아직 확인하지 않은 문서만 담은 목록. 문서를 확인하면 이 목록만 다시 받으려고 키를 따로 둬요
  pendingList: ({ workspaceId }: DocumentPendingListKeyParams) =>
    [...documentKeys.all, "pendingList", workspaceId] as const,
  detail: ({ workspaceId, documentId }: DocumentDetailKeyParams) =>
    [...documentKeys.all, "detail", workspaceId, documentId] as const,
  // 문서 상세 키 아래에 둬서, 상세를 무효화하면 확인 대상도 함께 다시 받아요
  confirmations: (params: DocumentDetailKeyParams) =>
    [...documentKeys.detail(params), "confirmations"] as const,
  // 문서 상세 키 아래에 두지 않아요. 문서를 확인해 상세를 무효화해도 원문은 바뀌지 않아 다시 받을 필요가 없어요
  transcript: ({ workspaceId, documentId }: DocumentDetailKeyParams) =>
    [...documentKeys.all, "transcript", workspaceId, documentId] as const,
};
