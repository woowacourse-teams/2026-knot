import { http, HttpResponse } from "msw";

import {
  currentRecordingResponse,
  recordingDetailsResponse,
} from "@api/mock/responses/recording";
import { findRecordingDetail } from "@api/mock/state/recording";

/** 개발 브라우저에서 바꿔 볼 수 있는 현재 녹음 상황 */
export const CURRENT_RECORDING_SCENARIOS = [
  "none",
  "recording",
  "paused",
  "processing",
  "transcriptionFailed",
  "documentFailed",
  "transcriptionFailedNotRetryable",
  "documentFailedNotRetryable",
] as const;
export type CurrentRecordingScenario =
  (typeof CURRENT_RECORDING_SCENARIOS)[number];

interface MockScenario {
  /** 현재 녹음 조회가 답할 상황. `none`이면 녹음이 없어요(204) */
  currentRecording: CurrentRecordingScenario;
}

/** 브라우저에 남겨 두는 자리. 새로고침해도 고른 상황이 이어져요 */
export const MOCK_SCENARIO_STORAGE_KEY = "knot.mock.scenario";

/** 주소에 `?mock.currentRecording=paused`처럼 붙여도 골라져요 */
const QUERY_PREFIX = "mock.";

const DEFAULT_SCENARIO: MockScenario = { currentRecording: "none" };

const isCurrentRecordingScenario = (
  value: unknown,
): value is CurrentRecordingScenario =>
  typeof value === "string" &&
  (CURRENT_RECORDING_SCENARIOS as readonly string[]).includes(value);

/** 저장된 상황을 읽어요. 없거나 깨졌으면 기본값이에요 */
export const readMockScenario = (): MockScenario => {
  try {
    const raw = localStorage.getItem(MOCK_SCENARIO_STORAGE_KEY);
    if (raw === null) return DEFAULT_SCENARIO;
    const parsed: unknown = JSON.parse(raw);
    const currentRecording =
      typeof parsed === "object" &&
      parsed !== null &&
      "currentRecording" in parsed
        ? parsed.currentRecording
        : undefined;
    return {
      currentRecording: isCurrentRecordingScenario(currentRecording)
        ? currentRecording
        : DEFAULT_SCENARIO.currentRecording,
    };
  } catch {
    return DEFAULT_SCENARIO;
  }
};

export const writeMockScenario = (scenario: Partial<MockScenario>) => {
  const next = { ...readMockScenario(), ...scenario };
  localStorage.setItem(MOCK_SCENARIO_STORAGE_KEY, JSON.stringify(next));
  return next;
};

/** 주소의 `mock.*` 파라미터를 읽어 저장해요. 읽은 게 있으면 `true`예요 */
export const applyMockScenarioFromQuery = (search: string) => {
  const params = new URLSearchParams(search);
  const currentRecording = params.get(`${QUERY_PREFIX}currentRecording`);
  if (!isCurrentRecordingScenario(currentRecording)) return false;
  writeMockScenario({ currentRecording });
  return true;
};

const STATUS_BY_SCENARIO = {
  recording: "RECORDING",
  paused: "PAUSED",
  processing: "PROCESSING",
} as const;

const TRANSCRIPTION_FAILED_RECORDING = recordingDetailsResponse.find(
  ({ detail }) => detail.transcriptionStatus === "FAILED",
)!;
const DOCUMENT_FAILED_RECORDING = recordingDetailsResponse.find(
  ({ detail }) => detail.documentGeneration?.status === "FAILED",
)!;

const failedResponse = (scenario: CurrentRecordingScenario) => {
  const isDocumentFailure =
    scenario === "documentFailed" || scenario === "documentFailedNotRetryable";
  const recordingId = isDocumentFailure
    ? DOCUMENT_FAILED_RECORDING.recordingId
    : TRANSCRIPTION_FAILED_RECORDING.recordingId;
  const detail = findRecordingDetail(recordingId);
  const isRetried =
    detail?.transcriptionStatus === "PROCESSING" ||
    detail?.documentGeneration?.status === "QUEUED";
  const isRetryable = !isRetried && !scenario.endsWith("NotRetryable");

  return {
    ...currentRecordingResponse,
    recordingId,
    status: isRetried
      ? "PROCESSING"
      : isDocumentFailure
        ? "DOCUMENT_FAILED"
        : "TRANSCRIPTION_FAILED",
    retryable: isRetryable,
    retryableJobIds:
      isDocumentFailure && isRetryable
        ? (detail?.documentGeneration?.failedJobIds ?? [])
        : [],
  };
};

/**
 * 개발 브라우저 전용 상황 핸들러.
 *
 * 기본 mock은 늘 「다른 탭에서 12분째 녹음 중」이라 독의 기본 모양이나 일시정지 칩을 개발 서버에서 볼 수 없어요.
 * 이 핸들러는 브라우저에 저장한 상황을 읽어 현재 녹음 조회를 달리 답해요. 기본은 녹음 없음이에요.
 * `browser.ts`에서만 기본 핸들러 앞에 두고, vitest(server.ts)에는 넣지 않아요.
 */
export const devScenarioHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/recordings/current", () => {
    const { currentRecording } = readMockScenario();
    if (currentRecording === "none")
      return new HttpResponse(null, { status: 204 });
    if (
      currentRecording === "recording" ||
      currentRecording === "paused" ||
      currentRecording === "processing"
    ) {
      return HttpResponse.json({
        ...currentRecordingResponse,
        status: STATUS_BY_SCENARIO[currentRecording],
      });
    }
    return HttpResponse.json(failedResponse(currentRecording));
  }),
];
