import { http, HttpResponse } from "msw";

import { documentRecordingsResponse } from "@api/mock/responses/document";
import { recordingDocumentResponse } from "@api/mock/responses/recordingDocument";

// 경로 파라미터가 있어 fetch 상수 대신 패턴을 직접 적어요
export const recordingDocumentHandlers = [
  http.get(
    "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/document",
    ({ params }) => {
      const recordingId = Number(params.recordingId);
      // 문서 탭 「녹음별」 mock의 녹음이면 어느 녹음을 눌러도 열리도록, 그 녹음의 id와 제목만 바꿔 같은 녹음 문서를 돌려줘요
      const recording = documentRecordingsResponse.items.find(
        (item) => item.recordingId === recordingId,
      );

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
        ...recordingDocumentResponse,
        recordingId,
        title: recording.title,
      });
    },
  ),
];
