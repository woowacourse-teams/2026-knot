import type { SpeakerGroup } from "../types/metrics";
import { OTHERS_KEY, UNKNOWN_KEY } from "../utils/calculateSpeakerMetrics";

/**
 * 화자 색. dataviz 검증기로 흰 배경에서 확인한 순서라 바꾸면 다시 검사해야 해요.
 * 연한 색은 3:1 대비에 못 미쳐서, 그림 옆에 값과 범례를 항상 함께 보여 줘요
 */
const SPEAKER_COLORS = [
  "#2a78d6",
  "#eb6834",
  "#1baf7a",
  "#eda100",
  "#e87ba4",
  "#008300",
  "#6250d6",
  "#e34948",
];

/** 「기타」와 「미상」처럼 사람을 가리키지 않는 묶음의 색 */
export const NEUTRAL_SERIES_COLOR = "#B0ABA2";

/** 값의 크기를 칸 색으로 보여 줄 때 쓰는 한 색상 단계. 앱의 강조색이에요 */
export const SEQUENTIAL_COLORS = ["#E5F0F4", "#A8CEDB", "#2E85A3", "#2F6F82"];

export const SINGLE_SERIES_COLOR = "#2E85A3";

/** 회의 안에서 화자마다 색을 하나로 고정해요. 그림이 달라도 같은 참여자는 같은 색이에요 */
export const createSpeakerColorMap = (groups: SpeakerGroup[]) => {
  const named = groups.filter(
    ({ key }) => key !== OTHERS_KEY && key !== UNKNOWN_KEY,
  );

  return new Map(
    groups.map(({ key }) => {
      const index = named.findIndex((group) => group.key === key);

      return [key, SPEAKER_COLORS[index] ?? NEUTRAL_SERIES_COLOR];
    }),
  );
};
