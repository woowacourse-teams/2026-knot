/**
 * GNB 가운데 탭(홈, 탐색, 문서)과 문서 탭(녹음별, 주제별)을 옮길 때의 모션 값.
 *
 * 탭 칸의 눌림과 진한 표시의 물방울 모션은 GNB와 문서 탭이 함께, 본문 전환은 워크스페이스 레이아웃이 써서 한 곳에 둬요.
 * 한 번의 전환이 0.4초 안에 끝나고, 본문은 8px 이하로만 움직이게 맞췄어요.
 * 크기는 0.96배에서 1.06배 사이, 미끄러지며 늘어나는 모양은 가로 1.08배, 세로 0.94배 안에서만 바뀌어요.
 * 이보다 크면 물방울보다 젤리처럼 보여 장난스러워져요.
 */

/** 등장 감속. 빠르게 출발해 부드럽게 멈춰요 */
export const TAB_ENTER_EASING = "cubic-bezier(0.22, 1, 0.36, 1)";

/** 손을 뗀 탭 칸이 통 튀어 돌아오는 곡선. 0.96배에서 돌아오면 1.004배까지만 넘쳐요 */
export const TAB_POP_EASING = "cubic-bezier(0.34, 1.56, 0.64, 1)";

/**
 * 물방울이 출렁이는 곡선. 모양이 바뀌는 끝마다 천천히 돌아서요.
 * 늘어남, 맺힘, 되돌아옴을 이 곡선으로 이어 감쇠 진동처럼 보이게 해요
 */
export const TAB_DROPLET_EASING = "cubic-bezier(0.37, 0, 0.63, 1)";

/** 누르는 동안의 탭 칸과 지금 탭 표시의 크기 */
export const TAB_PRESS_SCALE = 0.96;

/** 누를 때 작아지는 시간(ms) */
export const TAB_PRESS_DURATION_MS = 100;

/** 손을 뗀 탭 칸 글자가 원래 크기로 돌아오는 시간(ms) */
export const TAB_RELEASE_DURATION_MS = 200;

/** 진한 표시가 미끄러지고 글자색이 바뀌는 시간(ms) */
export const TAB_INDICATOR_DURATION_MS = 240;

/** 진한 표시가 미끄러지는 동안 가장 늘어난 모양. 진행 방향(가로)으로 늘고 그만큼 세로로 눌려요 */
export const TAB_DROPLET_STRETCH_SCALE = { x: 1.08, y: 0.94 } as const;

/** 진한 표시가 새 탭에 도착하거나 제자리에서 톡 맺힐 때 가장 부푼 크기 */
export const TAB_DROPLET_SWELL_SCALE = 1.06;

/** 부푼 뒤 한 번 되돌아 살짝 작아지는 크기. 이 한 번의 출렁임이 있어야 물방울처럼 보여요 */
export const TAB_DROPLET_SETTLE_SCALE = 0.985;

/**
 * 미끄러지며 늘어났다가 도착해 맺히고 자리잡기까지의 시간(ms).
 * 표시가 미끄러지는 시간보다 길어서, 도착한 뒤에 맺힘이 마저 출렁여요
 */
export const TAB_DROPLET_SLIDE_DURATION_MS = 380;

/**
 * 미끄러질 때 모양마다 가장 커지는 때(전체 시간 중 0에서 1 사이).
 * 표시는 출발 직후 가장 빨라서 늘어남도 앞쪽에 두고, 맺힘은 표시가 거의 도착한 때에 둬요
 */
export const TAB_DROPLET_SLIDE_TIMING = {
  stretch: 0.2,
  swell: 0.5,
  settle: 0.75,
} as const;

/** 지금 탭을 눌렀다 뗄 때 제자리에서 톡 맺히고 자리잡기까지의 시간(ms) */
export const TAB_DROPLET_TAP_DURATION_MS = 320;

/** 제자리에서 맺힐 때 모양마다 가장 커지는 때(전체 시간 중 0에서 1 사이) */
export const TAB_DROPLET_TAP_TIMING = { swell: 0.4, settle: 0.72 } as const;

/** 본문이 들어오는 시간(ms) */
export const TAB_SCREEN_DURATION_MS = 220;

/** 본문이 탭 방향으로 밀려 들어오기 시작하는 거리(px) */
export const TAB_SCREEN_OFFSET_PX = 8;

/** 새 본문이 처음 그려지는 불투명도. 0에서 시작하면 그 순간 배경만 보여 깜빡여 보여요 */
export const TAB_SCREEN_START_OPACITY = 0.6;
