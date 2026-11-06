/**
 * 독 안내 문구. Figma Design System `Dock/Guide`와 같아요.
 *
 * 첫 사용 안내는 접힌 독 위에 채팅과 회의 녹음을 함께 설명하고, 채팅·녹음 안내는 각 버튼에 올리거나 포커스했을 때 한 줄로 보여요.
 */
export const DOCK_GUIDE_COPY = {
  first: {
    title: "채팅 아이콘을 누르거나 글자를 입력해 보세요",
    chat: { title: "채팅으로 기록 찾기", description: "지난 회의의 내용과 결정을 물어보세요." },
    record: { title: "회의 녹음하기", description: "회의를 녹음하면 주제별 문서로 정리해요." },
  },
  chat: "채팅으로 지난 기록을 찾아보세요",
  record: "회의 녹음 · 끝내면 문서로 정리해요",
} as const;

/** 첫 사용 안내를 보여줄 최대 방문 횟수. 이만큼 보고 나면 다시 띄우지 않아요. */
export const DOCK_HINT_MAX_SEEN_COUNT = 3;

/** 홈이 그려진 뒤 첫 사용 안내가 뜨기까지 기다리는 시간(ms). 탭 전환 모션이 끝난 뒤에 떠야 화면이 바뀌는 중에 깜빡이지 않아요 */
export const DOCK_HINT_DELAY_MS = 320;

/** 버튼에 올린 뒤 채팅·녹음 안내가 뜨기까지 기다리는 시간(ms). 스쳐 지나갈 때는 뜨지 않아요 */
export const DOCK_HOVER_GUIDE_DELAY_MS = 160;

/** 한 방문에서 버튼마다 채팅·녹음 안내를 띄우는 최대 횟수. 첫 사용 안내를 띄우는 방문(처음 세 번) 안에서만 세요 */
export const DOCK_HOVER_GUIDE_MAX_PER_VISIT = 2;

/** 안내가 사라지는 모션 시간(ms). 이 동안은 그려 둔 채 옅어져요 */
export const DOCK_GUIDE_LEAVE_MS = 160;

/** 첫 사용 안내를 몇 번 보여줬는지 브라우저에 남겨 두는 키. */
export const DOCK_HINT_SEEN_COUNT_KEY = "knot.workspaceDockHintSeenCount";

/** 이번 방문에서 첫 사용 안내를 이미 처리했는지 표시해 두는 키. 탭을 닫으면 지워져요. */
export const DOCK_HINT_VISIT_KEY = "knot.workspaceDockHintVisit";

/** 이번 방문에서 버튼 안내를 버튼마다 몇 번 띄웠는지 두는 키. 탭을 닫으면 지워져요. */
export const DOCK_HOVER_GUIDE_COUNT_KEY = "knot.workspaceDockHoverGuideCount";
