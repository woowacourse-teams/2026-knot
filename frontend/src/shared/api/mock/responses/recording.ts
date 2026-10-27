import {
  documentDetailsResponse,
  documentsResponse,
} from "@api/mock/responses/document";
import {
  documentGenerationJobRetryResponse,
  RETRY_EXHAUSTED_JOB_ID,
} from "@api/mock/responses/documentGenerationJob";
import type {
  CurrentRecordingResponse,
  RecordingAudioChunkCompleteResponse,
  RecordingAudioChunkReserveResponse,
  RecordingAudioSaveResponse,
  RecordingAudioUploadCompleteResponse,
  RecordingAudioUploadUrlResponse,
  RecordingDetailEntry,
  RecordingDetailResponse,
  RecordingEndResponse,
  RecordingHeartbeatResponse,
  RecordingHistoryItem,
  RecordingPauseResponse,
  RecordingResumeResponse,
  RecordingsResponse,
  RecordingStartResponse,
  RecordingTranscriptionRetryResponse,
} from "@api/mock/types/recording";

const MINUTE = 60 * 1000;

// 고정 시각은 언젠가 만료·과거가 되므로 지금 기준으로 만들어요
const fromNow = (offset: number) => new Date(Date.now() + offset).toISOString();

/**
 * Presigned URL이 가리키는 가짜 객체 저장소. 실제 저장소는 우리 API와 오리진이 달라
 * 와일드카드 대신 이 오리진으로 PUT을 가로채요
 */
export const MOCK_AUDIO_STORAGE_ORIGIN = "https://audio-storage.knot.mock";

export const currentRecordingResponse = {
  recordingId: 10,
  status: "RECORDING",
  startedAt: fromNow(-12 * MINUTE),
  elapsedMillis: 12 * MINUTE,
} satisfies CurrentRecordingResponse;

export const recordingStartResponse = {
  recordingId: 10,
  status: "RECORDING",
  startedAt: fromNow(0),
} satisfies RecordingStartResponse;

export const recordingPauseResponse = {
  recordingId: 10,
  status: "PAUSED",
  pausedAt: fromNow(0),
  elapsedMillis: 10 * MINUTE,
} satisfies RecordingPauseResponse;

export const recordingResumeResponse = {
  recordingId: 10,
  status: "RECORDING",
  resumedAt: fromNow(0),
  elapsedMillis: 10 * MINUTE,
} satisfies RecordingResumeResponse;

export const recordingEndResponse = {
  recordingId: 10,
  status: "ENDED",
  endedAt: fromNow(0),
} satisfies RecordingEndResponse;

// 서버는 마지막 신호부터 30초 동안 신호가 없으면 녹음을 끝내요
export const recordingHeartbeatResponse = {
  recordingId: 10,
  status: "RECORDING",
  elapsedMillis: 10 * MINUTE,
  lastSeenAt: fromNow(0),
  expiresAt: fromNow(MINUTE / 2),
  endedAt: null,
  endReason: null,
  serverNow: fromNow(0),
} satisfies RecordingHeartbeatResponse;

// 32분 녹음을 8분 전에 끝내고 오디오 업로드까지 확인된 뒤의 공통 값이에요
const endedRecording = {
  status: "ENDED",
  elapsedMillis: 32 * MINUTE,
  endedAt: fromNow(-8 * MINUTE),
  endReason: "USER_ENDED",
  audioUploadStatus: "COMPLETED",
} satisfies Partial<RecordingDetailResponse>;

const TRANSCRIPTION_ATTEMPT_ID = "d0a03c18-1687-4512-8f79-1df67a8e56ef";
// 전사에 실패한 녹음의 ID. 전사 다시 시도 mock 응답이 가리키는 녹음이에요
const TRANSCRIPTION_FAILED_RECORDING_ID = 13;

// 전사까지 끝나 문서 생성이 접수된 뒤의 공통 값이에요
const transcribedRecording = {
  ...endedRecording,
  transcriptionAttemptId: TRANSCRIPTION_ATTEMPT_ID,
  transcriptionStatus: "SUCCEEDED",
  transcriptionFailureCode: null,
} satisfies Partial<RecordingDetailResponse>;

// 문서 mock의 문서(회원 탈퇴 정책 · 홈 개편 논의)가 나온 녹음의 ID.
// 정리가 끝난 녹음을 이 ID로 둬야, 정리 화면이 그 녹음의 문서로 이어져요
const [{ recordingSessionId: COMPLETED_RECORDING_ID }] =
  documentDetailsResponse;

