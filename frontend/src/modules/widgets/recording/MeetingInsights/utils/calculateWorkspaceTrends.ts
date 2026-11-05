import type { MeetingInsightsMeeting } from "../types/dataset";
import type { TrendPoint, WorkspaceTrends } from "../types/metrics";
import { calculateSpeakerMetrics } from "./calculateSpeakerMetrics";
import { getMeetingLabel } from "./getMeetingLabel";
import { parseDocumentSections } from "./parseDocumentSections";
import { toKstDateParts, toWeekStart } from "./toKstDateParts";

const FOLDER_LIMIT = 12;

const createPoints = (meetings: MeetingInsightsMeeting[]) => {
  let undecidedTotal = 0;

  return meetings.map((meeting): TrendPoint => {
    const { documents, recordingDurationSeconds, segments, startedAt } =
      meeting;
    const sections = documents.map(({ content }) =>
      parseDocumentSections(content),
    );
    const undecidedCount = sections.flatMap(
      ({ undecided }) => undecided,
    ).length;

    const { switchesPerHour, speakerCount } = calculateSpeakerMetrics(
      segments,
      recordingDurationSeconds,
    );

    undecidedTotal += undecidedCount;

    return {
      recordingId: meeting.recordingId,
      dateKey: toKstDateParts(startedAt).dateKey,
      label: getMeetingLabel(meeting),
      durationSeconds: recordingDurationSeconds,
      durationMinutes: Math.round(recordingDurationSeconds / 60),
      decisionCount: sections.flatMap(({ decisions }) => decisions).length,
      undecidedCount,
      undecidedTotal,
      todoCount: sections.flatMap(({ todos }) => todos).length,
      documentCount: documents.length,
      switchesPerHour,
      speakerCount,
      viewTotal: documents.reduce(
        (total, { viewCount }) => total + viewCount,
        meeting.viewCount,
      ),
      searchEvidenceTotal: documents.reduce(
        (total, { searchEvidenceCount }) => total + searchEvidenceCount,
        0,
      ),
    };
  });
};

const createWeeks = (points: TrendPoint[]) => {
  const weeks = new Map<
    string,
    { meetings: number; withoutDecision: number }
  >();

  points.forEach(({ dateKey, decisionCount }) => {
    const weekStart = toWeekStart(dateKey);
    const week = weeks.get(weekStart) ?? { meetings: 0, withoutDecision: 0 };

    weeks.set(weekStart, {
      meetings: week.meetings + 1,
      withoutDecision: week.withoutDecision + (decisionCount === 0 ? 1 : 0),
    });
  });

  return [...weeks.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([weekStart, { meetings, withoutDecision }]) => ({
      weekStart,
      meetings,
      withoutDecision,
      ratio: withoutDecision / meetings,
    }));
};

const createCalendar = (points: TrendPoint[]) => {
  const days = new Map<string, { count: number; minutes: number }>();

  points.forEach(({ dateKey, durationMinutes }) => {
    const day = days.get(dateKey) ?? { count: 0, minutes: 0 };

    days.set(dateKey, {
      count: day.count + 1,
      minutes: day.minutes + durationMinutes,
    });
  });

  return [...days.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([dateKey, day]) => ({ dateKey, ...day }));
};

const createWeekdayHours = (meetings: MeetingInsightsMeeting[]) => {
  const cells = new Map<
    string,
    { weekday: number; hour: number; count: number }
  >();

  meetings.forEach(({ startedAt }) => {
    const { weekday, hour } = toKstDateParts(startedAt);
    const key = `${weekday}-${hour}`;
    const cell = cells.get(key) ?? { weekday, hour, count: 0 };

    cells.set(key, { ...cell, count: cell.count + 1 });
  });

  return [...cells.values()].sort(
    (left, right) => left.weekday - right.weekday || left.hour - right.hour,
  );
};

const createRepeatedFolders = (meetings: MeetingInsightsMeeting[]) => {
  const folders = new Map<
    string,
    { meetings: Set<number>; documents: number }
  >();

  meetings.forEach(({ recordingId, documents }) => {
    documents.forEach(({ folderName }) => {
      if (folderName === null) return;

      const folder = folders.get(folderName) ?? {
        meetings: new Set<number>(),
        documents: 0,
      };

      folder.meetings.add(recordingId);
      folder.documents += 1;
      folders.set(folderName, folder);
    });
  });

  return [...folders.entries()]
    .map(([folderName, folder]) => ({
      folderName,
      meetings: folder.meetings.size,
      documents: folder.documents,
    }))
    .sort(
      (left, right) =>
        right.meetings - left.meetings || right.documents - left.documents,
    )
    .slice(0, FOLDER_LIMIT);
};

/** 여러 회의를 시간 순서로 비교하는 값을 모아요. `meetings`는 끝난 시각 오름차순이에요 */
export const calculateWorkspaceTrends = (
  meetings: MeetingInsightsMeeting[],
): WorkspaceTrends => {
  const points = createPoints(meetings);

  return {
    points,
    weeks: createWeeks(points),
    calendar: createCalendar(points),
    weekdayHours: createWeekdayHours(meetings),
    repeatedFolders: createRepeatedFolders(meetings),
  };
};
