import useServerRecording from "@hooks/domain/recording/useServerRecording";

import { useThisTabRecording } from "./useThisTabRecording";

/**
 * 맨 위 줄(STT-R24)에 둘 내 녹음 중 · 일시정지 녹음.
 *
 * 이 탭에서 시작한 녹음이 이어지고 있으면 이 탭의 상태와 시간을, 없으면 현재 녹음 조회가 답한 녹음 중 · 일시정지 녹음(다른 탭)을 돌려줘요.
 * 이 탭의 녹음은 조회를 기다리지 않아요.
 * 끝난 녹음(문서 정리 중 · 실패)은 아래 목록이 보여 주므로 여기서는 빼요. 둘 다 없거나 조회가 실패하면 `null`이에요.
 * 녹음 화면에 갈 수 있는 건 녹음을 시작한 탭뿐이라, 다른 탭의 녹음에는 `onOpenRecording`이 없어요(STT-R28).
 */
export const useCurrentRecording = () => {
  const {
    status: thisTabStatus,
    elapsedTime: thisTabElapsedTime,
    handleOpenRecording,
  } = useThisTabRecording();
  const {
    status: serverStatus,
    elapsedTime: serverElapsedTime,
    isLoading,
  } = useServerRecording();

  if (thisTabStatus !== "idle") {
    return {
      currentRecording: {
        status: thisTabStatus,
        elapsedTime: thisTabElapsedTime,
        onOpenRecording: handleOpenRecording,
      },
      isLoading: false,
    };
  }

  if (serverStatus === "recording" || serverStatus === "paused") {
    return {
      currentRecording: {
        status: serverStatus,
        elapsedTime: serverElapsedTime,
      },
      isLoading: false,
    };
  }

  return { currentRecording: null, isLoading };
};
