const ISO_DATE = /^\d{4}-(\d{2})-(\d{2})$/;

/**
 * DB 칸 값이 날짜(YYYY-MM-DD)면 카드에 보일 「M월 D일」로 바꾸고, 아니면 그대로 돌려줘요.
 *
 * 회의 날짜는 시각 없는 날짜라 시간대에 따라 하루가 밀리지 않게 글자에서 바로 읽어요.
 *
 * @example
 * formatDatabaseFieldValue("2026-11-06"); // "11월 6일"
 */
export const formatDatabaseFieldValue = (value: string) => {
  const match = value.match(ISO_DATE);
  if (!match) return value;

  return `${Number(match[1])}월 ${Number(match[2])}일`;
};
