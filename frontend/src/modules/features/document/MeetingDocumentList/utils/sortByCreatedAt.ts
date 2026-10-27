interface CreatedItem {
  id: number;
  /** 만든 시각(ISO 8601) */
  createdAt: string;
}

/**
 * 문서를 만든 순서(만든 시각, 같으면 id가 작은 것 먼저)로 늘어놓은 새 배열을 돌려줘요. 받은 배열은 바꾸지 않아요.
 *
 * 문서 탭 「녹음별」 탭은 한 녹음의 문서를 만든 순서로 보여 주는데(DOC-R23), 문서 목록 API는 최신순으로 줘요.
 * 「이 회의에서 나온 문서」를 「녹음별」 탭과 같은 순서로 맞출 때 써요(STT-R52).
 *
 * @example
 * sortByCreatedAt([{ id: 2, createdAt: "2026-10-21T02:00:00Z" }, { id: 1, createdAt: "2026-10-21T01:00:00Z" }]);
 * // [{ id: 1, ... }, { id: 2, ... }]
 */
export const sortByCreatedAt = <T extends CreatedItem>(items: T[]) =>
  [...items].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id,
  );
