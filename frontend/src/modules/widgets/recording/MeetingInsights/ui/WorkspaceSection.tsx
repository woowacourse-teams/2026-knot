import type { WorkspaceTrends } from "../types/metrics";
import { formatMinutes, formatPercent } from "../utils/formatMetric";
import ChartCard from "./ChartCard";
import ColumnChart from "./ColumnChart";
import HorizontalBars from "./HorizontalBars";
import LineChart from "./LineChart";
import MeetingCalendar from "./MeetingCalendar";
import StatTile, { StatRow } from "./StatTile";
import WeekdayHourHeatmap from "./WeekdayHourHeatmap";

interface WorkspaceSectionProps {
  trends: WorkspaceTrends;
  selectedRecordingId: number;
  memberCount: number;
}

const RANKING_LIMIT = 8;

/** 워크스페이스의 회의를 시간 순서로 비교한 값 */
const WorkspaceSection = ({
  trends,
  selectedRecordingId,
  memberCount,
}: WorkspaceSectionProps) => {
  const { points, weeks, calendar, weekdayHours, repeatedFolders } = trends;
  const startLabel = points[0]?.dateKey.slice(5);
  const endLabel = points[points.length - 1]?.dateKey.slice(5);
  const edgeLabels = { startLabel, endLabel };
  const sum = (pick: (point: (typeof points)[number]) => number) =>
    points.reduce((total, point) => total + pick(point), 0);
  const totalSeconds = sum(({ durationSeconds }) => durationSeconds);
  const toColumns = (
    pick: (point: (typeof points)[number]) => number,
    unit: string,
  ) =>
    points.map((point) => ({
      key: String(point.recordingId),
      value: pick(point),
      valueLabel: `${pick(point)}${unit}`,
      label: point.label,
    }));
  const toLinePoints = (
    pick: (point: (typeof points)[number]) => number,
    unit: string,
  ) =>
    points.map((point) => ({
      key: String(point.recordingId),
      value: pick(point),
      valueLabel: `${Math.round(pick(point))}${unit}`,
      label: point.label,
    }));
  const ranked = (pick: (point: (typeof points)[number]) => number) =>
    [...points]
      .filter((point) => pick(point) > 0)
      .sort((left, right) => pick(right) - pick(left))
      .slice(0, RANKING_LIMIT);
  const selectedKey = String(selectedRecordingId);
  const overMemberCount = points.filter(
    ({ speakerCount }) => speakerCount > memberCount,
  ).length;

  return (
    <>
      <ChartCard
        title="전체 회의 숫자"
        status="ready"
        method="30초 이상이고 문서가 나온 녹음을 회의로 세었어요"
        dataFields="녹음 길이, 문서 본문"
        span={12}
      >
        <StatRow>
          <StatTile value={points.length} label="회의" />
          <StatTile value={formatMinutes(totalSeconds)} label="회의 시간" />
          <StatTile
            value={sum(({ documentCount }) => documentCount)}
            label="주제별 문서"
          />
          <StatTile
            value={sum(({ decisionCount }) => decisionCount)}
            label="결정"
          />
          <StatTile
            value={sum(({ undecidedCount }) => undecidedCount)}
            label="미결정"
          />
          <StatTile value={sum(({ todoCount }) => todoCount)} label="할 일" />
        </StatRow>
      </ChartCard>

      <ChartCard
        title="회의 길이 추이"
        status="ready"
        method="회의마다 녹음 길이를 시간 순서로 놓았어요. 진한 막대가 지금 고른 회의예요"
        dataFields="녹음 길이"
        table={{
          headers: ["회의", "길이(분)"],
          rows: points.map(({ label, durationMinutes }) => [
            label,
            durationMinutes,
          ]),
        }}
      >
        <ColumnChart
          items={toColumns(({ durationMinutes }) => durationMinutes, "분")}
          selectedKey={selectedKey}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="회의별 결정 수"
        status="ready"
        method="회의에서 나온 문서의 결정 항목을 더했어요. 진한 막대가 지금 고른 회의예요"
        dataFields="문서 본문"
        table={{
          headers: ["회의", "결정"],
          rows: points.map(({ label, decisionCount }) => [
            label,
            decisionCount,
          ]),
        }}
      >
        <ColumnChart
          items={toColumns(({ decisionCount }) => decisionCount, "개")}
          selectedKey={selectedKey}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="결정 없이 끝난 회의 비율"
        status="ready"
        method="주마다 결정 항목이 없는 회의 ÷ 그 주의 회의 수"
        dataFields="문서 본문, 녹음 시각"
        table={{
          headers: ["주 시작", "회의", "결정 없음", "비율"],
          rows: weeks.map(({ weekStart, meetings, withoutDecision, ratio }) => [
            weekStart,
            meetings,
            withoutDecision,
            formatPercent(ratio),
          ]),
        }}
      >
        <ColumnChart
          items={weeks.map(
            ({ weekStart, meetings, withoutDecision, ratio }) => ({
              key: weekStart,
              value: ratio * 100,
              valueLabel: formatPercent(ratio),
              label: `${weekStart.slice(5)} 주, 회의 ${meetings}개 중 ${withoutDecision}개`,
            }),
          )}
          formatTick={(value) => `${Math.round(value)}%`}
          startLabel={weeks[0]?.weekStart.slice(5)}
          endLabel={weeks[weeks.length - 1]?.weekStart.slice(5)}
        />
      </ChartCard>

      <ChartCard
        title="쌓인 미결정"
        status="ready"
        method="회의 순서대로 미결정 항목 수를 더했어요. 나중에 결정된 항목을 빼지 않은 누적값이에요"
        dataFields="문서 본문"
      >
        <LineChart
          isArea
          points={toLinePoints(({ undecidedTotal }) => undecidedTotal, "개")}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="회의별 화자 번호 수"
        status="ready"
        method={`회의마다 나온 화자 번호 수예요. 멤버 ${memberCount}명보다 많은 회의가 ${points.length}개 중 ${overMemberCount}개예요. 손님이 없었다면 한 사람이 여러 번호로 나뉜 회의예요`}
        dataFields="전사 구간의 화자 번호, 멤버 수"
        table={{
          headers: ["회의", "화자 번호 수"],
          rows: points.map(({ label, speakerCount }) => [label, speakerCount]),
        }}
      >
        <ColumnChart
          items={toColumns(({ speakerCount }) => speakerCount, "개")}
          selectedKey={selectedKey}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="말 주고받기 추이"
        status="speaker"
        method="회의마다 시간당 화자가 바뀐 횟수를 놓았어요"
        dataFields="전사 구간의 화자 번호"
      >
        <LineChart
          points={toLinePoints(({ switchesPerHour }) => switchesPerHour, "번")}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="회의당 문서 수"
        status="ready"
        method="회의에서 나온 주제별 문서 수"
        dataFields="문서 목록"
      >
        <ColumnChart
          items={toColumns(({ documentCount }) => documentCount, "개")}
          selectedKey={selectedKey}
          {...edgeLabels}
        />
      </ChartCard>

      <ChartCard
        title="회의 달력"
        status="ready"
        method="한국 시간으로 회의를 시작한 날에 회의 수를 표시했어요. 칸이 진할수록 많아요"
        dataFields="녹음 시작 시각"
      >
        <MeetingCalendar calendar={calendar} />
      </ChartCard>

      <ChartCard
        title="요일과 시간대"
        status="ready"
        method="회의를 시작한 요일과 시를 세었어요. 칸이 진할수록 많아요"
        dataFields="녹음 시작 시각"
      >
        <WeekdayHourHeatmap cells={weekdayHours} />
      </ChartCard>

      <ChartCard
        title="되풀이 주제"
        status="ready"
        method="같은 주제 폴더가 나온 회의 수를 많은 순서로 12개까지 놓았어요"
        dataFields="문서의 주제 폴더"
      >
        <HorizontalBars
          items={repeatedFolders.map(({ folderName, meetings, documents }) => ({
            key: folderName,
            label: folderName,
            value: meetings,
            valueLabel: `${meetings}회`,
            detail: `문서 ${documents}개`,
          }))}
        />
      </ChartCard>

      <ChartCard
        title="가장 많이 열린 회의"
        status="ready"
        method="녹음 문서와 주제별 문서가 열린 횟수를 회의별로 더했어요. 우리 팀이 연 횟수예요"
        dataFields="PostHog 화면 열람 기록"
      >
        <HorizontalBars
          items={ranked(({ viewTotal }) => viewTotal).map(
            ({ recordingId, label, viewTotal }) => ({
              key: String(recordingId),
              label,
              value: viewTotal,
              valueLabel: `${viewTotal}회`,
            }),
          )}
        />
      </ChartCard>

      <ChartCard
        title="탐색 근거로 쓰인 회의"
        status="ready"
        method="탐색 답변의 근거로 쓰인 문서 수를 회의별로 더했어요"
        dataFields="탐색 근거 기록"
      >
        <HorizontalBars
          items={ranked(({ searchEvidenceTotal }) => searchEvidenceTotal).map(
            ({ recordingId, label, searchEvidenceTotal }) => ({
              key: String(recordingId),
              label,
              value: searchEvidenceTotal,
              valueLabel: `${searchEvidenceTotal}회`,
            }),
          )}
        />
      </ChartCard>
    </>
  );
};

export default WorkspaceSection;
