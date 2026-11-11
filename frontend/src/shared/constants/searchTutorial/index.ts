/**
 * 탐색 튜토리얼의 예시 대화. 서버에 보내지 않고 화면에서만 보여 주는 목 데이터예요.
 *
 * 워크스페이스의 실제 회의와 관계없이 늘 같은 예시 회의를 써요. 대화 목록과 질문 기록에도 남지 않아요.
 * 근거 문서는 예시라 누를 수 없어요.
 */
export const SEARCH_TUTORIAL_MOCK_CONVERSATION = {
  question: "알림은 어떻게 보내기로 했어?",
  answer:
    "실패 알림은 실패 이유와 다음에 할 일을 한 문장에 함께 쓰고, 밤 10시부터 아침 8시까지 온 알림은 모아서 8시에 보내기로 했어요. 알림 채널은 아직 정하지 못했어요.",
  evidences: [
    { title: "실패 알림 문구 개선", createdAt: "2026-10-21" },
    { title: "알림 묶어 보내기", createdAt: "2026-10-21" },
  ],
} as const;

/**
 * 마지막 단계에서 보여 주는 질문 종류. 누르면 이 질문을 실제로 보내요.
 *
 * 회의 이름이나 날짜를 넣어야 한다고 느끼지 않도록, 평소 말하듯 묻는 질문으로 써요.
 */
export const SEARCH_TUTORIAL_QUESTION_KINDS = [
  { kind: "past_decision", question: "지난번에 정한 거 알려 줘", label: "결정 찾기" },
  { kind: "undecided", question: "아직 안 정한 게 뭐야?", label: "남은 결정" },
  { kind: "next_action", question: "다음에 할 일 정리해 줘", label: "할 일 확인" },
] as const;

export type SearchTutorialQuestionKind =
  (typeof SEARCH_TUTORIAL_QUESTION_KINDS)[number]["kind"];
