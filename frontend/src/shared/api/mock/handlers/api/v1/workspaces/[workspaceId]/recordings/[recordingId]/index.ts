import { getSpeakerCount } from "@api/mock/state/recordingSpeaker";
import { http, HttpResponse } from "msw";

import { findRecordingDetail } from "@api/mock/state/recording";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const recordingHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId",
    ({ params }) => {
      // 문서 만들기를 다시 시도한 녹음은 바뀐 상태를 받도록 mock 상태를 거쳐 찾아요
      const recording = findRecordingDetail(Number(params.recordingId));

      if (recording === undefined) {
        return HttpResponse.json(
          {
            code: "RECORDING_NOT_FOUND",
            message: "녹음 세션을 찾을 수 없습니다.",
          },
          { status: 404 },
        );
      }

      return HttpResponse.json({
        ...recording,
        speakerCount: getSpeakerCount(
          Number(params.workspaceId),
          Number(params.recordingId),
        ),
      });
    },
  ),
];
