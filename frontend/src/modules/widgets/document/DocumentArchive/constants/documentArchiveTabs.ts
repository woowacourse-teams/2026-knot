/** 문서 탭의 두 탭. 적힌 순서대로 왼쪽부터 놓고, 문서 탭은 늘 첫 탭(녹음별)으로 열려요(DOC-R20) */
export const DOCUMENT_ARCHIVE_TABS = [
  { value: "recording", label: "녹음별" },
  { value: "topic", label: "주제별" },
] as const;

export type DocumentArchiveTab =
  (typeof DOCUMENT_ARCHIVE_TABS)[number]["value"];
