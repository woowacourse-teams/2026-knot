import type useReleaseNotesQuery from "@api/queries/useReleaseNotesQuery";

/** 머지 하나(또는 옮겨 온 릴리즈 하나)에 낸 새 소식 노트. 새 소식 쿼리의 응답 항목에서 추론해요 */
export type ReleaseNote = NonNullable<
  ReturnType<typeof useReleaseNotesQuery>["data"]
>["notes"][number];

/** 새 소식 노트의 항목 하나. 사용자가 알아챌 만한 변화 하나예요 */
export type ReleaseNoteItem = ReleaseNote["items"][number];
