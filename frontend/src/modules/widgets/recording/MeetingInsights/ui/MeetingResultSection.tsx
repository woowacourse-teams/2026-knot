import type { MeetingInsightsMeeting } from "../types/dataset";
import type { MeetingMetrics } from "../types/metrics";
import { createMeetingOutline } from "../utils/createMeetingOutline";
import {
  formatClock,
  formatMinutes,
  formatPercent,
  formatSeconds,
} from "../utils/formatMetric";
import { getMeetingLabel } from "../utils/getMeetingLabel";
import ChartCard from "./ChartCard";
import HorizontalBars from "./HorizontalBars";
import LineChart from "./LineChart";
import MeetingMap from "./MeetingMap";
import Meter from "./Meter";
import OutcomeLists from "./OutcomeLists";
import QuestionStrip from "./QuestionStrip";
import StatTile, { StatRow } from "./StatTile";

interface MeetingResultSectionProps {
  meeting: MeetingInsightsMeeting;
  metrics: MeetingMetrics;
  memberCount: number;
}

/** 화자 번호와 관계없이 회의 한 건에서 보여 줄 수 있는 값 */
const MeetingResultSection = ({
  meeting,
  metrics,
  memberCount,
}: MeetingResultSectionProps) => {
  const outline = createMeetingOutline(meeting);
  const {
    durationSeconds,
    spokenSeconds,
    spokenRatio,
    resultCounts,
    topicCount,
    decidedTopicCount,
    decidedTopicRatio,
    density,
    longSilences,
    questions,
    dateMentionCount,
    numberMentionCount,
    frequentWords,
    confirmedTotal,
    missingEndCount,
  } = metrics;
  const confirmTarget = topicCount * memberCount;
  // 지금 멤버 수로 나눠서, 떠난 멤버의 확인이 있으면 1을 넘을 수 있어요
  const confirmRatio =
    confirmTarget === 0 ? 0 : Math.min(confirmedTotal / confirmTarget, 1);

  return (
    <>
      <ChartCard
        title="회의 길이와 말한 시간"
        status="ready"
        method={`말한 구간을 겹치지 않게 더한 시간 ÷ 녹음 시간${missingEndCount > 0 ? `. 끝 시각이 없는 구간 ${missingEndCount}개는 뺐어요` : ""}`}
        dataFields="녹음 길이, 전사 구간의 시작과 끝 시각"
        span={4}
      >
        <StatTile
          value={formatMinutes(durationSeconds)}
          label="회의 길이"
          note={`말한 시간 ${formatMinutes(spokenSeconds)}, 말이 없던 시간 ${formatMinutes(Math.max(durationSeconds - spokenSeconds, 0))}`}
        />
        <Meter
          ratio={spokenRatio}
          label={`말한 시간 ${formatPercent(spokenRatio)}`}
        />
      </ChartCard>

      <ChartCard
        title="회의에서 나온 결과"
        status="ready"
        method="주제별 문서의 결정, 보류, 미결정, 할 일 구역에 있는 항목 수를 더했어요"
        dataFields="문서 본문"
        span={8}
      >
        <StatRow>
          <StatTile value={resultCounts.decisions} label="결정" />
          <StatTile value={resultCounts.holds} label="보류" />
          <StatTile value={resultCounts.undecided} label="미결정" />
          <StatTile value={resultCounts.todos} label="할 일" />
          <StatTile value={topicCount} label="주제별 문서" />
        </StatRow>
      </ChartCard>

      <ChartCard
        title="결정이 나온 주제 비율"
        status="ready"
        method="결정 구역이 있는 문서 ÷ 전체 문서"
        dataFields="문서 본문"
        span={4}
      >
        <StatTile
          value={formatPercent(decidedTopicRatio)}
          label={`주제 ${topicCount}개 중 ${decidedTopicCount}개`}
        />
        <Meter
          ratio={decidedTopicRatio}
          label={`결정이 나온 주제 ${formatPercent(decidedTopicRatio)}`}
        />
      </ChartCard>

      <ChartCard
        title="문서 확인 현황"
        status="ready"
        method="문서를 확인한 횟수 ÷ (문서 수 × 지금 멤버 수). 확인 대상이 문서마다 달라 대략의 값이에요"
        dataFields="문서 확인 기록, 멤버 수"
        span={4}
      >
        <StatTile
          value={formatPercent(confirmRatio)}
          label={`${confirmTarget}번 중 ${confirmedTotal}번 확인`}
        />
        <Meter
          ratio={confirmRatio}
          label={`문서 확인 ${formatPercent(confirmRatio)}`}
        />
      </ChartCard>

      <ChartCard
        title="날짜와 수치 언급"
        status="ready"
        method="문장에서 날짜 표현(다음 주, 금요일, 11월 5일)과 단위가 붙은 숫자(3개, 20%)를 세었어요"
        dataFields="전사 글"
        span={4}
      >
        <StatRow>
          <StatTile value={dateMentionCount} label="날짜 언급" />
          <StatTile value={numberMentionCount} label="수치 언급" />
        </StatRow>
      </ChartCard>

      <ChartCard
        title="시간대별 말 밀도"
        status="ready"
        method="구간의 글자를 시간 비율대로 1분 칸에 나눠 담았어요. 구간이 길면 글자가 고르게 퍼져 보여요"
        dataFields="전사 구간의 시각과 글"
        span={12}
        table={{
          headers: ["분", "글자 수"],
          rows: density.map(({ minute, characters }) => [minute, characters]),
        }}
      >
        <LineChart
          isArea
          points={density.map(({ minute, characters }) => ({
            key: String(minute),
            value: characters,
            valueLabel: `${characters}자`,
            label: `${minute}분`,
          }))}
          startLabel="0분"
          endLabel={`${density.length}분`}
        />
      </ChartCard>

      <ChartCard
        title="질문 수와 위치"
        status="ready"
        method="물음표로 끝나는 5글자 이상 문장을 세었어요. 위치는 구간 시작 시각이에요"
        dataFields="전사 구간의 시각과 글"
        span={7}
      >
        <StatTile value={questions.length} label="질문" />
        <QuestionStrip
          questions={questions}
          durationSeconds={durationSeconds}
        />
      </ChartCard>

      <ChartCard
        title="긴 침묵"
        status="ready"
        method="15초 이상 말이 없던 구간을 긴 순서로 5개까지 보여 줘요"
        dataFields="전사 구간의 시작과 끝 시각"
        span={5}
      >
        {longSilences.length === 0 ? (
          <StatTile value="없음" label="15초 이상 침묵" />
        ) : (
          <HorizontalBars
            items={longSilences.map(({ startMillis, endMillis, seconds }) => ({
              key: String(startMillis),
              label: `${formatClock(startMillis)} ~ ${formatClock(endMillis)}`,
              value: seconds,
              valueLabel: formatSeconds(seconds),
            }))}
          />
        )}
      </ChartCard>

      <ChartCard
        title="자주 나온 말"
        status="ready"
        method="띄어쓰기로 나눠 조사를 떼고 말버릇을 뺀 뒤 세었어요. 형태소 분석이 아니라 대략의 값이에요"
        dataFields="전사 글"
        span={5}
      >
        <HorizontalBars
          items={frequentWords.map(({ word, count }) => ({
            key: word,
            label: word,
            value: count,
            valueLabel: `${count}회`,
          }))}
        />
      </ChartCard>

      <ChartCard
        title="정한 방향과 아직 결정하지 못한 것"
        status="ready"
        method={
          outline.source === "summary"
            ? "회의 전체 요약의 「정한 방향」과 「아직 결정하지 못한 것」을 읽었어요"
            : "요약이 없는 회의라 주제별 문서의 결정과 미결정 구역으로 대신했어요"
        }
        dataFields={
          outline.source === "summary" ? "회의 전체 요약" : "문서 본문"
        }
        span={7}
      >
        <OutcomeLists
          directions={outline.directions}
          undecided={outline.undecided}
        />
      </ChartCard>

      <ChartCard
        title="회의 구조 지도"
        status="ready"
        method={
          outline.source === "summary"
            ? "회의 전체 요약의 주제와 적용 방향을 가지로 펼쳤어요"
            : "요약이 없는 회의라 문서 제목과 결정 항목으로 가지를 만들었어요"
        }
        dataFields={
          outline.source === "summary" ? "회의 전체 요약" : "문서 제목과 본문"
        }
        span={12}
      >
        <MeetingMap
          rootLabel={outline.conclusion ?? getMeetingLabel(meeting)}
          topics={outline.topics}
        />
      </ChartCard>
    </>
  );
};

export default MeetingResultSection;
