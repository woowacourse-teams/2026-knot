import { getRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]";
import { getRecordingAudioSaveApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioSave";
import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";
import { markRecordingStart } from "@utils/recordingStartTrace";
import useCompleteRecordingAudioChunkMutation from "@api/mutations/useCompleteRecordingAudioChunkMutation";
import useCompleteRecordingAudioUploadMutation from "@api/mutations/useCompleteRecordingAudioUploadMutation";
import useEndRecordingMutation from "@api/mutations/useEndRecordingMutation";
import useIssueRecordingAudioUploadUrlMutation from "@api/mutations/useIssueRecordingAudioUploadUrlMutation";
import usePauseRecordingMutation from "@api/mutations/usePauseRecordingMutation";
import useReconnectRecordingMutation from "@api/mutations/useReconnectRecordingMutation";
import useReserveRecordingAudioChunkMutation from "@api/mutations/useReserveRecordingAudioChunkMutation";
import useResumeRecordingMutation from "@api/mutations/useResumeRecordingMutation";
import useSendRecordingHeartbeatMutation from "@api/mutations/useSendRecordingHeartbeatMutation";
import useStartRecordingMutation from "@api/mutations/useStartRecordingMutation";
import useUploadRecordingAudioChunkMutation from "@api/mutations/useUploadRecordingAudioChunkMutation";
import useUploadRecordingAudioMutation from "@api/mutations/useUploadRecordingAudioMutation";
import { recordingKeys } from "@api/queryKey/recording";
import { workspaceKeys } from "@api/queryKey/workspace";
import { RECORDING_AUDIO_MODE } from "@constants/recording";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useNavigateToRecordingDocuments from "@hooks/domain/recording/useNavigateToRecordingDocuments";
import useRecordingEndedAnalytics from "@hooks/domain/recording/useRecordingEndedAnalytics";
import useRecordingReconnectAnalytics from "@hooks/domain/recording/useRecordingReconnectAnalytics";
import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import { useToast } from "@provider/context/toastContext";
import { countMetric } from "@provider/sentry";
import {
  type DiscardRecordingParams,
  type RecordingSession,
  useRecordingStore,
} from "@store/recordingStore";
import {
  createBackoff,
  isTransientApiError,
  type PcmAudioSeal,
  type PcmChunkRequests,
} from "@store/recordingStore/pcmChunkUploadQueue";
import { UnsupportedPcmCollectorError } from "@store/recordingStore/pcmCollector";
import {
  deleteRecordingChunks,
  readRecordingChunks,
} from "@store/recordingStore/recordingChunkArchive";
import { useQueryClient } from "@tanstack/react-query";
import { createRepeatedFailureReporter } from "@utils/createRepeatedFailureReporter";
import { isClosedRecordingError } from "@utils/isClosedRecordingError";
import { isRetryableAudioUploadError } from "@utils/isRetryableAudioUploadError";
import { isUnreachableRecordingError } from "@utils/isUnreachableRecordingError";
import { logRequestError } from "@utils/logRequestError";
import { trackRecordingAutoEnded } from "@utils/trackRecordingAutoEnded";
import {
  clearRecordingStartProof,
  completeRecordingReconnect,
  getRecordingControlProof,
  getRecordingResumeProof,
  getRecordingStartProof,
  prepareRecordingReconnect,
  saveRecordingResumeProof,
} from "@utils/recordingControlProof";
import {
  forgetUnfinishedRecording,
  getUnfinishedRecording,
  rememberUnfinishedRecording,
} from "@utils/unfinishedRecording";
import { useCallback, useRef } from "react";
import useEndRecordingDialog from "../useEndRecordingDialog";

import type { RecordingEndCause } from "@/shared/types/recording";

/** 최종 오디오 업로드가 일시 실패했을 때 다시 시도하는 최대 횟수(첫 시도 제외) */
const AUDIO_UPLOAD_MAX_RETRIES = 3;

/** 종료 뒤 서버가 PCM 조각을 받아 주는 시간. 종료 응답이 시한을 주지 않으면 종료 시각에 더해요 */
const CHUNK_ACCEPT_WINDOW_MS = 120 * 1000;

/** 종료 봉인이 거절된 서버 오류 코드. 봉인 없이 한 번 더 끝내 예약한 조각을 보존해요 */
const SEAL_REJECTED_CODES = [
  "INVALID_AUDIO_SEAL",
  "AUDIO_SEAL_CONFLICT",
  "AUDIO_MODE_CONFLICT",
];

/** 생존 신호는 10초마다 보내 실패도 거듭되므로, 녹음마다 연속 실패의 첫 번째만 보고해요. 여러 화면이 같은 기록을 써요 */
const heartbeatFailures = createRepeatedFailureReporter({
  feature: "recording",
  action: "녹음 생존 신호",
});

/** 서버가 조각을 받아 주는 시한(ISO 8601). 종료 시각을 모르면 지금부터 재요 */
const acceptUntilFrom = (endedAt: string | null) =>
  new Date(
    (endedAt ? new Date(endedAt).getTime() : Date.now()) +
      CHUNK_ACCEPT_WINDOW_MS,
  ).toISOString();

interface CountRecordingEndParams {
  session: RecordingSession;
  reason: RecordingEndCause;
  /** 오디오를 서버로 넘겼는지. PCM은 종료가 받아들여져 남은 조각을 올리기 시작했으면 `true`예요 */
  isUploaded: boolean;
}

/**
 * 녹음이 끝난 이유를 Sentry 지표로 세요. 녹음이 끝나는 경로마다 한 번만 불러요(ADR 915).
 * 서버가 끝낸 녹음은 사용 기록(`recording_auto_ended`)으로도 보내요. 한 번만 부르니 같은 녹음을 두 번 보내지 않아요
 */
const countRecordingEnd = ({
  session,
  reason,
  isUploaded,
}: CountRecordingEndParams) => {
  countMetric({
    name: "recording.end",
    attributes: { reason, uploaded: isUploaded, audio_mode: session.audioMode },
  });
  trackRecordingAutoEnded({ ...session, reason, isUploaded });
};

/** 생존 신호가 알려 준 서버의 종료 사유를 지표의 끝난 이유로 바꿔요. 다른 탭에서 끝냈거나 사유가 없으면 `endedByServer`예요 */
const toServerEndCause = (endReason: string | null) => {
  if (endReason === "CONNECTION_EXPIRED") return "connectionExpired";
  if (endReason === "MAX_DURATION") return "maxDuration";

  return "endedByServer";
};

const wait = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * PCM 녹음의 일시정지·이어서 녹음을 순서대로 처리하는 사슬. 봉인과 서버 응답을 기다리는 동안 연타가 겹치지 않게 해요.
 * 훅을 쓰는 화면(독·녹음 바)이 여럿이라 모듈에 둬요
 */
let pcmControlChain: Promise<unknown> = Promise.resolve();

// Babel 8은 .ts에서도 JSX를 켜서 `<T>(…) =>`를 태그로 읽으니 function 선언으로 둬요
function runInOrder<T>(task: () => Promise<T>) {
  const result = pcmControlChain.then(task);
  // 앞 작업이 실패해도 다음 작업은 이어 가요
  pcmControlChain = result.catch(() => undefined);

  return result;
}

const isPcmSession = () =>
  useRecordingStore.getState().session?.audioMode === "PCM16_CHUNKS_V1";

interface HandleControlErrorParams {
  action: string;
  error: unknown;
  workspaceId: number;
}

interface UploadRecordedAudioParams {
  workspaceId: number;
  recordingId: number;
  audio: Blob;
}

type ControlProof = NonNullable<ReturnType<typeof getRecordingControlProof>>;

interface CreateChunkRequestsParams extends ControlProof {
  workspaceId: number;
  recordingId: number;
}

interface LeaveReloadedRecordingParams {
  workspaceId: number;
  recordingId: number;
  /** 다른 탭이 이어 받아 그 탭이 올릴 조각이면 지우지 않아요 */
  isTakenOver?: boolean;
}

// TODO: 시작의 그 밖의 실패(`failed`)와 일시정지 실패는 안내 정책이 없어 콘솔에만 남겨요(#465)
/**
 * 녹음을 시작·일시정지·이어서 녹음·끝내며 서버 녹음 세션과 맞추는 도메인 훅.
 *
 * - 시작: 마이크 권한 → 시작 요청이 성공해야 브라우저 녹음을 시작해요. 시작 요청이 실패하면 마이크를 꺼요.
 *   서버가 409로 거절하거나, 남아 있던 증명의 녹음을 돌려주면(200) 이어받지 않고 거절로 돌려줘요.
 *   돌려준 녹음이 이미 끝났거나 버려졌으면 새 증명으로 한 번만 다시 보내요. 시작 중에 다시 불러도 겹쳐 보내지 않아요.
 *   저장 방식이 PCM이면 시작 요청에 `audioMode`를 싣고, 조각 예약·PUT·저장 확인 요청에 세션과 최초 탭 증명을 묶어 저장소에 넘겨요.
 *   16kHz 수집기를 만들 수 없는 브라우저이거나 서버가 조각 저장을 꺼 뒀으면(409 `AUDIO_MODE_DISABLED`) 안내 없이 WebM으로 시작해요.
 *   시작 요청이 응답을 받지 못하면(연결 끊김, timeout) `networkFailed`로 돌려줘 부른 쪽이 다시 시도를 안내해요(ERR-18).
 * - 일시정지·이어서 녹음(WebM): 브라우저 녹음을 먼저 바꾸고 서버에 알려요. 서버 요청이 실패해도 다시 보내지 않고
 *   사용자가 누른 대로 둬요.
 * - 일시정지·이어서 녹음(PCM): 일시정지는 남은 소리를 봉인해 큐에 넣은 뒤 서버에 알리고, 이어서 녹음은 서버 재개가
 *   성공한 뒤에 수집을 이어 가요. 연타는 앞 요청이 끝난 뒤 순서대로 처리해요.
 *   서버 재개가 실패해 일시정지로 남으면 오류 토스트로 알려요.
 * - 끝내기(WebM): 확인 창에서 [녹음 끝내기]를 고르면 종료 요청 → 업로드 URL 발급 → 오디오 PUT → 업로드 완료 확인 → 녹음 비우기 → 그 녹음의 문서 정리 화면.
 *   끝내는 동안에는 확인 창을 다시 띄우지 않아요. 종료가 실패하면 녹음을 그대로 두고 오류 토스트로 알려요(ERR-21).
 *   업로드가 일시 실패하면 URL 발급부터 3번까지 다시 시도하고, 그래도 실패하면 오디오를 버리고 주의 토스트와 함께 홈으로 가요.
 *   종료 요청이 이미 끝났거나 버려진 녹음으로 거절돼도 정리할 오디오가 없어 홈으로 가요.
 *   워크스페이스를 옮길 때처럼 부른 쪽이 따로 확인받고 갈 곳도 정하면 `endRecordingWithoutConfirm`으로 끝내요.
 * - 끝내기(PCM): 수집을 멈추고 마지막 조각을 봉인한 뒤, 예약 응답을 아직 받지 못한 조각을 `audioSeal`에 담아 종료 요청을 보내요.
 *   성공하면 응답의 시한을 큐에 주고 녹음을 비워 바로 정리 화면으로 가고, 남은 조각은 큐가 뒤에서 올려요.
 *   응답을 못 받았거나 5xx, 429면 같은 봉인으로 1, 2, 4초 뒤, 그 뒤 4초 간격으로 다시 보내고, 봉인이 거절되면 봉인 없이 한 번 더 보내요.
 *   끊겨서 쌓인 조각이 있으면 저장소가 먼저 올린 뒤 봉인해요. 조각은 종료 뒤 120초 안에만 받아 주기 때문이에요.
 *   그동안 30분이 지나 서버가 끝낸 녹음이면 토스트로 알리고 홈으로 가요(STT-R26).
 *   서버가 먼저 끝낸 녹음(409 `RECORDING_ALREADY_ENDED`)은 예약한 조각만 종료 뒤 120초까지 올리고, 그 밖의 거절은 조각도 버려요. 둘 다 홈으로 가요.
 *   큐에 조각이 남아 있는 동안은 새 녹음을 시작하지 않아요(`uploading`). 안내 화면은 #814에서 붙여요.
 * - 생존 신호: 응답의 서버 상태를 따라가요. 서버가 끝낸 녹음(`ENDED`)이면 종료 요청 없이 WebM은 여기까지의 오디오를 올리고,
 *   PCM은 남은 소리를 봉인하지 않고 버린 뒤 예약한 조각만 종료 뒤 120초까지 올려요.
 *   연결 끊김(`CONNECTION_EXPIRED`)으로 끝난 녹음이면 넘긴 뒤 주의 토스트로 알려요(STT-R26).
 *   PCM은 수신 창이 이미 닫혀 남은 조각을 지우고, 지운 조각이 있으면 일부 소리를 올리지 못했다고도 알려요.
 *   버렸거나(`DISCARDED`) 더는 볼 수 없는 녹음(403 `WORKSPACE_ACCESS_DENIED`, 404)이면 오디오와 조각을 버려요. 모두 홈으로 가요.
 *   이 탭의 제어가 거절되면(403 `RECORDING_CONTROL_DENIED`) 오디오와 조각을 버리고 주의 토스트와 함께 홈으로 가요.
 *   녹음을 비웠으니 다음 주기부터는 보내지 않아요.
 *   응답을 못 받았거나 5xx, 429면 녹음을 끊지 않고 연결이 끊겼다고만 표시해요(STT-R53). 신호가 다시 닿으면 표시를 지워요.
 *   실패는 다시 보내지 않고 부르는 쪽의 다음 주기에 맡겨요. 끝내는 중에는 보내지 않아요.
 * - 새로고침 뒤 이어 가기: 증명으로 녹음을 이어 받아 일시정지로 되살리고 보관한 조각부터 올린 뒤, 마이크를 받아 이어서 녹음해요(STT-R26).
 *   PCM은 서버가 확인한 다음 번호부터 이어 붙이고 새로고침 사이 구간은 채우지 않아요. WebM은 새로고침 전 소리를 잃어요.
 *   끝난 녹음이면 홈으로 가고, 다른 탭이 먼저 이어 받았으면(403) 알린 뒤 홈으로 가요.
 * - 일시정지나 이어서 녹음이 서버에서 이미 끝난 녹음(409 `RECORDING_ALREADY_ENDED`)이라고 거절되면 오디오를 버리지 않고
 *   생존 신호를 바로 한 번 보내 그 응답대로 처리해요. 이미 버려진 녹음(409)이면 오디오를 버리고 홈으로 가요.
 *
 * 녹음 탭 증명은 sessionStorage에 두고, 실패는 콘솔에 남겨요. 위에 적은 경우만 토스트나 모달로 안내해요.
 * 시작을 시도한 결과와 녹음이 끝난 이유는 오류가 아니어도 Sentry 지표(`recording.start`, `recording.end`)로 세요.
 * 사용자가 끝낸 녹음 · 서버가 끝낸 녹음 · 새로고침 뒤 이어 받은 결과는 사용 기록(PostHog)으로도 보내요.
 * 안내하는 이동은 모두 독이 있는 화면끼리라 `show`로 띄운 토스트가 이동 뒤에도 남아요.
 * 독의 마이크 끊김 알림처럼 효과 안에서 부르는 곳이 있어 동작의 참조를 고정해요.
 */
const useRecordingControl = () => {
  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();
  const { navigateToRecordingDocuments } = useNavigateToRecordingDocuments();
  //TODO: shared훅에서 mutate 제거하기
  const { mutateAsync: startRecordingSession } = useStartRecordingMutation();
  const { mutateAsync: pauseRecordingSession } = usePauseRecordingMutation();
  const { mutateAsync: resumeRecordingSession } = useResumeRecordingMutation();
  const { mutateAsync: endRecordingSession } = useEndRecordingMutation();
  const { mutateAsync: sendRecordingHeartbeat } =
    useSendRecordingHeartbeatMutation();
  const { mutateAsync: reconnectRecordingSession } =
    useReconnectRecordingMutation();
  const { mutateAsync: issueAudioUploadUrl } =
    useIssueRecordingAudioUploadUrlMutation();
  const { mutateAsync: uploadAudio } = useUploadRecordingAudioMutation();
  const { mutateAsync: completeAudioUpload } =
    useCompleteRecordingAudioUploadMutation();
  const { mutateAsync: reserveAudioChunk } =
    useReserveRecordingAudioChunkMutation();
  const { mutateAsync: uploadAudioChunk } =
    useUploadRecordingAudioChunkMutation();
  const { mutateAsync: completeAudioChunk } =
    useCompleteRecordingAudioChunkMutation();
  const { openEndRecordingDialog } = useEndRecordingDialog();
  const { show } = useToast();
  const queryClient = useQueryClient();
  const isStartingRef = useRef(false);

  /**
   * 녹음을 버리고 다음 녹음은 새 시작 요청으로 보내도록 증명을 지워요.
   * `acceptUntil`을 주면 PCM 큐는 그때까지 남은 조각을 올리고, `isTakenOver`면 보관본을 이어 받은 탭의 몫으로 남겨요
   */
  const clearRecording = useCallback(
    (workspaceId: number, options?: DiscardRecordingParams) => {
      useRecordingStore.getState().discardRecording(options);
      clearRecordingStartProof();
      // 녹음하는 동안 받아 둔 녹음 중 응답이 다른 탭의 녹음으로 보이지 않게, 비우고 새로 받아요
      void queryClient.resetQueries({
        queryKey: recordingKeys.current(workspaceId),
      });
    },
    [queryClient],
  );

  //TODO: shared훅에서 라우팅 제거하기
  /** 녹음을 비우고 홈으로 가요 */
  const closeRecording = useCallback(
    (workspaceId: number, options?: DiscardRecordingParams) => {
      clearRecording(workspaceId, options);
      navigateToWorkspaceHome({
        workspaceId: String(workspaceId),
        replace: true,
      });
    },
    [clearRecording, navigateToWorkspaceHome],
  );

  /**
   * 일시 실패면 URL 발급부터 최대 `AUDIO_UPLOAD_MAX_RETRIES`번 다시 시도해요. 최종 실패는 콘솔에 남기고 주의 토스트로 알려요.
   * 업로드 완료를 확인받았으면 `true`를 돌려줘요
   */
  const uploadRecordedAudio = useCallback(
    async ({ workspaceId, recordingId, audio }: UploadRecordedAudioParams) => {
      for (let retryCount = 0; ; retryCount += 1) {
        try {
          // 만료됐거나 PUT이 깨진 URL을 다시 쓰지 않도록 매번 새로 받아요. 쓰지 않은 예약이면 서버가 같은 uploadId를 줘요
          const { uploadId, uploadUrl } = await issueAudioUploadUrl({
            workspaceId,
            recordingId,
            contentType: audio.type,
            contentLength: audio.size,
          });
          await uploadAudio({ uploadUrl, audio });
          await completeAudioUpload({ workspaceId, recordingId, uploadId });

          return true;
        } catch (error) {
          if (
            retryCount < AUDIO_UPLOAD_MAX_RETRIES &&
            isRetryableAudioUploadError(error)
          ) {
            continue;
          }

          logRequestError({
            feature: "recording",
            action: "녹음 파일 업로드",
            error,
          });
          // 오디오를 버려 문서가 만들어지지 않아요. 홈으로 가는 이동은 같은 독 레이아웃 안이라 토스트가 남아요
          show(TOAST_MESSAGES.RECORDING_UPLOAD_FAILED);

          return false;
        }
      }
    },
    [completeAudioUpload, issueAudioUploadUrl, show, uploadAudio],
  );

  /**
   * 생존 신호를 보내고 응답의 서버 상태에 맞춰 녹음을 이어 가거나 닫아요.
   * 실패는 다시 보내지 않고 다음 신호를 부르는 쪽의 주기에 맡겨요. 콘솔에는 실패마다, Sentry에는 연속 실패의 첫 번째만 남겨요
   */
  const sendHeartbeat = useCallback(async () => {
    const { session, isEnding } = useRecordingStore.getState();
    const proof = getRecordingControlProof();
    if (!session || !proof || isEnding) return;

    // 응답을 기다리는 사이 끝내기 시작했거나 닫은 녹음이면, 늦은 응답으로 지금 화면을 바꾸지 않아요
    const isLateResponse = () => {
      const current = useRecordingStore.getState();

      return (
        current.isEnding || current.session?.recordingId !== session.recordingId
      );
    };

    try {
      const { status, elapsedMillis, endedAt, endReason, connectionStatus } =
        await sendRecordingHeartbeat({
          ...session,
          ...proof,
        });
      heartbeatFailures.reset();
      if (isLateResponse()) return;

      useRecordingStore
        .getState()
        .setHeartbeatFailing(connectionStatus === "DISCONNECTED");

      const isConnectionExpired = endReason === "CONNECTION_EXPIRED";

      if (status === "ENDED" && session.audioMode === "PCM16_CHUNKS_V1") {
        const unsentChunkCount = useRecordingStore
          .getState()
          .getUnsentChunkCount();
        // 서버가 먼저 끝낸 녹음은 봉인할 수 없어 남은 소리를 버리고, 예약한 조각만 시한 안에 올려요.
        // 연결 만료는 끝난 시각이 30분 전이라 수신 창이 이미 닫혀, 올리지 않고 지워요
        closeRecording(session.workspaceId, {
          acceptUntil: isConnectionExpired
            ? undefined
            : acceptUntilFrom(endedAt),
        });
        countRecordingEnd({
          session,
          reason: toServerEndCause(endReason),
          isUploaded: true,
        });
        if (isConnectionExpired) {
          show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST);
          if (unsentChunkCount > 0) {
            show(TOAST_MESSAGES.RECORDING_CHUNKS_NOT_UPLOADED);
          }
        }
      } else if (status === "ENDED") {
        // 서버가 이미 끝낸 녹음이라 종료 요청 없이 여기까지의 오디오를 올려요
        const audio = await useRecordingStore.getState().stopRecording();
        const isUploaded = await uploadRecordedAudio({ ...session, audio });
        closeRecording(session.workspaceId);
        countRecordingEnd({
          session,
          reason: toServerEndCause(endReason),
          isUploaded,
        });
        // 올리지 못했으면 문서로 정리하지 않으므로, 업로드 실패 안내만 남겨요
        if (isUploaded && isConnectionExpired) {
          show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST);
        }
      } else if (status === "DISCARDED") {
        closeRecording(session.workspaceId);
        countRecordingEnd({ session, reason: "discarded", isUploaded: false });
      } else {
        useRecordingStore.getState().syncElapsedTime(elapsedMillis);
      }
    } catch (error) {
      heartbeatFailures.report({ error, scope: session.recordingId });
      if (isLateResponse()) return;

      if (isTransientApiError(error)) {
        useRecordingStore.getState().setHeartbeatFailing(true);
      } else if (isUnreachableRecordingError(error)) {
        closeRecording(session.workspaceId);
        countRecordingEnd({
          session,
          reason: "unreachable",
          isUploaded: false,
        });
        // 내보내졌거나 없어진 워크스페이스인지 레이아웃의 진입 판정이 다시 확인해, 그렇다면 다른 곳으로 보내고 알려요(ERR-11)
        void queryClient.invalidateQueries({
          queryKey: workspaceKeys.detail(session.workspaceId),
        });
      } else if (
        isHttpError(error, HTTP_ERROR_TYPE.forbidden) &&
        error.code === "RECORDING_CONTROL_DENIED"
      ) {
        // 이 탭은 더 제어할 수 없어요. 오디오도 올릴 수 없어 버리고, 신호를 되풀이하지 않게 녹음을 닫아요.
        // 복제한 탭이 이어 받았을 수 있어 보관본은 지우지 않아요
        closeRecording(session.workspaceId, { isTakenOver: true });
        countRecordingEnd({
          session,
          reason: "controlLost",
          isUploaded: false,
        });
        show(TOAST_MESSAGES.RECORDING_CONTROL_LOST);
      }
    }
  }, [
    closeRecording,
    queryClient,
    sendRecordingHeartbeat,
    show,
    uploadRecordedAudio,
  ]);

  const handleControlError = useCallback(
    ({ action, error, workspaceId }: HandleControlErrorParams) => {
      logRequestError({ feature: "recording", action, error });

      // 서버가 끝낸 녹음은 오디오를 올려야 해서 버리지 않고, 생존 신호의 응답으로 마저 처리해요
      if (isHttpError(error) && error.code === "RECORDING_ALREADY_ENDED") {
        void sendHeartbeat();

        return;
      }

      if (!isClosedRecordingError(error)) return;

      // 서버가 끝낸 녹음은 위에서 생존 신호에 맡기므로, 여기서 닫는 건 서버가 버린 녹음이에요
      const { session } = useRecordingStore.getState();
      closeRecording(workspaceId);
      if (session) {
        countRecordingEnd({ session, reason: "discarded", isUploaded: false });
      }
    },
    [closeRecording, sendHeartbeat],
  );

  /** 조각 큐는 React 밖에서 돌아 뮤테이션을 직접 쓰지 못하므로, 세션과 녹음 탭 증명을 묶은 요청 함수를 만들어요 */
  const createChunkRequests = useCallback(
    ({
      workspaceId,
      recordingId,
      tabId,
      controlToken,
    }: CreateChunkRequestsParams): PcmChunkRequests => ({
      reserve: async (descriptor) => {
        try {
          return await reserveAudioChunk({
            workspaceId,
            recordingId,
            tabId,
            controlToken,
            ...descriptor,
          });
        } catch (error) {
          // 다른 탭·기기가 끝낸 녹음이에요. 다음 주기를 기다리지 않고 신호로 확인해 멈춰요
          if (isHttpError(error) && error.code === "RECORDING_ALREADY_ENDED") {
            void sendHeartbeat();
          }
          throw error;
        }
      },
      upload: uploadAudioChunk,
      complete: (sequence) =>
        completeAudioChunk({
          workspaceId,
          recordingId,
          tabId,
          controlToken,
          sequence,
        }),
    }),
    [completeAudioChunk, reserveAudioChunk, sendHeartbeat, uploadAudioChunk],
  );

  /** 마이크를 받아 서버에 녹음을 열어요. 결과는 `started`·`microphoneUnavailable`·`refused`·`networkFailed`·`failed`예요 */
  const connectAndStartRecording = useCallback(
    async (workspaceId: number) => {
      const store = useRecordingStore.getState();
      // WebM일 때는 시작 요청에 보내지 않아 켜기 전과 같은 요청이 나가요
      let audioMode =
        RECORDING_AUDIO_MODE === "PCM16_CHUNKS_V1"
          ? RECORDING_AUDIO_MODE
          : undefined;

      try {
        const isConnected = await store.connectMicrophone({
          audioMode: RECORDING_AUDIO_MODE,
        });
        if (!isConnected) return "microphoneUnavailable";
        markRecordingStart("mic-connected");
      } catch (error) {
        if (!(error instanceof UnsupportedPcmCollectorError)) {
          // 서버가 받는 형식으로 녹음할 수 없는 브라우저예요. 안내는 다른 실패와 함께 정해요(#465)
          logRequestError({ feature: "recording", action: "녹음 시작", error });
          useRecordingStore.getState().discardRecording();

          return "failed";
        }
        // 16kHz 수집기를 만들 수 없는 브라우저예요. 받아 둔 마이크로 WebM 녹음을 하고, 다음 시작은 다시 PCM부터 시도해요
        audioMode = undefined;
      }

      let isConflictRetried = false;
      const requestStart = async () => {
        for (;;) {
          try {
            return await startRecordingSession({
              workspaceId,
              ...getRecordingStartProof(),
              audioMode,
            });
          } catch (error) {
            if (
              audioMode &&
              isHttpError(error) &&
              error.code === "AUDIO_MODE_DISABLED"
            ) {
              // 서버가 조각 저장을 꺼 뒀어요. 수집기만 닫고(마이크는 둬요) 새 증명으로 WebM 시작을 요청해요
              audioMode = undefined;
              useRecordingStore.getState().closePcmCollector();
            } else if (
              !isConflictRetried &&
              isHttpError(error) &&
              error.code === "AUDIO_MODE_CONFLICT"
            ) {
              // 켜기 전에 WebM으로 만든 증명이 남아 있어요. 새 증명으로 한 번 더 보내요.
              // 옛 녹음이 살아 있으면 ACTIVE_RECORDING_ALREADY_EXISTS로 거절돼요
              isConflictRetried = true;
            } else {
              throw error;
            }
            clearRecordingStartProof();
          }
        }
      };

      try {
        let session = await requestStart();
        markRecordingStart("server-started");

        if (session.status === "ENDED" || session.status === "DISCARDED") {
          clearRecordingStartProof();
          session = await requestStart();
        } else if (!session.isCreated) {
          // 응답을 받지 못한 시작의 녹음이 아직 열려 있어요. 시작을 재시도해 이어받지 않고, 이 녹음은 다른 탭처럼 끝낼 수 있어요
          useRecordingStore.getState().discardRecording();

          return "refused";
        }

        const sessionIds = { workspaceId, recordingId: session.recordingId };
        const { tabId, controlToken } = getRecordingStartProof();
        useRecordingStore.getState().startRecording({
          ...sessionIds,
          chunkRequests: audioMode
            ? createChunkRequests({ ...sessionIds, tabId, controlToken })
            : undefined,
        });
        saveRecordingResumeProof({
          ...sessionIds,
          audioMode: audioMode ?? "FINAL_WEBM_V1",
        });
        // 기록은 한 칸이라, 덮어쓰기 전에 지난 녹음의 남은 조각을 지워요. 새 녹음이 열렸으니 지난 녹음은 끝났어요
        const previous = getUnfinishedRecording();
        if (previous && previous.recordingId !== session.recordingId) {
          void deleteRecordingChunks(previous.recordingId).catch(
            () => undefined,
          );
        }
        rememberUnfinishedRecording(sessionIds);

        return "started";
      } catch (error) {
        // 응답을 못 받았거나 거절됐어도 서버엔 열렸을 수 있어, 다음 시작이 같은 요청으로 가도록 증명은 남겨요
        logRequestError({ feature: "recording", action: "녹음 시작", error });
        useRecordingStore.getState().discardRecording();

        if (isHttpError(error, HTTP_ERROR_TYPE.conflict)) return "refused";
        // 응답을 받지 못했어요(연결 끊김, timeout). 남긴 증명으로 다시 시도할 수 있게 부른 쪽이 안내해요
        if (isHttpError(error, HTTP_ERROR_TYPE.network)) return "networkFailed";

        return "failed";
      }
    },
    [createChunkRequests, startRecordingSession],
  );

  /**
   * 녹음을 시작해요. 결과는 `started`·`microphoneUnavailable`·`refused`·`networkFailed`·`failed`·`ignored`·`uploading`이고,
   * 마이크를 받지 못했거나, 서버가 거절했거나, 시작 요청이 응답을 받지 못한 경우만 부른 쪽이 안내해요
   */
  const startRecording = useCallback(
    async (workspaceId: number) => {
      const { status, isUploadingChunks } = useRecordingStore.getState();
      if (status !== "idle") return "started";
      // 지난 녹음의 조각을 아직 올리는 중이면 새 녹음을 열지 않아요. 안내 화면은 #814에서 붙여요
      if (isUploadingChunks) return "uploading";
      // 같은 증명으로 시작이 겹치면 두 번째 응답(200 RECORDING)이 첫 녹음을 남은 녹음으로 오인해요
      if (isStartingRef.current) return "ignored";

      isStartingRef.current = true;
      try {
        const result = await connectAndStartRecording(workspaceId);
        countMetric({ name: "recording.start", attributes: { result } });

        return result;
      } finally {
        isStartingRef.current = false;
      }
    },
    [connectAndStartRecording],
  );

  const sendPause = useCallback(async () => {
    const { session, isEnding } = useRecordingStore.getState();
    const proof = getRecordingControlProof();
    if (!session || !proof || isEnding) return;

    try {
      await pauseRecordingSession({ ...session, ...proof });
    } catch (error) {
      handleControlError({
        action: "일시정지",
        error,
        workspaceId: session.workspaceId,
      });
    }
  }, [handleControlError, pauseRecordingSession]);

  /** 서버에 일시정지를 알려요. 마이크가 끊겨 브라우저가 먼저 멈춘 경우에도 불러요 */
  const syncPause = useCallback(
    () => (isPcmSession() ? runInOrder(sendPause) : sendPause()),
    [sendPause],
  );

  const pauseRecording = useCallback(async () => {
    const pause = async () => {
      if (useRecordingStore.getState().isEnding) return;

      // PCM은 남은 소리를 봉인해 큐에 넣은 뒤 서버에 알려요. 이미 올리던 조각은 큐가 계속 올려요
      await useRecordingStore.getState().pauseRecording();
      await sendPause();
    };

    await (isPcmSession() ? runInOrder(pause) : pause());
  }, [sendPause]);

  /** PCM 녹음을 이어 가요. 서버 재개가 성공한 뒤에 수집을 이어 가므로, 실패하면 일시정지로 남아요 */
  const resumePcmRecording = useCallback(async () => {
    const store = useRecordingStore.getState();
    if (store.isEnding || store.status !== "paused") {
      return store.status === "recording";
    }

    try {
      const isReady = await store.ensureMicrophone();
      if (!isReady) return false;
    } catch (error) {
      // 새 마이크로 수집기를 만들지 못했어요. 마이크를 받지 못한 것처럼 안내해 다시 시도하게 해요
      logRequestError({ feature: "recording", action: "이어서 녹음", error });

      return false;
    }

    const { session } = useRecordingStore.getState();
    const proof = getRecordingControlProof();
    if (session && proof) {
      try {
        await resumeRecordingSession({ ...session, ...proof });
      } catch (error) {
        handleControlError({
          action: "이어서 녹음",
          error,
          workspaceId: session.workspaceId,
        });
        // 서버가 끝냈거나 버린 녹음은 위에서 마저 처리하니, 일시정지로 남는 실패만 알려요
        if (!isClosedRecordingError(error)) {
          show(TOAST_MESSAGES.RECORDING_RESUME_FAILED);
        }

        return true;
      }
    }

    return useRecordingStore.getState().resumeRecording();
  }, [handleControlError, resumeRecordingSession, show]);

  /** 이어서 녹음해요. 마이크를 다시 받지 못하면 `false`를 돌려주고 서버에는 알리지 않아요 */
  const resumeRecording = useCallback(async () => {
    if (isPcmSession()) return runInOrder(resumePcmRecording);

    const store = useRecordingStore.getState();
    if (store.isEnding) return false;

    const isResumed = await store.resumeRecording();
    if (!isResumed) return false;

    const { session } = useRecordingStore.getState();
    const proof = getRecordingControlProof();
    if (!session || !proof) return true;

    try {
      await resumeRecordingSession({ ...session, ...proof });
    } catch (error) {
      handleControlError({
        action: "이어서 녹음",
        error,
        workspaceId: session.workspaceId,
      });
    }

    return true;
  }, [handleControlError, resumePcmRecording, resumeRecordingSession]);

  /** 새로고침 전 녹음을 더 이어 갈 수 없어 증명을 지우고 홈으로 가요 */
  const leaveReloadedRecording = useCallback(
    ({
      workspaceId,
      recordingId,
      isTakenOver = false,
    }: LeaveReloadedRecordingParams) => {
      closeRecording(workspaceId);
      if (isTakenOver) return;

      forgetUnfinishedRecording(recordingId);
      void deleteRecordingChunks(recordingId).catch(() => undefined);
    },
    [closeRecording],
  );

  /**
   * 새로고침 전 녹음을 이어 받아 마이크 없이 일시정지로 되살리고, 보관한 조각을 먼저 올려요.
   * 결과는 `reconnected`(이어 받음)·`closed`(녹음이 끝났거나 볼 수 없어 홈으로 감)·`takenOver`(다른 탭이 먼저 이어 받아 홈으로 감)·
   * `failed`(증명을 그대로 둬 다시 누를 수 있음)예요
   */
  const reconnectRecording = useCallback(async () => {
    const resumeProof = getRecordingResumeProof();
    const reconnectProof = prepareRecordingReconnect();
    if (!resumeProof || !reconnectProof) return "failed";

    const { workspaceId, recordingId, audioMode } = resumeProof;
    const sessionIds = { workspaceId, recordingId };

    try {
      const { status, elapsedMillis, endReason } =
        await reconnectRecordingSession({ ...sessionIds, ...reconnectProof });

      if (status === "ENDED" || status === "DISCARDED") {
        leaveReloadedRecording(sessionIds);
        if (endReason === "CONNECTION_EXPIRED") {
          show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST);
        }

        return "closed";
      }

      const isPcm = audioMode === "PCM16_CHUNKS_V1";
      const saved = isPcm ? await getRecordingAudioSaveApi(sessionIds) : null;
      const verifiedThroughSequence = saved?.verifiedThroughSequence ?? 0;
      const archivedChunks = isPcm
        ? await readRecordingChunks(recordingId).catch(() => [])
        : [];
      // 서버가 확인되지 않은 예약을 지워 번호가 바뀔 수 있어, 큐가 새 번호로 다시 보관하게 비워요
      await deleteRecordingChunks(recordingId).catch(() => undefined);

      completeRecordingReconnect();
      useRecordingStore.getState().restoreRecording({
        ...sessionIds,
        audioMode,
        elapsedMs: elapsedMillis,
        nextSequence: verifiedThroughSequence + 1,
        nextSampleOffset: saved?.verifiedThroughFrame ?? 0,
        archivedChunks: archivedChunks
          .filter(({ sequence }) => sequence > verifiedThroughSequence)
          .map(({ pcm }) => pcm),
        chunkRequests: isPcm
          ? createChunkRequests({
              ...sessionIds,
              tabId: reconnectProof.tabId,
              controlToken: reconnectProof.controlToken,
            })
          : undefined,
      });

      return "reconnected";
    } catch (error) {
      if (
        isHttpError(error, HTTP_ERROR_TYPE.forbidden) &&
        error.code === "RECORDING_CONTROL_DENIED"
      ) {
        leaveReloadedRecording({ ...sessionIds, isTakenOver: true });
        show(TOAST_MESSAGES.RECORDING_TAKEN_OVER);

        return "takenOver";
      }
      if (isClosedRecordingError(error) || isUnreachableRecordingError(error)) {
        leaveReloadedRecording(sessionIds);

        return "closed";
      }

      logRequestError({ feature: "recording", action: "녹음 이어 받기", error });
      show(TOAST_MESSAGES.RECORDING_RESUME_FAILED);

      return "failed";
    }
  }, [
    createChunkRequests,
    leaveReloadedRecording,
    reconnectRecordingSession,
    show,
  ]);

  // 「이어서 녹음」과 끝내기 확인 창이 모두 이 함수로 이어 받아요. 이어 받은 결과는 사용 기록으로도 보내요
  const { reconnectAndTrackRecording } =
    useRecordingReconnectAnalytics(reconnectRecording);

  /**
   * 새로고침 뒤 「이어서 녹음」을 눌렀을 때 이어 받고 마이크를 받아 이어 가요.
   * 마이크를 받지 못했을 때만 `false`예요. 서버는 녹음 중일 수 있어 일시정지를 알려 화면과 맞춰요
   */
  const resumeReloadedRecording = useCallback(async () => {
    if ((await reconnectAndTrackRecording("resume_button")) !== "reconnected") {
      return true;
    }

    const isResumed = await resumeRecording();
    if (!isResumed) void syncPause();

    return isResumed;
  }, [reconnectAndTrackRecording, resumeRecording, syncPause]);

  /**
   * PCM 녹음을 봉인해 끝내요. 일시 실패는 같은 봉인으로 간격을 두고 다시 보내고, 봉인이 거절되면 봉인 없이 한 번 더 보내요.
   * 성공하면 `uploaded`로 정리 화면으로 가고, 남은 조각은 큐가 시한까지 뒤에서 올려요.
   * 서버가 먼저 끝낸 녹음은 예약한 조각만 올리고, 그 밖의 거절은 조각도 버려요. 둘 다 `notUploaded`예요
   */
  const endPcmRecording = useCallback(
    async (session: RecordingSession, proof: ControlProof) => {
      const { isHeartbeatFailing, isChunkTransferStalled } =
        useRecordingStore.getState();
      let audioSeal: PcmAudioSeal | undefined = await useRecordingStore
        .getState()
        .sealRecording();

      // 끊긴 채 끝내는 동안 30분이 지났으면 서버가 이미 끝냈어요. 종료 응답은 사유를 주지 않아 녹음을 조회해 알아내요
      if (isHeartbeatFailing || isChunkTransferStalled) {
        const recording = await getRecordingApi(session).catch(() => null);

        if (recording?.endReason === "CONNECTION_EXPIRED") {
          const unsentChunkCount = useRecordingStore
            .getState()
            .getUnsentChunkCount();
          clearRecording(session.workspaceId);
          show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST);
          if (unsentChunkCount > 0) {
            show(TOAST_MESSAGES.RECORDING_CHUNKS_NOT_UPLOADED);
          }

          return "notUploaded";
        }
      }

      const nextDelay = createBackoff();
      const endFailures = createRepeatedFailureReporter({
        feature: "recording",
        action: "녹음 종료",
      });

      for (;;) {
        try {
          const { endedAt, audioSave } = await endRecordingSession({
            ...session,
            ...proof,
            audioSeal,
          });
          clearRecording(session.workspaceId, {
            acceptUntil: audioSave?.acceptUntil ?? acceptUntilFrom(endedAt),
          });

          return "uploaded";
        } catch (error) {
          if (isTransientApiError(error)) {
            endFailures.report({ error });
            // 끝내는 중 생존 신호를 보내지 않아 30분이 넘으면 서버가 먼저 끝내고 409로 알려요
            await wait(nextDelay());
          } else if (
            audioSeal &&
            isHttpError(error) &&
            SEAL_REJECTED_CODES.includes(error.code ?? "")
          ) {
            endFailures.report({ error });
            audioSeal = undefined;
          } else {
            endFailures.report({ error, isGivingUp: true });
            const isEndedByServer =
              isHttpError(error) && error.code === "RECORDING_ALREADY_ENDED";
            clearRecording(session.workspaceId, {
              acceptUntil: isEndedByServer ? acceptUntilFrom(null) : undefined,
            });

            return "notUploaded";
          }
        }
      }
    },
    [clearRecording, endRecordingSession, show],
  );

  /**
   * 확인 창 없이 녹음을 끝내 올린 뒤 비워요. 이동은 부른 쪽이 정해요.
   * 결과는 `uploaded`(업로드 완료를 확인받음. PCM은 봉인해 끝냄)·`notUploaded`(끝냈지만 오디오를 올리지 못함)·`notEnded`예요.
   * `notEnded`는 이미 끝내는 중이거나 종료 요청이 실패해 녹음을 이어 가는 경우예요
   */
  const endAndUploadRecording = useCallback(async () => {
    const { session, isEnding, beginEnding } = useRecordingStore.getState();
    const proof = getRecordingControlProof();
    if (!session || !proof || isEnding) return "notEnded";

    // 응답을 기다리는 동안 다시 눌러도 종료 요청이 겹치지 않게 먼저 표시해요
    beginEnding();

    if (session.audioMode === "PCM16_CHUNKS_V1") {
      return endPcmRecording(session, proof);
    }

    try {
      await endRecordingSession({ ...session, ...proof });
    } catch (error) {
      logRequestError({ feature: "recording", action: "녹음 종료", error });

      // 서버에서 이미 끝났거나 버려진 녹음이면 올릴 곳이 없어 오디오를 버리고 끝낸 것으로 봐요
      if (isClosedRecordingError(error)) {
        clearRecording(session.workspaceId);

        return "notUploaded";
      }

      useRecordingStore.getState().cancelEnding();
      show(TOAST_MESSAGES.RECORDING_END_FAILED);

      return "notEnded";
    }

    const audio = await useRecordingStore.getState().stopRecording();
    const isUploaded = await uploadRecordedAudio({ ...session, audio });
    clearRecording(session.workspaceId);

    return isUploaded ? "uploaded" : "notUploaded";
  }, [
    clearRecording,
    endPcmRecording,
    endRecordingSession,
    show,
    uploadRecordedAudio,
  ]);

  /** 사용자가 끝낸 결과를 Sentry 지표로도 세요. 끝내지 못했으면(`notEnded`) 녹음이 이어져 세지 않아요 */
  const endAndCountRecording = useCallback(async () => {
    const { session } = useRecordingStore.getState();
    const result = await endAndUploadRecording();
    if (session && result !== "notEnded") {
      countRecordingEnd({
        session,
        reason: "userEnded",
        isUploaded: result === "uploaded",
      });
    }

    return result;
  }, [endAndUploadRecording]);

  // 확인 창과 워크스페이스 옮기기가 모두 이 함수로 끝내요. 끝낸 결과는 사용 기록으로도 보내요
  const { endAndTrackRecording: endRecordingWithoutConfirm } =
    useRecordingEndedAnalytics(endAndCountRecording);

  const endRecording = useCallback(() => {
    // 끝내는 동안 다시 눌러도 이미 끝내는 중이라 확인 창을 또 띄우지 않아요
    if (useRecordingStore.getState().isEnding) return;

    openEndRecordingDialog({
      onEnd: async () => {
        // 새로고침 뒤 이어 받기 전이면 보관한 소리를 올릴 수 있게 먼저 이어 받아요
        if (
          useRecordingStore.getState().status === "idle" &&
          (await reconnectAndTrackRecording("end_dialog")) !== "reconnected"
        ) {
          return;
        }

        const { session } = useRecordingStore.getState();
        if (!session) return;

        const result = await endRecordingWithoutConfirm();
        if (result === "notEnded") return;

        const workspaceId = String(session.workspaceId);
        // 올린 오디오가 있어야 문서가 만들어지므로, 올리지 못한 녹음은 정리 화면 대신 홈으로 보내요
        if (result === "uploaded") {
          navigateToRecordingDocuments({
            workspaceId,
            recordingId: session.recordingId,
            replace: true,
          });
        } else {
          navigateToWorkspaceHome({ workspaceId, replace: true });
        }
      },
    });
  }, [
    endRecordingWithoutConfirm,
    navigateToRecordingDocuments,
    navigateToWorkspaceHome,
    openEndRecordingDialog,
    reconnectAndTrackRecording,
  ]);

  return {
    startRecording,
    pauseRecording,
    syncPause,
    resumeRecording,
    resumeReloadedRecording,
    sendHeartbeat,
    endRecording,
    endRecordingWithoutConfirm,
  };
};

export default useRecordingControl;