// 녹음을 끝낸 뒤의 상태를 녹음마다 하나씩 둬, 정리 화면 주소의 녹음 ID만 바꿔 각 상태를 볼 수 있어요.
// 차례로 정리 중 · 문서로 만들 내용 없음 · 문서 만들기 실패 · 전사 실패 · 정리 완료 ·
// 일부만 만들어진 실패(실패한 작업 둘) · 다시 시도 횟수를 다 쓴 실패예요.
// 10은 녹음 시작·종료 mock과 같은 녹음이라, 끝낸 직후에 보게 되는 정리 중으로 뒀어요
export const recordingDetailsResponse = [
  {
    recordingId: 10,
    detail: {
      ...transcribedRecording,
      documentGeneration: {
        status: "RUNNING",
        succeededCount: 0,
        failedJobIds: [],
      },
    },
  },
  {
    recordingId: 11,
    detail: {
      ...transcribedRecording,
      documentGeneration: {
        status: "NO_CONTENT",
        succeededCount: 0,
        failedJobIds: [],
      },
    },
  },
  {
    recordingId: 12,
    detail: {
      ...transcribedRecording,
      documentGeneration: {
        status: "FAILED",
        succeededCount: 0,
        // 다시 시도 mock 응답(documentGenerationJobRetryResponse)이 가리키는 작업이에요
        failedJobIds: [documentGenerationJobRetryResponse.jobId],
      },
    },
  },
  {
    recordingId: TRANSCRIPTION_FAILED_RECORDING_ID,
    detail: {
      ...endedRecording,
      transcriptionAttemptId: TRANSCRIPTION_ATTEMPT_ID,
      transcriptionStatus: "FAILED",
      transcriptionFailureCode: "AI_TIMEOUT",
      // 전사가 실패하면 문서 생성이 접수되지 않아요
      documentGeneration: null,
    },
  },
  {
    recordingId: COMPLETED_RECORDING_ID,
    detail: {
      ...transcribedRecording,
      documentGeneration: {
        status: "SUCCEEDED",
        succeededCount: documentsResponse.items.filter(
          ({ recordingSessionId }) =>
            recordingSessionId === COMPLETED_RECORDING_ID,
        ).length,
        failedJobIds: [],
      },
    },
  },
  {
    recordingId: 14,
    detail: {
      ...transcribedRecording,
      // 주제 넷 가운데 둘은 문서가 만들어졌고, 나머지 둘의 작업이 실패했어요
      documentGeneration: {
        status: "FAILED",
        succeededCount: 2,
        failedJobIds: [89, 90],
      },
    },
  },
  {
    recordingId: 15,
    detail: {
      ...transcribedRecording,
      documentGeneration: {
        status: "FAILED",
        succeededCount: 0,
        // 다시 시도하면 서버가 거절하는 작업이에요
        failedJobIds: [RETRY_EXHAUSTED_JOB_ID],
      },
    },
  },
] satisfies RecordingDetailEntry[];

const DAY = 24 * 60 * MINUTE;

// 처리 상태마다 한 행씩 두고, 녹음 상세 mock과 같은 ID를 써서 다시 시도가 같은 녹음 · 작업을 접수하게 해요.
// 문서 정리 중 행(16)은 현재 녹음 mock(10, 녹음 중)과 겹치지 않게 상세가 없는 ID로 뒀어요
const recordingHistoryHeadItems = [
  {
    recordingId: 16,
    title: "노티드 님의 녹음",
    startedAt: fromNow(-40 * MINUTE),
    recordingDurationSeconds: 25 * 60,
    processingStatus: "PROCESSING",
    retryable: false,
    retryableJobIds: [],
  },
  {
    recordingId: 101,
    title:
      "알림 정책 정리 회의와 다음 분기 알림 채널 우선순위, 실패 알림 문구 개선안까지 함께 논의한 긴 회의",
    startedAt: fromNow(-3 * 60 * MINUTE),
    recordingDurationSeconds: 48 * 60 + 20,
    processingStatus: "COMPLETED",
    retryable: false,
    retryableJobIds: [],
  },
  {
    recordingId: 12,
    title: "결제 화면 개편 회의",
    startedAt: fromNow(-1 * DAY),
    recordingDurationSeconds: 32 * 60,
    processingStatus: "DOCUMENT_FAILED",
    retryable: true,
    retryableJobIds: [documentGenerationJobRetryResponse.jobId],
  },
  {
    recordingId: TRANSCRIPTION_FAILED_RECORDING_ID,
    title: "노티드 님의 녹음",
    startedAt: fromNow(-2 * DAY),
    recordingDurationSeconds: 32 * 60,
    processingStatus: "TRANSCRIPTION_FAILED",
    retryable: true,
    retryableJobIds: [],
  },
  {
    recordingId: 11,
    title: "노티드 님의 녹음",
    startedAt: fromNow(-3 * DAY),
    recordingDurationSeconds: 45,
    processingStatus: "NO_CONTENT",
    retryable: false,
    retryableJobIds: [],
  },
  {
    recordingId: COMPLETED_RECORDING_ID,
    title: "회원 탈퇴 정책 회의",
    startedAt: fromNow(-4 * DAY),
    recordingDurationSeconds: 62 * 60 + 5,
    processingStatus: "COMPLETED",
    retryable: false,
    retryableJobIds: [],
  },
  {
    recordingId: 14,
    title: "온보딩 인터뷰 · 참가자 3",
    startedAt: fromNow(-5 * DAY),
    recordingDurationSeconds: 52 * 60,
    processingStatus: "DOCUMENT_FAILED",
    retryable: true,
    retryableJobIds: [89, 90],
  },
  {
    // 다시 시도할 수 있는 횟수를 다 써서 서버가 다시 시도할 수 없다고 알려 준 행이에요
    recordingId: 15,
    title: "비밀번호 재설정 정책 논의",
    startedAt: fromNow(-6 * DAY),
    recordingDurationSeconds: 35 * 60,
    processingStatus: "DOCUMENT_FAILED",
    retryable: false,
    retryableJobIds: [],
  },
] satisfies RecordingHistoryItem[];

