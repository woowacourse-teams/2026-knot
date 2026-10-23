import posthog from "posthog-js";

import type {
  MicrophonePermission,
  RecordingAudioMode,
} from "@/shared/types/recording";

/**
 * PostHog로 보내는 이벤트와 그 속성이에요. 클릭 · 페이지 이동은 PostHog가 자동으로 기록하므로, 클릭으로 셀 수 없는 결과만 여기에 둬요.
 *
 * 이름은 PostHog 관례대로 snake_case로 쓰고, 한 번 정하면 바꾸지 않아요. 바꾸면 PostHog에서 이전 기록과 다른 이벤트로 갈라져요.
 * 읽기 쉬운 한글 이름은 PostHog의 액션 · 인사이트에 붙여요.
 * 문서 제목 · 내용은 넣지 않아요. 탐색 질문 글 · 답변 글은 MVP 단계에서 자세히 보려고 `search_answer_result`에만 넣어요.
 */
interface AnalyticsEventMap {
  /** 녹음을 시작하거나 이어 가지 못해 안내 창을 띄웠어요. 실패할 때마다 보내요 */
  recording_start_failed: {
    reason: "microphone_unavailable" | "refused" | "network_failed";
    /** 마이크를 받지 못했을 때만 있어요. getUserMedia의 오류 이름(`NotAllowedError` · `NotFoundError` · `NotReadableError` 등)이에요 */
    error_name?: string;
    /** 녹음을 누를 때의 마이크 권한 상태 */
    permission_before: MicrophonePermission;
    /** 시작하지 못한 뒤의 마이크 권한 상태 */
    permission_after: MicrophonePermission;
    /** 이 탭에서 새로고침 전까지 몇 번째로 시작하지 못했는지. 1부터 세요 */
    attempt: number;
    /** 독의 마이크(`dock`) 또는 녹음 바의 「이어서 녹음」(`recorder_bar`) */
    surface: "dock" | "recorder_bar";
    workspace_id: number;
  };
  /** 독의 녹음 버튼을 누른 뒤 녹음을 열었는지 결과가 정해졌어요. 누른 것은 자동 클릭으로 세고, 열렸는지와 기다린 시간은 여기서만 보여요 */
  recording_start_result: {
    result: "started" | "microphone_unavailable" | "refused" | "network_failed" | "failed" | "uploading";
    /** 누른 뒤 결과가 정해질 때까지 기다린 시간(초, 소수 첫째 자리까지). 마이크 권한 창을 보고 있던 시간도 들어가요 */
    wait_seconds: number;
    workspace_id: number;
  };
  /** 접힌 독이 질문 입력창으로 펼쳐졌어요. 버튼은 자동 클릭으로도 보이지만, 화면 아무 데서나 글자를 쳐서 펼친 것은 여기서만 보여요 */
  dock_chat_opened: {
    trigger: "button" | "typing";
  };
  /** 녹음을 끝내고 업로드 결과가 정해졌어요. 끝내지 못했으면 보내지 않아요 */
  recording_ended: {
    result: "uploaded" | "not_uploaded";
    audio_mode: RecordingAudioMode;
    recording_id: number;
    workspace_id: number;
  };
  /** 정리 화면에서 「정리 중」을 본 뒤 결과가 정해졌어요. 이미 결과가 난 녹음을 다시 열면 보내지 않지만, 저장된 음성 없음 · 저장 실패는 다시 열 때마다 보내서 recording_id로 중복을 빼요 */
  recording_documents_result: {
    result: "completed" | "no_content" | "failed" | "audio_save_failed";
    /** 실패했을 때만 있어요. 전사에서 실패했는지, 문서 만들기에서 실패했는지예요 */
    failure_stage?: "transcription" | "document_generation";
    /** 완료했을 때만 있어요. 그 녹음에서 만들어진 문서 수예요. 문서 보기의 스테퍼 전체 수와 같아요 */
    document_count?: number;
    recording_id: number;
    workspace_id: number;
  };
  /** 정리 화면의 다시 시도에 서버가 응답했어요. 거절은 409 · 404, 오류는 5xx · 네트워크 문제예요 */
  recording_documents_retry: {
    stage: "transcription" | "document_generation";
    result: "accepted" | "rejected" | "error";
    recording_id: number;
    workspace_id: number;
  };
  /** 탐색 답변이 끝났어요. 사용자가 받기를 끊었으면 결과가 없어 보내지 않아요 */
  search_answer_result: {
    result: "completed" | "stopped" | "failed" | "accept_failed";
    is_retry: boolean;
    is_first_question: boolean;
    /** 질문을 보낸 뒤 끝날 때까지 걸린 시간(초, 소수 첫째 자리까지) */
    answer_seconds: number;
    /** 보낸 질문 글 */
    question: string;
    /** 끝날 때까지 받은 답변 글. 되묻기도 이 글로 세요. 접수 전에 실패하면 빈 문자열이에요 */
    answer: string;
  };
  /** 탐색 답변이 끝나기 전(보내는 중 · 받는 중)에 그 대화 화면을 떠났어요. 다른 워크스페이스로 옮기거나 나간 것도 세요. 접수 전(대화 주소가 생기기 전)은 세지 않아요 */
  search_answer_left: {
    /** 질문을 보낸 뒤 떠날 때까지 기다린 시간(초, 소수 첫째 자리까지) */
    waited_seconds: number;
  };
  /** 답변을 기다리다 떠났던 그 대화 화면으로 같은 탭에서 돌아왔어요. 그사이 답변이 끊겼으면 보내지 않아요 */
  search_answer_returned: {
    /** 떠난 뒤 돌아올 때까지 걸린 시간(초, 소수 첫째 자리까지) */
    away_seconds: number;
    /** 돌아왔을 때 답변이 끝나 있었는지 */
    is_answer_ready: boolean;
  };
}

type AnalyticsEvent = keyof AnalyticsEventMap;

interface CaptureAnalyticsEventParams<Event extends AnalyticsEvent> {
  event: Event;
  properties: AnalyticsEventMap[Event];
}

/**
 * @description 핵심 흐름의 결과를 PostHog 이벤트로 보내요
 * @param event - 이벤트 이름. 예: `recording_ended`
 * @param properties - 그 이벤트의 속성. 이벤트마다 받는 값이 정해져 있어요
 * @example
 * captureAnalyticsEvent({
 *   event: "recording_ended",
 *   properties: { result: "uploaded", audio_mode, recording_id, workspace_id },
 * });
 *
 * `src/index.tsx`는 PostHog 키가 있을 때만 PostHog를 켜요. 키가 없는 로컬 · 테스트에서는 보내지 않아요.
 */
export const captureAnalyticsEvent = <Event extends AnalyticsEvent>({
  event,
  properties,
}: CaptureAnalyticsEventParams<Event>) => {
  if (!process.env.POSTHOG_KEY) return;

  posthog.capture(event, properties);
};
