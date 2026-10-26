import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import useRetryDocumentGenerationJobsMutation from "@api/mutations/useRetryDocumentGenerationJobsMutation";
import useRecordingAudioSaveQuery from "@api/queries/useRecordingAudioSaveQuery";
import useRecordingQuery from "@api/queries/useRecordingQuery";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { useToast } from "@provider/context/toastContext";
import { getRecordingDocumentsStatus } from "@utils/getRecordingDocumentsStatus";
import { isRetryRejectedError } from "@utils/isRetryRejectedError";
import { isUnauthorizedError } from "@utils/isUnauthorizedError";
import { useEffect, useState } from "react";

import { useTranscriptionRetry } from "./useTranscriptionRetry";

interface UseRecordingDocumentsParams {
  workspaceId: number;
  recordingId: number;
}

/** 다시 물어도 결과가 같은 조회 실패(4xx). 이 실패를 받으면 3초마다 하던 조회도 멈춰요 */
const isUnrecoverableLoadError = (error: unknown) =>
  isHttpError(error) && error.isClientError;

/**
 * 정리 화면이 그릴 상태(`viewStatus`)를 정해요. 주소에서 읽은 id를 확인하지 않고 그대로 조회하고, 잘못된 id는 서버 응답(400 · 404)으로 알아요.
 *
 * `viewStatus`는 녹음 조회 응답으로 정한 문서 정리 상태(`getRecordingDocumentsStatus`)에 조회 결과와 오류를 더해 해석한 값이에요.
 *
 * - `loading`: 녹음 상태를 아직 받지 못했어요. 401이면 로그인 화면으로 보내는 동안에도 이 상태로 둬요.
 * - `loadFailed`: 녹음 상태를 보여 주지 못해요. `retryLoad`로 다시 조회해요.
 *   400 · 403 · 404처럼 다시 물어도 결과가 같은 실패는 조회가 멈추므로, 앞서 받은 상태가 있어도 이 상태예요.
 *   네트워크 · 서버 문제는 받은 상태가 없을 때만 이 상태예요.
 * - 그 밖에는 응답으로 정한 문서 정리 상태 그대로예요.
 *   `organizing`(정리 중) · `noContent`(문서로 만들 내용 없음) · `failed`(문서를 만들지 못함),
 *   그리고 이 화면에 머물 이유가 없는 `completed`(정리가 끝남) · `recording`(아직 끝내지 않은 녹음)이에요.
 * - `audioSaveFailed`: PCM 조각 녹음의 서버 저장이 끝내 실패했거나 폐기되어 정리할 음성이 없어요. 이 화면에 머물 이유가 없어요.
 *   끝낸 녹음의 전사가 시작되기 전(전사 상태와 문서 생성 결과가 모두 null)에는 녹음 상태와 함께 저장 상태도 3초마다 조회해요.
 *   녹음 상태 응답에는 어떤 방식의 녹음인지(audioMode)가 없어 PCM인지 미리 알 수 없어서, 모든 녹음에 조회하고 WebM 녹음은 409로 알아요.
 *   저장된 음성이 없는 실패(`AUDIO_NO_CONTENT`)는 문서로 만들 내용이 없는 것과 같은 결과라 `noContent`예요.
 *   저장 중(`DRAINING` · `FINALIZING`)도 `organizing`이에요. 저장 상태 조회가 실패해도 `organizing`이지만,
 *   정리 중이 끝없이 이어지지 않도록 `isAudioSaveCheckFailed`로 알려요. `retryAudioSaveCheck`는 저장 상태만 다시 조회해요.
 *   WebM 녹음이라는 409와 로그인이 풀린 401은 알리지 않아요. 409는 실패가 아니고, 401은 녹음 상태 조회가 로그인 화면으로 보내요.
 *   조회는 실패해도 같은 간격으로 계속하므로, 저장 상태를 다시 받으면 알림이 저절로 풀려요.
 *   서버가 버린 녹음처럼 정리 상태를 정할 수 없는 응답은 보여 줄 화면이 없어 `loadFailed`예요.
 *   `failed`에서 다시 시도할 수 있으면 `retryGeneration`이 있어요.
 *
 * 전사가 실패했으면(`transcriptionStatus`가 `FAILED`) `failed`와 함께 `isTranscriptionFailed`가 `true`예요.
 * 화면은 이 값으로 전사 실패와 문서 생성 실패를 제목으로 나눠요(STT-R22).
 * 전사가 실패했으면 문서 만들기가 접수되지 않아 다시 시도할 작업이 없고, 대신 `retryGeneration`이 전사부터 다시 접수해요.
 * 다시 접수와 그 거절 처리는 `useTranscriptionRetry`가 맡아요. 더 시도할 수 없는 거절(409 · 404)을 받으면 `retryGeneration`이 없어져요.
 *
 * 문서 다시 시도는 문서 만들기가 실패로 끝났고 다시 시도할 작업이 남아 있을 때만 할 수 있어요.
 * 실패한 작업이 여러 개면 한 번에 모두 다시 시도해요. 하나라도 접수되면 녹음 상태를 다시 받아 정리 중으로 돌아가요.
 * 남은 실패 작업이 모두 거절된(409 · 404) 작업이면 더 시도할 수 없어 `retryGeneration`을 없애요.
 * 네트워크 · 서버 문제로 실패한 작업은 거절된 것이 아니라 다시 시도할 수 있어요.
 * 그런 실패가 있고 접수된 작업이 하나도 없으면 오류 토스트로 알려요(STT-R22). 거절(409 · 404)만 있거나 로그인이 풀렸으면(401) 알리지 않아요.
 * 409는 화면이 아는 녹음 상태와 서버의 녹음 상태가 다를 수 있다는 응답이라, 녹음 상태를 다시 조회해요.
 * 서버에서 이미 정리 중이거나 정리가 끝났으면 그 상태의 화면으로 바뀌어요.
 * 이 조회가 끝날 때까지도 다시 시도 중(`isRetrying`)이에요.
 *
 * 쓰는 쪽이 구조분해해서 받을 수 있게, 어느 상태든 같은 이름의 값을 모두 돌려줘요.
 */
