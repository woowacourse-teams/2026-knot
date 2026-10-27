import {
  documentGenerationJobRetryResponse,
  RETRY_EXHAUSTED_JOB_ID,
} from "@api/mock/responses/documentGenerationJob";
import {
  recordingDetailsResponse,
  recordingsResponse,
  recordingTranscriptionRetryResponse,
} from "@api/mock/responses/recording";
import type { DocumentGenerationJobRetryResponse } from "@api/mock/types/documentGenerationJob";
import type {
  RecordingDetailEntry,
  RecordingDetailResponse,
  RecordingHistoryItem,
  RecordingTranscriptionRetryResponse,
} from "@api/mock/types/recording";

// 기본 응답은 녹음마다 값이 그대로 타입이 되어 있어요(예: 빈 배열). 여기서는 응답 타입으로 넓혀 읽어요
const recordingDetails: RecordingDetailEntry[] = recordingDetailsResponse;
const recordingHistoryItems: RecordingHistoryItem[] = recordingsResponse.items;

// 다시 시도를 접수한 문서 생성 작업의 ID.
// 기본 응답(responses)은 테스트의 기대값으로도 쓰여서 바꾸지 않고, 바뀐 것만 여기에 적어요
const retriedJobIds = new Set<number>();
// 전사를 다시 접수한 녹음의 ID
const transcriptionRetriedRecordingIds = new Set<number>();

/** 다시 시도한 기록을 지워 기본 응답 상태로 되돌려요. 테스트와 스토리 사이에 불러요 */
export const resetRecordingMockState = () => {
  retriedJobIds.clear();
  transcriptionRetriedRecordingIds.clear();
};

/**
 * 녹음 상세. 실패한 작업을 다시 시도한 녹음은 그 작업이 실패 목록에서 빠지고 다시 정리 중인 상태로,
 * 전사를 다시 접수한 녹음은 새 시도 ID로 전사 중인 상태로 돌려줘요
 */
export const findRecordingDetail = (recordingId: number) => {
  const recording = recordingDetails.find(
    (entry) => entry.recordingId === recordingId,
  );

  if (recording === undefined) return undefined;

  const { detail } = recording;

  // 전사를 다시 접수하면 서버는 새 시도 ID로 전사 중이 되고 실패 코드를 지워요
  if (transcriptionRetriedRecordingIds.has(recordingId)) {
    return {
      ...detail,
      transcriptionAttemptId: recordingTranscriptionRetryResponse.attemptId,
      transcriptionStatus: "PROCESSING",
      transcriptionFailureCode: null,
    } satisfies RecordingDetailResponse;
  }

  if (detail.documentGeneration === null) return detail;

  const failedJobIds = detail.documentGeneration.failedJobIds.filter(
    (jobId) => !retriedJobIds.has(jobId),
  );

  if (failedJobIds.length === detail.documentGeneration.failedJobIds.length) {
    return detail;
  }

  // 다시 시도를 접수하면 서버는 그 작업을 대기로 바꾸고, 문서 생성 결과도 대기로 돌려줘요
  return {
    ...detail,
    documentGeneration: {
      ...detail.documentGeneration,
      status: "QUEUED",
      failedJobIds,
    },
  } satisfies RecordingDetailResponse;
};

/**
 * 녹음 기록 목록 전체. 전사나 문서 생성 작업을 다시 접수한 녹음은 서버처럼 문서 정리 중으로 돌려줘요.
 * 작업 일부만 다시 접수해도 대기 중인 작업이 실패보다 먼저라 정리 중이에요
 */
export const findRecordingHistoryItems = () =>
  recordingHistoryItems.map((item) => {
    const isRetried =
      transcriptionRetriedRecordingIds.has(item.recordingId) ||
      item.retryableJobIds.some((jobId) => retriedJobIds.has(jobId));

    if (!isRetried) return item;

    return {
      ...item,
      processingStatus: "PROCESSING",
      retryable: false,
      retryableJobIds: [],
    } satisfies RecordingHistoryItem;
  });

/**
 * 문서 생성 작업의 다시 시도를 기록해요.
 * 어느 녹음의 실패 작업도 아니면 `notFound`, 이미 다시 시도해 실패 상태가 아니거나 다시 시도할 수 있는 횟수를 다 쓴 작업이면 `notAllowed`를 돌려줘요(서버와 같은 기준).
 */
export const retryDocumentGenerationJob = (jobId: number) => {
  const isFailedJob = recordingDetails.some(({ detail }) =>
    detail.documentGeneration?.failedJobIds.includes(jobId),
  );

  if (!isFailedJob) return { result: "notFound" } as const;

  if (jobId === RETRY_EXHAUSTED_JOB_ID || retriedJobIds.has(jobId)) {
    return { result: "notAllowed" } as const;
  }

  retriedJobIds.add(jobId);

  return {
    result: "accepted",
    response: {
      ...documentGenerationJobRetryResponse,
      jobId,
    } satisfies DocumentGenerationJobRetryResponse,
  } as const;
};

/**
 * 녹음 전사의 다시 시도를 기록해요.
 * 없는 녹음이면 `notFound`, 전사가 실패 상태가 아니면(전사 전 · 전사 중 · 성공 · 이미 다시 접수함) `notAllowed`를 돌려줘요(서버와 같은 기준).
 */
export const retryRecordingTranscription = (recordingId: number) => {
  const detail = findRecordingDetail(recordingId);

  if (detail === undefined) return { result: "notFound" } as const;

  if (detail.transcriptionStatus !== "FAILED") {
    return { result: "notAllowed" } as const;
  }

  transcriptionRetriedRecordingIds.add(recordingId);

  return {
    result: "accepted",
    response: {
      ...recordingTranscriptionRetryResponse,
      recordingId,
    } satisfies RecordingTranscriptionRetryResponse,
  } as const;
};
