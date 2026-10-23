import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { readMicrophonePermission } from "@utils/readMicrophonePermission";

import type { MicrophonePermission } from "@/shared/types/recording";

interface TrackRecordingStartFailedParams {
  /** 시작하지 못한 결과. `useRecordingControl`의 `startRecording`이 돌려주는 값 중 안내 창을 띄우는 값이에요 */
  result: "microphoneUnavailable" | "refused" | "networkFailed";
  /** 마이크를 받지 못한 오류 이름(`NotAllowedError` 등). 마이크를 받았으면 `null`이에요 */
  errorName: string | null;
  /** 녹음을 누를 때 읽기 시작한 마이크 권한 상태. 시작을 기다리게 하지 않으려고 다 읽기 전에 넘겨요 */
  permissionBefore: Promise<MicrophonePermission>;
  /** 어디서 녹음을 눌렀는지. 독의 마이크 또는 녹음 바의 「이어서 녹음」이에요 */
  surface: "dock" | "recorder_bar";
  workspaceId: number;
}

const REASON_BY_RESULT = {
  microphoneUnavailable: "microphone_unavailable",
  refused: "refused",
  networkFailed: "network_failed",
} as const;

/** 이 탭에서 시작하지 못한 횟수. 새로고침하면 처음부터 세요 */
let failedAttempts = 0;

/**
 * @description 녹음을 시작하지 못한 이유를 사용 기록(`recording_start_failed`)으로 보내요
 * @param params - 시작하지 못한 결과, 마이크 오류 이름, 누를 때 읽기 시작한 권한 상태, 누른 곳, 워크스페이스 id
 * @example
 * void trackRecordingStartFailed({ result: "microphoneUnavailable", errorName: "NotAllowedError", ... });
 *
 * 부를 때마다 이 탭에서 몇 번째 실패인지를 하나씩 늘리고, 실패한 뒤의 권한 상태를 읽어 앞뒤 권한 상태를 함께 보내요.
 * 오류 이름이 없으면(서버 거절 · 네트워크 문제) `error_name`을 빼고 보내요.
 * 권한 상태를 다 읽은 뒤에 보내므로 부른 쪽은 기다리지 않아도 돼요.
 */
export const trackRecordingStartFailed = async ({
  result,
  errorName,
  permissionBefore,
  surface,
  workspaceId,
}: TrackRecordingStartFailedParams) => {
  failedAttempts += 1;
  const attempt = failedAttempts;
  const permissionAfter = readMicrophonePermission();

  try {
    captureAnalyticsEvent({
      event: "recording_start_failed",
      properties: {
        reason: REASON_BY_RESULT[result],
        ...(errorName ? { error_name: errorName } : {}),
        permission_before: await permissionBefore,
        permission_after: await permissionAfter,
        attempt,
        surface,
        workspace_id: workspaceId,
      },
    });
  } catch {
    // 사용 기록을 보내지 못해도 녹음 안내에는 영향이 없어 삼켜요
  }
};
