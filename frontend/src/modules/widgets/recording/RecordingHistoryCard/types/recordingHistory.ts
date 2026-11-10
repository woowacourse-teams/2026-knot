import type useRecordingsInfiniteQuery from "@api/queries/useRecordingsInfiniteQuery";

/** 녹음 기록 한 행. 쿼리 훅이 돌려주는 모양에서 읽어요 */
export type RecordingHistoryItem = NonNullable<
  ReturnType<typeof useRecordingsInfiniteQuery>["data"]
>["pages"][number]["items"][number];

/** 끝난 녹음의 처리 상태 */
export type RecordingProcessingStatus = NonNullable<
  RecordingHistoryItem["processingStatus"]
>;
