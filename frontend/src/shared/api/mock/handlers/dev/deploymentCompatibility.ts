import { recordingsResponse } from "@api/mock/responses/recording";
import { recordingAudioPlaybackResponse } from "@api/mock/responses/recordingDocument";
import { http, HttpResponse } from "msw";

/** 개발 브라우저에서만 쓰는 배포 시차 응답. 값을 바꾸면 다음 조회부터 적용해 열린 탭의 롤백도 재현해요. */
const STORAGE_KEY = "knot.mock.deploymentCompatibility";

const readScenario = () => {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
};

const unsupportedResponse = () => HttpResponse.json(
  { code: "REQUEST_PATH_NOT_FOUND", message: "요청 경로가 존재하지 않습니다." },
  { status: 404 },
);

const respondToNewApi = (feature: string) => {
  const scenario = readScenario();
  if (scenario === `${feature}-error`) return new HttpResponse(null, { status: 500 });
  if (scenario === `${feature}-network-error`) return HttpResponse.error();
  if (scenario === "legacy" || scenario === feature) return unsupportedResponse();
  // 응답하지 않으면 기존 기본 핸들러가 처리해요.
};

/** browser.ts에서만 기본 핸들러 앞에 등록해요. 프로덕션과 vitest의 기본 응답은 바뀌지 않아요. */
export const deploymentCompatibilityHandlers = [
  http.get("*/api/v1/workspaces/:workspaceId/recordings", () => {
    const scenario = readScenario();
    if (scenario === "missing-retryable") return HttpResponse.json({
      ...recordingsResponse,
      items: recordingsResponse.items.map((item) => ({ ...item, processingStatus: "TRANSCRIPTION_FAILED", retryable: undefined })),
    });
    if (scenario !== "legacy" && scenario !== "unknown") return;
    return HttpResponse.json({
      ...recordingsResponse,
      items: recordingsResponse.items.map(
        ({ recordingId, title, startedAt, recordingDurationSeconds }) => ({
          recordingId,
          title,
          startedAt,
          recordingDurationSeconds,
          ...(scenario === "unknown" ? { processingStatus: "FUTURE_STATUS" } : {}),
        }),
      ),
    });
  }),
  http.get("*/api/v1/workspaces/:workspaceId/document-recordings", () => respondToNewApi("list")),
  http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId/document", () => respondToNewApi("document")),
  http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId/transcript", () => respondToNewApi("transcript")),
  http.get("*/api/v1/workspaces/:workspaceId/recordings/:recordingId/audio-playback", () => {
    if (readScenario() === "expired-audio") return HttpResponse.json({
      ...recordingAudioPlaybackResponse,
      expiresAt: new Date(Date.now() - 60_000).toISOString(),
    });
    return respondToNewApi("audio");
  }),
];