export const useRecordingDocuments = ({
  workspaceId,
  recordingId,
}: UseRecordingDocumentsParams) => {
  const {
    data: recording,
    error,
    isError,
    refetch,
  } = useRecordingQuery({ workspaceId, recordingId });
  // 끝낸 녹음의 전사가 시작되기 전이에요. 이 동안만 저장 상태를 함께 조회해요
  const isAwaitingAudioSave =
    recording?.sessionStatus === "ENDED" &&
    recording.transcriptionStatus === null &&
    recording.documentGeneration === null;
  const {
    data: audioSave,
    error: audioSaveError,
    refetch: refetchAudioSave,
  } = useRecordingAudioSaveQuery({
    workspaceId,
    recordingId,
    isEnabled: isAwaitingAudioSave,
  });
  // 전사 전에 저장이 끝내 실패한 결과예요. 저장 중이거나 조회에 실패했으면 결과가 없어 정리 중을 유지해요
  const audioSaveFailure =
    isAwaitingAudioSave &&
    (audioSave?.audioSaveStatus === "FAILED" ||
      audioSave?.audioSaveStatus === "DISCARDED")
      ? audioSave
      : undefined;
  // 전사 전에 저장 상태를 확인하지 못하고 있어요. WebM 녹음이라는 409는 실패가 아니고, 401은 녹음 상태 조회가 로그인 화면으로 보내요
  const isAudioSaveCheckFailed =
    isAwaitingAudioSave &&
    audioSaveError !== null &&
    !isHttpError(audioSaveError, HTTP_ERROR_TYPE.conflict) &&
    !isUnauthorizedError(audioSaveError);
  const {
    mutate: requestRetry,
    isPending: isRetryPending,
    data: retryResults = [],
    reset: clearRetryResults,
  } = useRetryDocumentGenerationJobsMutation();
  const { retryTranscription, isTranscriptionRetrying } = useTranscriptionRetry(
    {
      workspaceId,
      recordingId,
      transcriptionAttemptId: recording?.transcriptionAttemptId,
      refetchRecording: refetch,
    },
  );
  // 문서 다시 시도의 409 뒤에 거절 기록을 지울지 정하려고 녹음 상태를 다시 조회하는 중인지.
  // 그동안 버튼이 풀리면 그사이 시작한 새 다시 시도의 결과까지 아래 `clearRetryResults`가 지울 수 있어, 조회가 끝날 때까지 다시 시도 중으로 둬요
  const [isCheckingRejection, setIsCheckingRejection] = useState(false);
  const { show } = useToast();
  const { isUnauthorized } = useRedirectToLoginOnUnauthorized({ error });
  // 다시 시도에서 로그인이 풀려도 같은 방식으로 로그인 화면에 보내요
  const unauthorizedRetry = retryResults.find(({ error: retryError }) =>
    isUnauthorizedError(retryError),
  );
  useRedirectToLoginOnUnauthorized({ error: unauthorizedRetry?.error });

  const isLoadFailed =
    !isUnauthorized &&
    (isUnrecoverableLoadError(error) || (recording === undefined && isError));

  const getViewStatus = () => {
    if (isLoadFailed) return "loadFailed" as const;
    if (recording === undefined) return "loading" as const;

    if (audioSaveFailure !== undefined) {
      // 저장된 음성이 없는 실패는 문서로 만들 내용이 없는 것과 같은 결과예요
      return audioSaveFailure.failureCode === "AUDIO_NO_CONTENT"
        ? ("noContent" as const)
        : ("audioSaveFailed" as const);
    }

    const documentsStatus = getRecordingDocumentsStatus(recording);

    // 서버가 버린 녹음이나 타입에 없는 값은 보여 줄 화면이 없어 불러오지 못한 것으로 둬요
    return documentsStatus === "unsupported"
      ? ("loadFailed" as const)
      : documentsStatus;
  };

  const viewStatus = getViewStatus();

  // 저장 실패는 업로드 실패와 같은 정책으로 안내 없이 콘솔에만 남겨요. 홈으로 보내는 일은 `useRecordingDocumentsRedirect`가 맡아요
  const audioSaveStatus = audioSaveFailure?.audioSaveStatus;
  const audioSaveFailureCode = audioSaveFailure?.failureCode;
  useEffect(() => {
    if (viewStatus !== "audioSaveFailed") return;

    console.error("녹음 오디오를 저장하지 못해 홈으로 보내요", {
      audioSaveStatus,
      failureCode: audioSaveFailureCode,
    });
  }, [audioSaveFailureCode, audioSaveStatus, viewStatus]);

  // 문서 만들기가 실패로 끝났을 때 다시 시도할 수 있게 남아 있는 작업의 id예요.
  // 전사 실패는 문서 생성 결과가 없어 비어 있어요
  const failedJobIds =
    viewStatus === "failed"
      ? (recording?.documentGeneration?.failedJobIds ?? [])
      : [];
  // 마지막 다시 시도에서 서버가 거절한 작업의 id예요
  const rejectedJobIds = retryResults
    .filter(({ error: retryError }) => isRetryRejectedError(retryError))
    .map(({ jobId }) => jobId);
  // 거절되지 않은 실패 작업이 하나라도 있어야 다시 시도할 수 있어요.
  // 주소가 다른 녹음으로 바뀌어도 앞 녹음의 거절 기록은 남아 있지만, 작업 id가 달라 지금 녹음의 작업을 가리지 않아요
  const canRetry = failedJobIds.some(
    (jobId) => !rejectedJobIds.includes(jobId),
  );

  // 화면을 새로고침하지 않고 녹음 상태만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const retryLoad = () => {
    refetch();
  };

  // 녹음 상태는 정리 중으로 받았으니 저장 상태만 다시 조회해요
  const retryAudioSaveCheck = () => {
    refetchAudioSave();
  };

  // 전사가 실패한 녹음이에요. 문서 만들기가 접수되지 않아 실패 작업이 없고, 실패 코드와 관계없이 전사부터 다시 시도할 수 있어요
  const isTranscriptionFailed =
    viewStatus === "failed" && recording?.transcriptionStatus === "FAILED";

  const retryDocumentGeneration = canRetry
    ? () =>
        requestRetry(
          { workspaceId, jobIds: failedJobIds },
          {
            onSuccess: async (results) => {
              const errors = results.map(({ error: retryError }) => retryError);
              const isNoneAccepted = results.every(
                ({ isAccepted }) => !isAccepted,
              );
              const hasRetryableFailure = errors.some(
                (retryError) =>
                  retryError !== null && !isRetryRejectedError(retryError),
              );
              if (
                isNoneAccepted &&
                hasRetryableFailure &&
                !errors.some(isUnauthorizedError)
              ) {
                show(TOAST_MESSAGES.RETRY_REQUEST_FAILED);
              }

              const conflictedJobIds = results
                .filter(({ error: retryError }) =>
                  isHttpError(retryError, HTTP_ERROR_TYPE.conflict),
                )
                .map(({ jobId }) => jobId);

              if (conflictedJobIds.length === 0) return;

              setIsCheckingRejection(true);
              const { data: latestRecording } = await refetch();
              setIsCheckingRejection(false);

              if (latestRecording === undefined) return;

              const latestFailedJobIds =
                latestRecording.documentGeneration?.failedJobIds ?? [];
              const isAllStillFailed = conflictedJobIds.every((jobId) =>
                latestFailedJobIds.includes(jobId),
              );

              // 409를 받은 작업이 모두 여전히 실패 상태면 다시 시도할 수 있는 횟수를 다 쓴 작업이라 거절 기록을 남겨요.
              // 하나라도 실패 상태가 아니면(다른 곳에서 먼저 다시 시도함) 거절 기록을 지워요. 남겨 두면 그 작업이 또 실패했을 때 「다시 시도」가 숨겨져요.
              // 기록은 작업별로 지울 수 없어 모두 지워요. 횟수를 다 쓴 작업의 기록도 함께 지워지지만, 다시 누르면 또 거절되어 기록이 다시 남아요
              if (!isAllStillFailed) clearRetryResults();
            },
          },
        )
    : undefined;

  return {
    viewStatus,
    isTranscriptionFailed,
    isAudioSaveCheckFailed,
    retryLoad,
    retryAudioSaveCheck,
    retryGeneration: isTranscriptionFailed
      ? retryTranscription
      : retryDocumentGeneration,
    isRetrying:
      isRetryPending || isCheckingRejection || isTranscriptionRetrying,
  };
};
