import { SEARCH_TUTORIAL_RECORD_KEY } from "../constants/searchTutorial";

/**
 * 멤버가 튜토리얼을 어디까지 겪었는지 브라우저에 남긴 기록.
 * - `done`: 끝냈거나 건너뛰었거나 닫았어요. 다시 띄우지 않아요
 * - `snoozed`: 「다음에 볼게요」를 눌렀어요. `at`(그날 날짜) 다음 날부터 한 번 더 띄워요
 */
export type SearchTutorialRecord =
  | { state: "done" }
  | { state: "snoozed"; at: string };

/** 지금 띄울 수 있는지와 어떤 모양으로 띄울지. 띄우지 않으면 `null`이에요 */
export type SearchTutorialEntry = "first" | "again" | null;

/** 로컬 시간대의 날짜(YYYY-MM-DD). 미룬 날과 오늘을 날짜로만 비교해요 */
export const toLocalDate = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");

  return `${year}-${month}-${day}`;
};

/**
 * @description 기록과 오늘 날짜로 튜토리얼을 띄울 수 있는지 정해요
 * @param record - 브라우저에 남긴 기록. 없으면 `null`
 * @param today - 오늘 날짜(YYYY-MM-DD)
 * @returns 처음이면 `first`, 미룬 다음 날 이후면 `again`, 그 밖에는 `null`
 * @example
 * getSearchTutorialEntry({ state: "snoozed", at: "2026-11-11" }, "2026-11-12"); // "again"
 */
export const getSearchTutorialEntry = (
  record: SearchTutorialRecord | null,
  today: string,
): SearchTutorialEntry => {
  if (record === null) return "first";
  if (record.state === "done") return null;

  return today > record.at ? "again" : null;
};

const getKey = (memberId: number) => `${SEARCH_TUTORIAL_RECORD_KEY}.${memberId}`;

const isRecord = (value: unknown): value is SearchTutorialRecord => {
  if (typeof value !== "object" || value === null) return false;

  const { state, at } = value as { state?: unknown; at?: unknown };

  return state === "done" || (state === "snoozed" && typeof at === "string");
};

/**
 * 저장소를 못 쓰면(시크릿 모드 등) 다 본 것으로 답해요. 몇 번 봤는지 알 수 없어 매번 띄우게 되면 안내가 방해가 되기 때문이에요.
 * 남은 값이 깨져 있으면 처음으로 봐요.
 */
export const readSearchTutorialRecord = (
  memberId: number,
): SearchTutorialRecord | null => {
  let raw: string | null;
  try {
    raw = localStorage.getItem(getKey(memberId));
  } catch {
    return { state: "done" };
  }
  if (raw === null) return null;

  try {
    const parsed: unknown = JSON.parse(raw);

    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

export const writeSearchTutorialRecord = (
  memberId: number,
  record: SearchTutorialRecord,
) => {
  try {
    localStorage.setItem(getKey(memberId), JSON.stringify(record));
  } catch {
    // 남길 수 없으면 이번에만 보여 주고 넘어가요
  }
};
