import useCurrentRecordingQuery from "@api/queries/useCurrentRecordingQuery";
import useNow from "@hooks/common/useNow";
import { calculateElapsedSeconds } from "@utils/calculateElapsedSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { logRequestError } from "@utils/logRequestError";
import { useEffect } from "react";
import { useParams } from "react-router";

import type { CurrentRecordingStatus } from "@/shared/types/recording";

type ServerRecordingStatus = NonNullable<
  ReturnType<typeof useCurrentRecordingQuery>["data"]
>["status"];

/**
 * 서버의 진행 상태가 어느 화면 상태가 되는지 정한 표.
 *
 * 훅이 이 표를 응답의 `status`로 읽으므로, 서버 상태가 늘었는데 여기에 없으면 읽는 줄에서 타입 오류가 나요.
 */
const RECORDING_STATUS_BY_SERVER_STATUS = {
  RECORDING: "recording",
  PAUSED: "paused",
  PROCESSING: "organizing",
  TRANSCRIPTION_FAILED: "transcriptionFailed",
  DOCUMENT_FAILED: "documentFailed",
} as const satisfies Record<ServerRecordingStatus, CurrentRecordingStatus>;

/**
 * 현재 `:workspaceId`의 현재 녹음 조회 응답을 화면에 보여 줄 상태와 시간으로 바꿔요.
 *
 * 응답의 누적 시간에 응답을 받은 뒤 흐른 시간을 더해 녹음 중이면 매초 늘리고, 일시정지면 멈춰 둬요.
 * 녹음을 끝내고 문서를 정리하는 중이면 `organizing`이에요. 정리 화면으로 갈 수 있게 그 녹음의 `recordingId`도 함께 돌려줘요.
 * 전사나 문서 생성이 실패했으면 재시도 가능 여부와 관계없이 실패 상태를 유지해요.
 * `isRetryable`과 `retryableJobIds`는 버튼과 실제 재접수 대상을 정하는 데 써요.
 * 표시할 녹음이 없거나(204) 처음 조회가 실패하면 `status`와 `recordingId`가 `null`이에요.
 * 조회 실패는 콘솔에 남기고, 받은 녹음이 아직 없을 때만 `isLoadFailed`로 알려요. 실패 안내는 쓰는 쪽이 정해요(홈 카드는 ERR-09).
 * 한 번 받은 뒤 다시 조회만 실패하면 받은 녹음을 그대로 보여 줘요.
 * `retry`는 현재 녹음만 다시 조회해요.
 */
const useServerRecording = () => {
  const { workspaceId } = useParams();
  const { data, dataUpdatedAt, isLoading, error, refetch } =
    useCurrentRecordingQuery({
      workspaceId: Number(workspaceId),
    });

  useEffect(() => {
    if (error)
      logRequestError({
        feature: "recording",
        action: "현재 녹음 조회",
        error,
      });
  }, [error]);

  const isRecording = data?.status === "RECORDING";

  // 서버가 녹음 중이라고 답한 동안만 시간 표시를 다시 그려요
  const now = useNow(isRecording);

  // 타입에 없는 상태를 서버가 보내면 표에서 undefined가 나와요
  const status: CurrentRecordingStatus | null = data
    ? (RECORDING_STATUS_BY_SERVER_STATUS[data.status] ?? null)
    : null;

  return {
    status,
    recordingId: status === null ? null : (data?.recordingId ?? null),
    isRetryable: data?.isRetryable ?? false,
    retryableJobIds: data?.retryableJobIds ?? [],
    elapsedTime: formatRecordingTime(
      calculateElapsedSeconds(
        data?.elapsedMillis ?? 0,
        isRecording ? dataUpdatedAt : null,
        now,
      ),
    ),
    isLoading,
    isLoadFailed: data === undefined && error !== null,
    retry: () => {
      refetch();
    },
  };
};

export default useServerRecording;
