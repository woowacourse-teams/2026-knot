import type { DocumentTranscriptSegmentRaw } from "@api/dto/document";

import type { SpeakerGroup, SpeakerMetrics } from "../types/metrics";
import {
  countCharacters,
  isQuestionSentence,
  splitSentences,
} from "./splitSentences";

export const OTHERS_KEY = "others";
export const UNKNOWN_KEY = "unknown";

const MILLIS_PER_SECOND = 1000;
const SECONDS_PER_HOUR = 3600;
/** 그림의 색 수 제한. 나머지 화자는 「기타」로 묶어요 */
const NAMED_GROUP_LIMIT = 7;
/** 이보다 짧게 말한 화자는 화자 나누기가 잘못 만든 번호일 수 있어요 */
const SHORT_SPEAKER_SECONDS = 20;
const PHASE_LABELS = ["앞", "중간", "끝"];

const toSpeakerKey = (speakerNumber: number | null) =>
  speakerNumber === null ? UNKNOWN_KEY : String(speakerNumber);

const toSpeakerLabel = (key: string) =>
  key === UNKNOWN_KEY ? "미상" : `참여자 ${key}`;

const segmentSeconds = ({
  startMillis,
  endMillis,
}: DocumentTranscriptSegmentRaw) =>
  endMillis === null ? 0 : (endMillis - startMillis) / MILLIS_PER_SECOND;

const median = (values: number[]) => {
  if (values.length === 0) return null;

  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);

  return sorted.length % 2 === 1
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};

const calculateGini = (values: number[]) => {
  const total = values.reduce((sum, value) => sum + value, 0);

  if (values.length === 0 || total === 0) return 0;

  const differenceTotal = values.reduce(
    (sum, left) =>
      sum + values.reduce((inner, right) => inner + Math.abs(left - right), 0),
    0,
  );

  return differenceTotal / (2 * values.length * total);
};

const calculateBalance = (secondsBySpeaker: Map<string, number>) => {
  const counted = [...secondsBySpeaker.entries()]
    .filter(
      ([key, seconds]) =>
        key !== UNKNOWN_KEY && seconds >= SHORT_SPEAKER_SECONDS,
    )
    .map(([, seconds]) => seconds);
  const total = counted.reduce((sum, seconds) => sum + seconds, 0);
  const even = 1 / Math.max(counted.length, 1);

  return {
    countedSpeakers: counted.length,
    gapFromEven:
      total === 0
        ? 0
        : counted.reduce(
            (sum, seconds) => sum + Math.abs(seconds / total - even),
            0,
          ) / counted.length,
    gini: calculateGini(counted),
  };
};

/** 많이 말한 7명까지는 그대로 두고, 나머지 화자를 「기타」로 보내는 표를 만들어요 */
const createGroupKeyMap = (secondsBySpeaker: Map<string, number>) => {
  const ranked = [...secondsBySpeaker.entries()].sort(
    (left, right) => right[1] - left[1],
  );
  const isGrouped = ranked.length > NAMED_GROUP_LIMIT + 1;

  return new Map(
    ranked.map(([key], rank) => [
      key,
      isGrouped && rank >= NAMED_GROUP_LIMIT ? OTHERS_KEY : key,
    ]),
  );
};

const createGroups = (
  segments: DocumentTranscriptSegmentRaw[],
  groupKeyOf: (speakerNumber: number | null) => string,
  secondsBySpeaker: Map<string, number>,
  groupKeyMap: Map<string, string>,
): SpeakerGroup[] => {
  const totalSeconds = [...secondsBySpeaker.values()].reduce(
    (sum, seconds) => sum + seconds,
    0,
  );
  const responses = new Map<string, number[]>();

  segments.slice(1).forEach(({ startMillis, speakerNumber }, index) => {
    const previous = segments[index];

    if (
      previous.endMillis === null ||
      previous.speakerNumber === speakerNumber
    ) {
      return;
    }

    const key = groupKeyOf(speakerNumber);
    const seconds = Math.max(
      0,
      (startMillis - previous.endMillis) / MILLIS_PER_SECOND,
    );

    responses.set(key, [...(responses.get(key) ?? []), seconds]);
  });

  const groupKeys = [
    ...new Set(segments.map((s) => groupKeyOf(s.speakerNumber))),
  ];
  const otherCount = [...groupKeyMap.values()].filter(
    (key) => key === OTHERS_KEY,
  ).length;

  return groupKeys
    .map((key) => {
      const own = segments.filter((s) => groupKeyOf(s.speakerNumber) === key);
      const seconds = own.reduce((sum, s) => sum + segmentSeconds(s), 0);
      const characters = own.reduce(
        (sum, s) => sum + countCharacters(s.text),
        0,
      );
      const sentences = own.flatMap(({ text }) => splitSentences(text));

      return {
        key,
        label:
          key === OTHERS_KEY ? `기타 ${otherCount}명` : toSpeakerLabel(key),
        seconds,
        ratio: totalSeconds === 0 ? 0 : seconds / totalSeconds,
        characters,
        charactersPerMinute: seconds === 0 ? 0 : (characters / seconds) * 60,
        sentenceCount: sentences.length,
        questionCount: sentences.filter(isQuestionSentence).length,
        medianResponseSeconds: median(responses.get(key) ?? []),
        intervals: own.flatMap(({ startMillis, endMillis }) =>
          endMillis === null ? [] : [{ startMillis, endMillis }],
        ),
      };
    })
    .sort((left, right) => {
      if (left.key === OTHERS_KEY) return 1;
      if (right.key === OTHERS_KEY) return -1;

      return right.seconds - left.seconds;
    });
};

