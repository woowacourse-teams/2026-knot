export const workspaceKeys = {
  members: (workspaceId: number) =>
    ["workspaces", "members", workspaceId] as const,
  all: ["workspaces"] as const,
  list: () => [...workspaceKeys.all, "list"] as const,
  detail: (workspaceId: number) =>
    [...workspaceKeys.all, "detail", workspaceId] as const,
  ownershipTransferCandidates: (workspaceId: number) =>
    [...workspaceKeys.all, "ownershipTransferCandidates", workspaceId] as const,
};
