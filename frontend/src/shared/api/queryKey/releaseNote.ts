export const releaseNoteKeys = {
  all: ["releaseNote"] as const,
  list: () => [...releaseNoteKeys.all, "list"] as const,
};
