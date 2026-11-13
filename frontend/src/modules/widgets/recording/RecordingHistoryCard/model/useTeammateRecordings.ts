import useTeammateRecordingsQuery from "@api/queries/useTeammateRecordingsQuery";
import useNow from "@hooks/common/useNow";
import { calculateElapsedSeconds } from "@utils/calculateElapsedSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { logRequestError } from "@utils/logRequestError";
import { useEffect } from "react";

import { useWorkspaceId } from "./useWorkspaceId";

const STATUS_BY_SERVER_STATUS = {
  RECORDING: "recording",
  PAUSED: "paused",
} as const;

/**
 * 팀원 줄(STT-R55)에 둘 팀원의 녹음 중 · 일시정지 녹음.
 *
 * 녹음 중인 줄은 응답의 누적 시간에 응답을 받은 뒤 흐른 시간을 더해 매초 늘리고, 일시정지 줄은 멈춰 둬요.
 * 조회가 실패하면 콘솔에 남기고 팀원 줄만 비워요. 끝난 녹음 목록은 그대로 보여 주고, 다음 조회(30초 · 창 포커스)에서 다시 받아요(HOME-R6).
 */
export const useTeammateRecordings = () => {
  const workspaceId = Number(useWorkspaceId());
  const { data, dataUpdatedAt, isLoading, error } = useTeammateRecordingsQuery({
    workspaceId,
  });

  useEffect(() => {
    if (error)
      logRequestError({
        feature: "recording",
        action: "팀원 진행 중 녹음 조회",
        error,
      });
  }, [error]);

  const items = data?.items ?? [];
  const hasRecording = items.some(({ status }) => status === "RECORDING");
  // 녹음 중인 팀원 줄이 있는 동안만 시간 표시를 다시 그려요
  const now = useNow(hasRecording);

  return {
    teammateRecordings: items.map(
      ({ recordingId, status, title, elapsedMillis }) => ({
        recordingId,
        status: STATUS_BY_SERVER_STATUS[status],
        title,
        elapsedTime: formatRecordingTime(
          calculateElapsedSeconds(
            elapsedMillis,
            status === "RECORDING" ? dataUpdatedAt : null,
            now,
          ),
        ),
      }),
    ),
    isLoading,
  };
};