// 다음 묶음을 볼 수 있게 한 페이지(20개)를 넘기는, 정리가 끝난 지난 녹음이에요
const recordingHistoryPastItems = Array.from(
  { length: 17 },
  (_, index) =>
    ({
      recordingId: 100 - index,
      title: `주간 회의 ${17 - index}회차`,
      startedAt: fromNow(-(7 + index) * DAY),
      recordingDurationSeconds: (20 + index) * 60,
      processingStatus: "COMPLETED",
      retryable: false,
      retryableJobIds: [],
    }) satisfies RecordingHistoryItem,
);

// 홈 「녹음 기록」 카드의 녹음 전체예요. 핸들러가 20개씩 잘라서 돌려줘요.
// 순서는 서버 정렬(녹음 시작 시각 내림차순, 같으면 녹음 ID 내림차순)이에요
export const recordingsResponse = {
  items: [...recordingHistoryHeadItems, ...recordingHistoryPastItems],
  nextCursor: null,
} satisfies RecordingsResponse;

// 전사에 실패한 녹음의 전사를 다시 접수했을 때의 응답이에요. 새 시도라 녹음 상세의 시도 ID와 달라요
export const recordingTranscriptionRetryResponse = {
  recordingId: TRANSCRIPTION_FAILED_RECORDING_ID,
  attemptId: "6f2b9c44-3d1e-4b7a-9f0c-2a8e5d7c1b33",
  transcriptionStatus: "PROCESSING",
} satisfies RecordingTranscriptionRetryResponse;

export const recordingAudioUploadUrlResponse = {
  uploadId: 300,
  uploadUrl: `${MOCK_AUDIO_STORAGE_ORIGIN}/recordings/10/audio`,
  expiresAt: fromNow(5 * MINUTE),
} satisfies RecordingAudioUploadUrlResponse;

export const recordingAudioUploadCompleteResponse = {
  recordingId: 10,
  uploadId: 300,
  uploadStatus: "COMPLETED",
  completedAt: fromNow(0),
} satisfies RecordingAudioUploadCompleteResponse;

// 5초 무음 PCM(값이 0인 160,000바이트)의 SHA-256. BE 문서의 예시 조각과 같아요
const SILENT_CHUNK_CHECKSUM = "uc4WTTDkEBsAn+S+dloHBZPPvdSPiXhT3hWajBd/q+g=";

export const recordingAudioChunkReserveResponse = {
  sequence: 1,
  status: "RESERVED",
  uploadUrl: `${MOCK_AUDIO_STORAGE_ORIGIN}/recordings/10/audio-chunks/1`,
  expiresAt: fromNow(5 * MINUTE),
  requiredHeaders: {
    "content-type": "application/octet-stream",
    "if-none-match": "*",
    "x-amz-checksum-sha256": SILENT_CHUNK_CHECKSUM,
  },
} satisfies RecordingAudioChunkReserveResponse;

export const recordingAudioChunkCompleteResponse = {
  sequence: 1,
  status: "VERIFIED",
  verifiedThroughSequence: 1,
  verifiedThroughFrame: 80000,
  verifiedAudioMillis: 5000,
  confirmedAt: fromNow(0),
} satisfies RecordingAudioChunkCompleteResponse;

// 녹음 상세 mock처럼 8분 전에 끝난 녹음이라, 받아 주는 시한(종료 + 2분)도 이미 지났어요.
// 5초 조각 뒤에 1,600프레임 tail까지 모두 확인돼 최종 WAV를 채택한 상태예요
export const recordingAudioSaveResponse = {
  audioSaveStatus: "READY",
  verifiedThroughSequence: 2,
  verifiedThroughFrame: 81600,
  verifiedAudioMillis: 5100,
  verifiedRanges: [{ start: 0, endExclusive: 81600 }],
  missingRanges: [],
  tailUnknown: false,
  expectedLastSequence: 2,
  acceptUntil: fromNow(-6 * MINUTE),
  failureCode: null,
  canRetry: false,
  jobId: 1,
  quality: "COMPLETE",
} satisfies RecordingAudioSaveResponse;
