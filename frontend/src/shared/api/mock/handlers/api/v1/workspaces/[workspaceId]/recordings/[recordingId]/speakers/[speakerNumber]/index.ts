import { http, HttpResponse } from "msw";
import type { SpeakerNameRequest } from "@api/mock/types/recordingSpeaker";
import { setSpeakerName } from "@api/mock/state/recordingSpeaker";
export const speakerNameHandlers = [
  http.patch(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speakers/:speakerNumber",
    async ({ params, request }) => {
      const { displayName } = (await request.json()) as SpeakerNameRequest;
      const recordingId = Number(params.recordingId);
      const speakerNumber = Number(params.speakerNumber);
      setSpeakerName(
        Number(params.workspaceId),
        recordingId,
        speakerNumber,
        displayName.trim(),
      );
      return HttpResponse.json({
        recordingId,
        speakerNumber,
        displayName: displayName.trim(),
      });
    },
  ),
];
