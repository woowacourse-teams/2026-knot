/**
 * 문서 행 하나의 높이(rem). `DocumentRow`의 위아래 여백과 글줄 높이로 정해져요.
 *
 * 제목과 요약은 한 줄에서 말줄임되므로 글이 길어도 높이가 그대로예요.
 * `DocumentRow`의 여백, 글자 크기, 줄 높이를 바꾸면 이 값도 함께 바꿔야 해요.
 */
export const ROW_HEIGHT_REM = {
  /** 위아래 여백 1.25rem씩 + 제목 한 줄(1rem × 줄 높이 1.5) */
  withoutSummary: 4,
  /** 요약 없는 행 + 제목과 요약 사이 0.25rem + 요약 한 줄 1.5rem */
  withSummary: 5.75,
} as const;

/** 행 사이 구분선 두께(px). 두 번째 행부터 위쪽에 그어서 그 행의 높이에 들어가요 */
export const ROW_DIVIDER_PX = 1;
