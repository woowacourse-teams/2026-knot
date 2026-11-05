import type { DocumentTranscriptSegmentRaw } from "@api/dto/document";

import { FREQUENT_WORD_STOPWORDS } from "../constants/stopwords";
import type { MeetingInsightsMeeting } from "../types/dataset";
import type { MeetingMetrics, SilenceRange } from "../types/metrics";
import { parseDocumentSections } from "./parseDocumentSections";
import {
  countCharacters,
  isQuestionSentence,
  splitSentences,
} from "./splitSentences";

const MILLIS_PER_SECOND = 1000;
const MILLIS_PER_MINUTE = 60_000;
const LONG_SILENCE_SECONDS = 15;
const LONG_SILENCE_LIMIT = 5;
const FREQUENT_WORD_LIMIT = 15;

const DATE_PATTERN =
  /\d{1,2}월(?:\s*\d{1,2}일)?|\d{1,2}일|오늘|내일|모레|어제|이번 주|다음 주|지난주|[월화수목금토일]요일/g;
const NUMBER_PATTERN =
  /\d+(?:[.,]\d+)?\s*(?:%|퍼센트|개|명|분|시간|초|원|만|억|건|번|달|배)/g;
const WORD_SEPARATOR = /[\s.,?!"'()…~]+/;
const PARTICLE_SUFFIX =
  /(?:으로|에서|에게|까지|부터|처럼|보다|한테|이랑|은|는|이|가|을|를|에|의|도|로|와|과|랑|만)$/;
const VERB_ENDING_SUFFIX =
  /(?:요|다|까|죠|네|게|는데|인데|니까|지만|해서|어서|아서|라서|하고|되고|있고|없고|하면|되면|으면|라면)$/;

const toTimedRanges = (segments: DocumentTranscriptSegmentRaw[]) =>
  segments
    .flatMap(({ startMillis, endMillis }) =>
      endMillis === null ? [] : [{ startMillis, endMillis }],
    )
    .sort((left, right) => left.startMillis - right.startMillis);

/** 겹친 구간을 합쳐요. 화자가 바뀌는 곳의 구간은 0.01초쯤 겹쳐서 그대로 더하면 시간이 늘어나요 */
const mergeRanges = (ranges: { startMillis: number; endMillis: number }[]) =>
  ranges.reduce<{ startMillis: number; endMillis: number }[]>(
    (merged, range) => {
      const last = merged[merged.length - 1];

      if (last === undefined || range.startMillis > last.endMillis) {
        return [...merged, { ...range }];
      }

      last.endMillis = Math.max(last.endMillis, range.endMillis);

      return merged;
    },
    [],
  );

const findLongSilences = (
  ranges: { startMillis: number; endMillis: number }[],
): SilenceRange[] =>
  ranges
    .slice(1)
    .map(({ startMillis }, index) => ({
      startMillis: ranges[index].endMillis,
      endMillis: startMillis,
      seconds: (startMillis - ranges[index].endMillis) / MILLIS_PER_SECOND,
    }))
    .filter(({ seconds }) => seconds >= LONG_SILENCE_SECONDS)
    .sort((left, right) => right.seconds - left.seconds)
    .slice(0, LONG_SILENCE_LIMIT);

/** 구간의 글자를 시간 비율대로 1분 칸에 나눠 담아요. 끝 시각이 없으면 시작한 칸에 모두 담아요 */
const calculateDensity = (
  segments: DocumentTranscriptSegmentRaw[],
  durationSeconds: number,
) => {
  const minuteCount = Math.max(1, Math.ceil(durationSeconds / 60));
  const characters = new Array<number>(minuteCount).fill(0);

  segments.forEach(({ startMillis, endMillis, text }) => {
    const length = countCharacters(text);
    const end = endMillis ?? startMillis;
    const span = end - startMillis;
    const firstMinute = Math.floor(startMillis / MILLIS_PER_MINUTE);
    const lastMinute = Math.floor(
      Math.max(end - 1, startMillis) / MILLIS_PER_MINUTE,
    );

    for (let minute = firstMinute; minute <= lastMinute; minute += 1) {
      const slot = Math.min(minute, minuteCount - 1);
      const overlap =
        Math.min(end, (minute + 1) * MILLIS_PER_MINUTE) -
        Math.max(startMillis, minute * MILLIS_PER_MINUTE);

      characters[slot] += span === 0 ? length : (length * overlap) / span;
    }
  });

  return characters.map((value, minute) => ({
    minute,
    characters: Math.round(value),
  }));
};

const findQuestions = (segments: DocumentTranscriptSegmentRaw[]) =>
  segments.flatMap(({ startMillis, speakerNumber, text }) =>
    splitSentences(text)
      .filter(isQuestionSentence)
      .map((sentence) => ({ startMillis, speakerNumber, text: sentence })),
  );

/** 조사를 떼고 말버릇과 서술어를 뺀 낱말을 세요. 형태소 분석이 아니라 대략의 값이에요 */
const countFrequentWords = (segments: DocumentTranscriptSegmentRaw[]) => {
  const counts = new Map<string, number>();

  segments.forEach(({ text }) => {
    text.split(WORD_SEPARATOR).forEach((token) => {
      const word =
        token.length >= 3 ? token.replace(PARTICLE_SUFFIX, "") : token;

      if (
        word.length < 2 ||
        FREQUENT_WORD_STOPWORDS.has(word) ||
        VERB_ENDING_SUFFIX.test(word)
      ) {
        return;
      }

      counts.set(word, (counts.get(word) ?? 0) + 1);
    });
  });

  return [...counts.entries()]
    .map(([word, count]) => ({ word, count }))
    .sort((left, right) => right.count - left.count)
    .slice(0, FREQUENT_WORD_LIMIT);
};

const countMatches = (texts: string[], pattern: RegExp) =>
  texts.reduce((total, text) => total + (text.match(pattern)?.length ?? 0), 0);

/** 회의 한 건에서 화자와 관계없이 계산할 수 있는 값을 모아요 */
export const calculateMeetingMetrics = ({
  recordingDurationSeconds,
  segments,
  documents,
}: MeetingInsightsMeeting): MeetingMetrics => {
  const ranges = mergeRanges(toTimedRanges(segments));
  const spokenMillis = ranges.reduce(
    (total, { startMillis, endMillis }) => total + endMillis - startMillis,
    0,
  );
  const spokenSeconds = spokenMillis / MILLIS_PER_SECOND;
  const sections = documents.map(({ content }) =>
    parseDocumentSections(content),
  );
  const decidedTopicCount = sections.filter(
    ({ decisions }) => decisions.length > 0,
  ).length;
  const texts = segments.map(({ text }) => text);

  return {
    durationSeconds: recordingDurationSeconds,
    spokenSeconds,
    spokenRatio:
      recordingDurationSeconds === 0
        ? 0
        : spokenSeconds / recordingDurationSeconds,
    missingEndCount: segments.filter(({ endMillis }) => endMillis === null)
      .length,
    resultCounts: {
      decisions: sections.flatMap(({ decisions }) => decisions).length,
      holds: sections.flatMap(({ holds }) => holds).length,
      undecided: sections.flatMap(({ undecided }) => undecided).length,
      todos: sections.flatMap(({ todos }) => todos).length,
    },
    topicCount: documents.length,
    decidedTopicCount,
    decidedTopicRatio:
      documents.length === 0 ? 0 : decidedTopicCount / documents.length,
    density: calculateDensity(segments, recordingDurationSeconds),
    longSilences: findLongSilences(ranges),
    questions: findQuestions(segments),
    dateMentionCount: countMatches(texts, DATE_PATTERN),
    numberMentionCount: countMatches(texts, NUMBER_PATTERN),
    frequentWords: countFrequentWords(segments),
    confirmedTotal: documents.reduce(
      (total, { confirmedCount }) => total + confirmedCount,
      0,
    ),
  };
};
