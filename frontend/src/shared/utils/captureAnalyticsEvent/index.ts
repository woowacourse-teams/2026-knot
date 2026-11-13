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
    result:
      | "started"
      | "microphone_unavailable"
      | "refused"
      | "network_failed"
      | "failed"
      | "uploading";
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
    /**
     * 끝낼 때의 회의 소리 함께 녹음 상태. 함께 녹음 중(`on`) · 공유가 멈춤(`stopped`) · 소리 없이 공유함(`no_audio`) ·
     * 마이크만(`off`) · 회의 소리를 받을 수 없는 브라우저(`unsupported`)예요
     */
    meeting_audio: "on" | "stopped" | "no_audio" | "off" | "unsupported";
    /** 끝낼 때까지 녹음한 시간(초, 소수 첫째 자리까지). 일시정지한 동안은 세지 않아요 */
    duration_seconds: number;
    /** 끝낸 곳. 녹음 화면의 녹음 바(`recorder_bar`) · 독의 녹음 칩(`dock`) · 로그아웃 확인 창(`logout`) · 워크스페이스를 옮기기 전 확인 창(`workspace_move`)이에요 */
    surface: "recorder_bar" | "dock" | "logout" | "workspace_move";
    recording_id: number;
    workspace_id: number;
  };
  /**
   * 서버가 녹음을 끝냈어요. 사용자가 끝내지 않은 녹음이 끝난 경우라 녹음 손실을 볼 때 써요. 사용자가 끝낸 녹음은 `recording_ended`로 보내요.
   * 연결이 끊긴 채 30분이 지남(`connection_expired`) · 2시간에 닿음(`max_duration`) · 다른 곳에서 끝냈거나 사유를 모름(`ended_by_server`) ·
   * 녹음이 버려짐(`discarded`) · 녹음에 더는 접근할 수 없음(`unreachable`) · 이 탭이 녹음 제어권을 잃음(`control_lost`)이에요.
   * 새로고침한 사이 서버가 끝낸 녹음은 이 이벤트가 아니라 `recording_reconnect_result`의 `closed`로 남아요
   */
  recording_auto_ended: {
    reason:
      | "connection_expired"
      | "max_duration"
      | "ended_by_server"
      | "discarded"
      | "unreachable"
      | "control_lost";
    /** 음성을 서버로 넘겼는지. 넘기지 못했으면 그 녹음은 정리되지 않아요 */
    is_uploaded: boolean;
    audio_mode: RecordingAudioMode;
    recording_id: number;
    workspace_id: number;
  };
  /**
   * 녹음 중 서버와의 연결이 끊겼다가 그 끊김이 끝났어요. 끊길 때가 아니라 끝날 때 한 번 보내서, 끊긴 시간과 결과를 함께 봐요.
   * 끊김은 화면이 연결 끊김 안내를 띄우는 조건(생존 신호 실패 또는 조각 전송 밀림)과 같아요. 끊긴 채 탭을 닫으면 끊김이 끝나지 않아 보내지 않아요
   */
  recording_disconnected: {
    /** 연결이 돌아와 녹음이 이어짐(`restored`) · 녹음이 끝남(`recording_closed`). 안내가 떠 있는 채 사용자가 끝낸 녹음도 `recording_closed`예요 */
    outcome: "restored" | "recording_closed";
    /** 끊긴 동안의 시간(초, 소수 첫째 자리까지) */
    disconnected_seconds: number;
    recording_id: number;
    workspace_id: number;
  };
  /**
   * 새로고침 전 녹음을 이어 받으려 한 결과가 정해졌어요.
   * 이어 받음(`reconnected`) · 녹음이 이미 끝나 이어 갈 수 없음(`closed`) · 다른 탭이 이미 이어 받음(`taken_over`) · 요청이 실패해 다시 누를 수 있음(`failed`)이에요
   */
  recording_reconnect_result: {
    result: "reconnected" | "closed" | "taken_over" | "failed";
    /** 이어 받기를 시작한 곳. 녹음 바의 「이어서 녹음」(`resume_button`) 또는 끝내기 확인 창(`end_dialog`)이에요 */
    trigger: "resume_button" | "end_dialog";
    recording_id: number;
    workspace_id: number;
  };
  /** 다른 탭이나 다른 기기에서 하는 녹음을 독의 녹음 칩으로 끝낸 결과가 정해졌어요. 이 탭의 녹음을 끝낸 것은 `recording_ended`로 보내요 */
  recording_end_other_tab_result: {
    /** 끝냄(`ended`) · 이미 끝나 있었음(`already_ended`) · 실패(`failed`) */
    result: "ended" | "already_ended" | "failed";
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 중에 마이크가 끊겨 안내 창을 띄웠어요. 녹음을 시작하지 못한 것은 `recording_start_failed`로 보내요 */
  recording_microphone_lost: {
    /** 끊길 때까지 녹음한 시간(초, 소수 첫째 자리까지) */
    elapsed_seconds: number;
    recording_id: number;
    workspace_id: number;
  };
  /**
   * 「정리 중」을 본 뒤 결과가 정해졌어요. 정리 화면에서 결과를 보면 정리 화면이, 전사가 끝나 녹음 문서로 넘어간 뒤 결과를 보면 녹음 문서가 보내요.
   * 이미 결과가 난 녹음을 다시 열면 보내지 않지만, 저장된 음성 없음 · 저장 실패는 다시 열 때마다 보내서 recording_id로 중복을 빼요
   */
  recording_documents_result: {
    result: "completed" | "no_content" | "failed" | "audio_save_failed";
    /** 실패했을 때만 있어요. 전사 · 주제 분류 · 문서 만들기 중 어디에서 실패했는지예요 */
    failure_stage?: "transcription" | "topic_classification" | "document_generation";
    /** 완료했을 때만 있어요. 그 녹음에서 만들어진 문서 수예요 */
    document_count?: number;
    /** 결과를 본 화면. 정리 화면(`organizing_screen`) 또는 녹음 문서(`recording_document`)예요 */
    surface: "organizing_screen" | "recording_document";
    /** 이 화면에서 「정리 중」을 본 뒤 결과가 날 때까지 기다린 시간(초, 소수 첫째 자리까지) */
    waited_seconds: number;
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
  /**
   * 탐색 답변이 끝났어요. 사용자가 받기를 끊었으면 결과가 없어 보내지 않아요.
   * 끊긴 답변을 이어 받다가 서버가 그 답변을 더는 주지 않으면(4xx) `unavailable`이에요. 서버에서는 이미 끝난 답변(409)도 여기에 들어가요
   */
  search_answer_result: {
    result: "completed" | "stopped" | "failed" | "accept_failed" | "unavailable";
    /** 실패한 답변의 「다시 시도」로 보냈는지. #1141에서 그 버튼이 없어져 지금은 늘 `false`예요 */
    is_retry: boolean;
    /** 질문을 보낸 곳. 독의 입력창(`dock`) 또는 탐색 튜토리얼의 질문 종류 카드(`tutorial`)예요 */
    entry: "dock" | "tutorial";
    /** `failed`일 때만 있어요. 서버가 알려 준 실패 원인 코드예요. 예: `LLM_STREAM_FAILED` */
    failure_code?: string;
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
  /** 탐색 튜토리얼 1단계를 띄웠어요. 처음 뜬 것과 미룬 뒤 두 번째로 뜬 것, 탐색 사용법 보기로 다시 연 것을 함께 세요 */
  search_tutorial_shown: {
    /** 띄운 화면. 탐색 사용법 보기로 다시 열었으면 `replay`예요 */
    source:
      | "home"
      | "search"
      | "documents"
      | "document"
      | "recording_document"
      | "replay";
    /** 「다음에 볼게요」를 누른 뒤 두 번째로 뜬 것인지 */
    is_again: boolean;
    workspace_id: number;
  };
  /** 탐색 튜토리얼의 한 단계를 보여 줬어요. 1단계는 `search_tutorial_shown`과 함께 와요 */
  search_tutorial_step_viewed: {
    step: 1 | 2 | 3 | 4;
    workspace_id: number;
  };
  /**
   * 탐색 튜토리얼이 끝났어요.
   * - `completed`: 4단계에서 질문 종류 카드를 누르거나, 직접 보내거나, 끝내기를 눌렀어요
   * - `skipped`: 2~4단계에서 건너뛰기를 눌렀어요
   * - `snoozed`: 1단계에서 「다음에 볼게요」를 눌렀어요. 다음 날 이후 한 번 더 떠요
   * - `closed`: 두 번째로 뜬 1단계에서 닫기를 눌렀어요
   * - `left`: 끝내지 않고 새로고침하거나 탭을 닫거나 다른 화면으로 떠났어요
   */
  search_tutorial_ended: {
    result: "completed" | "skipped" | "snoozed" | "closed" | "left";
    /** 끝낸 단계 */
    step: 1 | 2 | 3 | 4;
    /** 끝낸 방법. `completed`일 때만 있어요. 질문 종류 카드(`question_kind`), 직접 보냄(`typed`), 끝내기 버튼(`finish_button`) */
    via?: "question_kind" | "typed" | "finish_button";
    /** 질문 종류 카드로 끝냈을 때 고른 종류 */
    question_kind?: "past_decision" | "undecided" | "next_action";
    workspace_id: number;
  };
  /**
   * 피드백 패널에서 보낸 피드백이 접수함에 저장됐어요. 저장에 실패하면 보내지 않아요(FEEDBACK-R7).
   * 이름은 PostHog 설문 응답 이벤트 이름을 그대로 써서 snake_case가 아니에요. 화면 경로는 PostHog가 자동으로 붙여요
   */
  "survey sent": {
    /** 고른 유형의 화면 문구. 예: `오류가 있어요` */
    feedback_type: string;
    /** 유형을 바꾼 점수(1~4). 화면에는 보이지 않아요. 매기는 법은 `shared/constants/feedback`에 있어요 */
    feedback_score: number;
    /** 적은 내용. 적었을 때만 있어요 */
    feedback_content?: string;
  };
  /** 답변을 기다리다 떠났던 그 대화 화면으로 같은 탭에서 돌아왔어요. 그사이 답변이 끊겼으면 보내지 않아요 */
  search_answer_returned: {
    /** 떠난 뒤 돌아올 때까지 걸린 시간(초, 소수 첫째 자리까지) */
    away_seconds: number;
    /** 돌아왔을 때 답변이 끝나 있었는지 */
    is_answer_ready: boolean;
  };
  /** 문서 본문 · 회의 전체 요약 · 원문을 복사했어요. 복사에 실패하면 보내지 않아요. 복사한 글은 넣지 않아요 */
  content_copied: {
    /** 무엇을 복사했는지. 주제별 문서 본문(`document`) · 녹음 문서의 회의 전체 요약(`meeting_summary`) · 원문 패널의 전사(`transcript`)예요 */
    content: "document" | "meeting_summary" | "transcript";
  } & AnalyticsScreenTarget;
  /** 원문 패널의 발화 목록을 스크롤했어요. 화면에 들어올 때마다 처음 한 번만 보내요. 같은 화면에서 패널을 닫았다 다시 열어도 다시 보내지 않아요 */
  transcript_panel_scrolled: AnalyticsScreenTarget;
  /** 녹음 문서를 열어 처리 상태가 정해졌어요. 화면에 들어올 때마다 한 번 보내요. 보는 동안 상태가 바뀌어도 다시 보내지 않아요 */
  recording_document_viewed: {
    processing_status:
      | "organizing"
      | "transcription_failed"
      | "topic_failed"
      | "document_failed"
      | "no_content"
      | "completed";
    /** 회의 전체 요약의 상태. 만드는 중(`drafting`) · 완료(`completed`) · 실패(`failed`) · 요약 기능이 생기기 전에 끝난 녹음(`none`)이에요 */
    meeting_summary_status: "drafting" | "completed" | "failed" | "none";
    /** 완료 · 문서 생성 실패일 때만 있어요. 「이 회의에서 나온 문서」의 문서 수예요. 목록을 받지 못했으면 없어요 */
    document_count?: number;
    /** 원문(전사)이 이미 있어 정리가 끝나기 전에 원문을 먼저 볼 수 있었는지 */
    is_transcript_ready: boolean;
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 문서에서 녹음 음성이 처음 재생되기 시작했어요. 화면에 들어올 때마다 처음 한 번만 보내요. 재생을 누른 것은 자동 클릭으로 세요 */
  recording_audio_played: {
    /** 재생을 시작한 위치(초, 소수 첫째 자리까지) */
    position_seconds: number;
    /** 녹음 전체 길이(초, 소수 첫째 자리까지) */
    duration_seconds: number;
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 음성을 들은 뒤 녹음 문서를 떠났어요. 재생한 적이 없으면 보내지 않아요 */
  recording_audio_listened: {
    /** 실제로 재생된 시간(초, 소수 첫째 자리까지). 위치를 옮겨 건너뛴 구간은 세지 않아요 */
    listened_seconds: number;
    /** 녹음 전체 길이(초, 소수 첫째 자리까지) */
    duration_seconds: number;
    /** 재생하며 가장 멀리 간 위치(초, 소수 첫째 자리까지) */
    max_position_seconds: number;
    /** 위치를 옮긴 횟수. 막대를 끄는 동안의 이동은 한 번으로 세요 */
    seek_count: number;
    /** 끝까지 재생했는지 */
    reached_end: boolean;
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 문서에서 녹음 음성을 들을 수 없었어요. 화면에 들어올 때마다 까닭마다 한 번 보내요 */
  recording_audio_unavailable: {
    /** 음성 파일이 지워짐(`no_audio`) · 아직 합치는 중(`preparing`) · 재생 정보를 받지 못함(`load_failed`) · 음성을 불러오지 못함(`media_error`) */
    reason: "no_audio" | "preparing" | "load_failed" | "media_error";
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 문서 · 홈 녹음 기록 카드의 다시 시도에 서버가 응답했어요. 거절은 409 · 404, 오류는 5xx · 네트워크 문제예요. 정리 화면의 다시 시도는 `recording_documents_retry`로 보내요 */
  recording_retry_result: {
    /** 다시 시도를 누른 곳. 녹음 문서(`recording_document`) 또는 홈 녹음 기록 카드(`recording_history`)예요 */
    surface: "recording_document" | "recording_history";
    /** 무엇을 다시 시도했는지. 주제 분류 실패의 다시 시도는 문서 만들기 작업을 다시 접수해서 `document_generation`이에요 */
    stage: "transcription" | "document_generation" | "meeting_summary";
    result: "accepted" | "rejected" | "error";
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 화면의 회의 소리 연결 안내를 닫았거나 공유 결과가 정해졌어요 */
  meeting_audio_connect_result: {
    /** 연결됨(`connected`) · 소리 없이 공유함(`no_audio`) · 공유 창을 닫음(`share_cancelled`) · 안내에서 취소함(`dialog_cancelled`) */
    result: "connected" | "no_audio" | "share_cancelled" | "dialog_cancelled";
    /** 연결 안내에서 고른 회의를 연 곳. 브라우저(`browser_tab`) 또는 설치한 앱(`meeting_app`)이에요 */
    location: "browser_tab" | "meeting_app";
    /** 「다시 연결하기」로 연 것인지 */
    is_reconnect: boolean;
    recording_id: number;
    workspace_id: number;
  };
  /** 회의 소리를 함께 녹음하다 공유가 멈췄어요. 브라우저의 공유 중지를 눌렀거나 공유한 탭을 닫은 거예요. 녹음은 마이크로 이어져요 */
  meeting_audio_stopped: {
    recording_id: number;
    workspace_id: number;
  };
  /** 문서 보기의 「문서를 확인했어요」에 서버가 응답했어요. 누른 것은 자동 클릭으로 세요. 로그인이 풀린 401은 보내지 않아요 */
  document_confirmed: {
    /** 확인됨(`confirmed`) 또는 실패(`failed`). 확인 대상이 아니라는 409도 `failed`예요 */
    result: "confirmed" | "failed";
    /** `confirmed`일 때만 있어요. 이 확인으로 같은 녹음의 문서를 모두 확인했는지. 내 미확인 문서 목록을 받지 못해 알 수 없으면 없어요 */
    is_all_confirmed?: boolean;
    document_id: number;
    workspace_id: number;
  };
  /** 초대 링크나 참여 코드를 복사했어요. 복사에 실패하면 보내지 않아요. 복사한 링크 · 코드는 넣지 않아요 */
  invite_copied: {
    /** 무엇을 복사했는지. 초대 링크(`link`) 또는 참여 코드(`code`)예요 */
    target: "link" | "code";
    /** 복사한 곳. 홈의 팀원 초대 카드(`home_card`) 또는 워크스페이스를 만든 뒤의 초대 화면(`create_flow`)이에요 */
    surface: "home_card" | "create_flow";
    workspace_id: number;
  };
  /** 녹음 문서에서 화자 이름을 바꾼 결과가 정해졌어요. 바꾼 이름은 넣지 않아요 */
  speaker_renamed: {
    result: "saved" | "failed";
    /** 이름을 정한 방법. 워크스페이스 멤버를 고름(`member`) 또는 직접 적음(`typed`)이에요 */
    name_source: "member" | "typed";
    recording_id: number;
    workspace_id: number;
  };
  /** 녹음 화면에서 참가자 수를 저장한 결과가 정해졌어요 */
  speaker_count_saved: {
    result: "saved" | "failed";
    /** 참가자 수를 정하지 않고 자동으로 둔 것인지 */
    is_auto: boolean;
    /** 정한 참가자 수. 자동이면 없어요 */
    speaker_count?: number;
    recording_id: number;
    workspace_id: number;
  };
  /**
   * 랜딩의 한 장면을 처음 봤어요. 페이지를 열 때마다 장면마다 한 번만 보내요.
   * 오른쪽 장면 목록 · 로고 · Home/End로 여러 장면을 한 번에 건너뛰면 지나친 장면도 본 것으로 남아요
   */
  landing_scene_viewed: {
    scene:
      | "intro"
      | "problem"
      | "recording"
      | "documents"
      | "confirmation"
      | "search"
      | "recording_technology"
      | "search_technology"
      | "start";
  };
}

/** 문서 보기 · 녹음 문서처럼 문서를 읽는 화면과 그 화면의 id. 문서 보기는 `document_id`, 녹음 문서는 `recording_id`가 있어요 */
export type AnalyticsScreenTarget =
  | { screen: "document"; document_id: number; workspace_id: number }
  | {
      screen: "recording_document";
      recording_id: number;
      workspace_id: number;
    };

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
