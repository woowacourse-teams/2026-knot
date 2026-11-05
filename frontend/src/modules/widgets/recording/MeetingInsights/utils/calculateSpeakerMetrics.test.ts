import { describe, expect, it } from "vitest";

import { calculateSpeakerMetrics, OTHERS_KEY } from "./calculateSpeakerMetrics";

const segment = (
  startSeconds: number,
  endSeconds: number,
  speakerNumber: number | null,
  text = "가나다라마바사아자차",
) => ({
  startMillis: startSeconds * 1000,
  endMillis: endSeconds * 1000,
  speakerNumber,
  text,
});

describe("calculateSpeakerMetrics", () => {
  it("참여자별 발화 시간과 비율을 많이 말한 순서로 돌려준다", () => {
    const { groups, topRatio } = calculateSpeakerMetrics(
      [segment(0, 60, 1), segment(60, 90, 2), segment(90, 100, 2)],
      120,
    );

    expect(groups.map(({ key, seconds }) => [key, seconds])).toEqual([
      ["1", 60],
      ["2", 40],
    ]);
    expect(groups[0].ratio).toBeCloseTo(0.6);
    expect(topRatio).toBeCloseTo(0.6);
  });

  it("8번째 참여자부터 「기타」로 묶고 짧은 참여자 수를 센다", () => {
    const segments = Array.from({ length: 9 }, (_, index) =>
      segment(
        index * 60,
        index * 60 + (index === 8 ? 10 : 50 - index),
        index + 1,
      ),
    );

    const { groups, speakerCount, shortSpeakerCount } = calculateSpeakerMetrics(
      segments,
      600,
    );

    expect(groups).toHaveLength(8);
    expect(groups[7].key).toBe(OTHERS_KEY);
    expect(groups[7].label).toBe("기타 2명");
    expect(speakerCount).toBe(9);
    expect(shortSpeakerCount).toBe(1);
  });

  it("화자가 없는 구간은 「미상」으로 묶고 비율을 알려 준다", () => {
    const { groups, unknownRatio } = calculateSpeakerMetrics(
      [segment(0, 30, 1), segment(30, 60, null)],
      60,
    );

    expect(groups.map(({ label }) => label)).toEqual(["참여자 1", "미상"]);
    expect(unknownRatio).toBeCloseTo(0.5);
  });

  it("같은 화자의 이어진 구간을 한 차례로 묶어 전환과 연속 발화를 계산한다", () => {
    const { switchCount, switchesPerHour, longestTurn } =
      calculateSpeakerMetrics(
        [
          segment(0, 10, 1),
          segment(12, 30, 1),
          segment(30, 40, 2),
          segment(40, 50, 1),
        ],
        1800,
      );

    expect(switchCount).toBe(2);
    expect(switchesPerHour).toBeCloseTo(4);
    expect(longestTurn).toEqual({
      label: "참여자 1",
      seconds: 28,
      startMillis: 0,
    });
  });

  it("1/N 차이와 지니 계수로 참여 균형을 계산한다", () => {
    const even = calculateSpeakerMetrics(
      [segment(0, 30, 1), segment(30, 60, 2)],
      60,
    );
    const uneven = calculateSpeakerMetrics(
      [segment(0, 90, 1), segment(90, 120, 2)],
      120,
    );

    expect(even.balance.gini).toBeCloseTo(0);
    expect(even.balance.gapFromEven).toBeCloseTo(0);
    expect(uneven.balance.gini).toBeCloseTo(0.25);
    expect(uneven.balance.gapFromEven).toBeCloseTo(0.25);
    expect(uneven.balance.countedSpeakers).toBe(2);
  });

  it("누가 누구 뒤에 말했는지 횟수를 센다", () => {
    const { links } = calculateSpeakerMetrics(
      [
        segment(0, 10, 1),
        segment(10, 20, 2),
        segment(20, 30, 1),
        segment(30, 40, 2),
      ],
      60,
    );

    expect(links).toEqual([
      { from: "1", to: "2", count: 2 },
      { from: "2", to: "1", count: 1 },
    ]);
  });

  it("말 속도와 응답 간격, 문장 수, 질문 수를 참여자별로 계산한다", () => {
    const { groups } = calculateSpeakerMetrics(
      [
        segment(0, 60, 1, "가".repeat(300)),
        segment(62, 92, 2, "그럼 언제 할까요? 금요일에 합니다."),
        segment(96, 126, 1, "좋습니다."),
      ],
      130,
    );
    const [first, second] = groups;

    expect(first.charactersPerMinute).toBeCloseTo((305 / 90) * 60);
    expect(second.medianResponseSeconds).toBe(2);
    expect(first.medianResponseSeconds).toBe(4);
    expect(second.sentenceCount).toBe(2);
    expect(second.questionCount).toBe(1);
  });

  it("회의를 앞 · 중간 · 끝으로 나눠 구간별 발화 비율을 계산한다", () => {
    const { phases } = calculateSpeakerMetrics(
      [segment(0, 30, 1), segment(30, 60, 2), segment(60, 90, 1)],
      90,
    );

    expect(phases.map(({ label }) => label)).toEqual(["앞", "중간", "끝"]);
    expect(phases[0].shares).toEqual([{ key: "1", ratio: 1 }]);
    expect(phases[1].shares).toEqual([{ key: "2", ratio: 1 }]);
  });
});
