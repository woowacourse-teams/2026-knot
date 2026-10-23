import type { CurrentRecordingStatus } from "@/shared/types/recording";

/** 상태 줄의 이름과 그 아래 안내 문구 */
export const RECORDING_STATUS_COPY = {
  recording: {
    label: "녹음 중",
    hint: "다른 화면으로 이동해도 녹음은 계속돼요",
  },
  paused: {
    label: "일시정지",
    hint: "녹음 화면에서 이어서 녹음할 수 있어요",
  },
  organizing: {
    label: "문서 정리 중",
    hint: "끝나면 확인이 필요한 문서에 올라와요",
  },
  // 두 실패의 문구는 기획 STT-R24 상태 표를 따라요. 보관 기간은 서버 정책(오디오 30일 · 실패 작업 7일)이에요
  transcriptionFailed: {
    label: "녹음을 글로 옮기지 못했어요",
    hint: "녹음은 30일 동안 보관해요",
  },
  documentFailed: {
    label: "문서를 만들지 못했어요",
    hint: "녹음과 원문은 7일 동안 보관해요",
  },
} as const satisfies Record<
  CurrentRecordingStatus,
  { label: string; hint: string }
>;
