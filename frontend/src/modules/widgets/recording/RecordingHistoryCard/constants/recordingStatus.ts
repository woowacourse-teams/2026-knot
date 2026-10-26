import type { CurrentRecordingStatus } from "@/shared/types/recording";

import type { RecordingProcessingStatus } from "../types/recordingHistory";

/** 맨 위 줄(STT-R24) 상태 줄의 이름과 그 아래 안내 문구. 일시정지는 시안에 안내가 없어요 */
export const CURRENT_RECORDING_COPY = {
  recording: {
    label: "녹음 중",
    hint: "다른 화면으로 이동해도 녹음은 계속돼요",
  },
  paused: {
    label: "일시정지",
    hint: null,
  },
} as const satisfies Record<
  Extract<CurrentRecordingStatus, "recording" | "paused">,
  { label: string; hint: string | null }
>;

/** 다른 탭의 녹음이면 이 탭에서 녹음 화면에 갈 수 없어 대신 보여 주는 안내(STT-R28) */
export const OTHER_TAB_RECORDING_HINT =
  "녹음 중인 탭에서 일시정지·끝내기를 할 수 있어요";

/** 완료가 아닌 행이 날짜와 길이 자리에 보여 주는 처리 상태 문구(STT-R51). 주제 분류 실패도 문서 생성 실패예요 */
export const PROCESSING_STATUS_LABEL = {
  PROCESSING: "문서 정리 중",
  TRANSCRIPTION_FAILED: "녹음을 글로 옮기지 못했어요",
  DOCUMENT_FAILED: "문서를 만들지 못했어요",
  NO_CONTENT: "변환된 음성 기록이 없어요.",
} as const satisfies Record<
  Exclude<RecordingProcessingStatus, "COMPLETED">,
  string
>;