/** 같은 화자가 이어 말한 구간을 한 차례로 묶어요 */
const createTurns = (segments: DocumentTranscriptSegmentRaw[]) =>
  segments.reduce<{ key: string; seconds: number; startMillis: number }[]>(
    (turns, segment) => {
      const key = toSpeakerKey(segment.speakerNumber);
      const last = turns[turns.length - 1];

      if (last?.key === key) {
        last.seconds += segmentSeconds(segment);

        return turns;
      }

      return [
        ...turns,
        {
          key,
          seconds: segmentSeconds(segment),
          startMillis: segment.startMillis,
        },
      ];
    },
    [],
  );

const countLinks = (
  turns: { key: string }[],
  groupKeyMap: Map<string, string>,
) => {
  const counts = new Map<string, { from: string; to: string; count: number }>();

  turns.slice(1).forEach(({ key }, index) => {
    const from = groupKeyMap.get(turns[index].key) ?? turns[index].key;
    const to = groupKeyMap.get(key) ?? key;

    if (from === to) return;

    const link = counts.get(`${from}>${to}`) ?? { from, to, count: 0 };

    counts.set(`${from}>${to}`, { ...link, count: link.count + 1 });
  });

  return [...counts.values()].sort((left, right) => right.count - left.count);
};

const calculatePhases = (
  segments: DocumentTranscriptSegmentRaw[],
  groupKeyOf: (speakerNumber: number | null) => string,
  orderedKeys: string[],
  durationSeconds: number,
) => {
  const phaseMillis =
    (durationSeconds * MILLIS_PER_SECOND) / PHASE_LABELS.length;

  return PHASE_LABELS.map((label, index) => {
    const phaseStart = index * phaseMillis;
    const phaseEnd = phaseStart + phaseMillis;
    const seconds = new Map<string, number>();

    segments.forEach(({ startMillis, endMillis, speakerNumber }) => {
      if (endMillis === null) return;

      const overlap =
        Math.min(endMillis, phaseEnd) - Math.max(startMillis, phaseStart);

      if (overlap <= 0) return;

      const key = groupKeyOf(speakerNumber);

      seconds.set(key, (seconds.get(key) ?? 0) + overlap / MILLIS_PER_SECOND);
    });

    const total = [...seconds.values()].reduce((sum, value) => sum + value, 0);

    return {
      label,
      shares: orderedKeys.flatMap((key) => {
        const value = seconds.get(key);

        return value === undefined ? [] : [{ key, ratio: value / total }];
      }),
    };
  });
};

/**
 * 전사 구간의 화자 번호로 계산할 수 있는 값을 모아요.
 * 번호가 틀리면 값도 틀려서, 화면은 이 값 옆에 화자 품질을 함께 보여 줘요.
 */
export const calculateSpeakerMetrics = (
  segments: DocumentTranscriptSegmentRaw[],
  durationSeconds: number,
): SpeakerMetrics => {
  const secondsBySpeaker = new Map<string, number>();

  segments.forEach((segment) => {
    const key = toSpeakerKey(segment.speakerNumber);

    secondsBySpeaker.set(
      key,
      (secondsBySpeaker.get(key) ?? 0) + segmentSeconds(segment),
    );
  });

  const groupKeyMap = createGroupKeyMap(secondsBySpeaker);
  const groupKeyOf = (speakerNumber: number | null) => {
    const key = toSpeakerKey(speakerNumber);

    return groupKeyMap.get(key) ?? key;
  };
  const groups = createGroups(
    segments,
    groupKeyOf,
    secondsBySpeaker,
    groupKeyMap,
  );
  const totalSeconds = groups.reduce((sum, { seconds }) => sum + seconds, 0);
  const turns = createTurns(segments);
  const longest = turns.reduce<(typeof turns)[number] | null>(
    (best, turn) =>
      best === null || turn.seconds > best.seconds ? turn : best,
    null,
  );
  const namedSpeakers = [...secondsBySpeaker.keys()].filter(
    (key) => key !== UNKNOWN_KEY,
  );
  const switchCount = Math.max(turns.length - 1, 0);
  const topSeconds = Math.max(
    0,
    ...[...secondsBySpeaker.entries()]
      .filter(([key]) => key !== UNKNOWN_KEY)
      .map(([, seconds]) => seconds),
  );

  return {
    groups,
    speakerCount: namedSpeakers.length,
    shortSpeakerCount: namedSpeakers.filter(
      (key) => (secondsBySpeaker.get(key) ?? 0) < SHORT_SPEAKER_SECONDS,
    ).length,
    unknownRatio:
      totalSeconds === 0
        ? 0
        : (secondsBySpeaker.get(UNKNOWN_KEY) ?? 0) / totalSeconds,
    topRatio: totalSeconds === 0 ? 0 : topSeconds / totalSeconds,
    balance: calculateBalance(secondsBySpeaker),
    switchCount,
    switchesPerHour:
      durationSeconds === 0
        ? 0
        : (switchCount / durationSeconds) * SECONDS_PER_HOUR,
    longestTurn:
      longest === null
        ? null
        : {
            label: toSpeakerLabel(longest.key),
            seconds: longest.seconds,
            startMillis: longest.startMillis,
          },
    links: countLinks(turns, groupKeyMap),
    phases: calculatePhases(
      segments,
      groupKeyOf,
      groups.map(({ key }) => key),
      durationSeconds,
    ),
  };
};
