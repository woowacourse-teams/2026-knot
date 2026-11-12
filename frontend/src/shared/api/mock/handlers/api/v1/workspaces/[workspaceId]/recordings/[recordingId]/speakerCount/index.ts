import { http, HttpResponse } from "msw";
import type { SpeakerCountRequest } from "@api/mock/types/recordingSpeaker";
import { setSpeakerCount } from "@api/mock/state/recordingSpeaker";
export const speakerCountHandlers = [
  http.patch(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speaker-count",
    async ({ params, request }) => {
      const { speakerCount } = (await request.json()) as SpeakerCountRequest;
      setSpeakerCount(
        Number(params.workspaceId),
        Number(params.recordingId),
        speakerCount,
      );
      return HttpResponse.json({
        recordingId: Number(params.recordingId),
        speakerCount,
      });
    },
  ),
];
