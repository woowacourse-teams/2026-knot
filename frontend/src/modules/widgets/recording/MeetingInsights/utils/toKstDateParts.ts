const KST_FORMATTER = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  hourCycle: "h23",
});

const MILLIS_PER_DAY = 86_400_000;

/** 날짜 키(`2026-11-02`)를 월요일을 0으로 하는 요일 번호로 바꿔요 */
export const toWeekday = (dateKey: string) =>
  (new Date(`${dateKey}T00:00:00Z`).getUTCDay() + 6) % 7;

/** 날짜 키가 속한 주의 월요일 날짜 키 */
export const toWeekStart = (dateKey: string) =>
  new Date(
    new Date(`${dateKey}T00:00:00Z`).getTime() -
      toWeekday(dateKey) * MILLIS_PER_DAY,
  )
    .toISOString()
    .slice(0, 10);

/** 브라우저 시간대와 관계없이 한국 시간의 날짜와 시를 돌려줘요 */
export const toKstDateParts = (isoDate: string) => {
  const parts = Object.fromEntries(
    KST_FORMATTER.formatToParts(new Date(isoDate)).map(({ type, value }) => [
      type,
      value,
    ]),
  );
  const dateKey = `${parts.year}-${parts.month}-${parts.day}`;

  return {
    dateKey,
    hour: Number(parts.hour),
    weekday: toWeekday(dateKey),
  };
};
