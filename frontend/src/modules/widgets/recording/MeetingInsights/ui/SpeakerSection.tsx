import { useMemo } from "react";

import { createSpeakerColorMap } from "../constants/chartColors";
import type { SpeakerMetrics } from "../types/metrics";
import {
  formatClock,
  formatMinutes,
  formatPercent,
  formatSeconds,
} from "../utils/formatMetric";
import ChartCard from "./ChartCard";
import ConversationGraph from "./ConversationGraph";
import DonutChart from "./DonutChart";
import HorizontalBars from "./HorizontalBars";
import Meter from "./Meter";
import PhaseBars from "./PhaseBars";
import SpeakerTimeline from "./SpeakerTimeline";
import StatTile, { StatRow } from "./StatTile";

interface SpeakerSectionProps {
  metrics: SpeakerMetrics;
  durationSeconds: number;
  memberCount: number;
}

const SPEAKER_DATA = "전사 구간의 화자 번호와 시각";

/** 화자 번호로 계산한 값. 번호가 맞아야 의미가 있어서 품질 카드를 맨 앞에 둬요 */
const SpeakerSection = ({
  metrics,
  durationSeconds,
  memberCount,
}: SpeakerSectionProps) => {
  const {
    groups,
    speakerCount,
    shortSpeakerCount,
    unknownRatio,
    topRatio,
    balance,
    switchCount,
    switchesPerHour,
    longestTurn,
    links,
    phases,
  } = metrics;
  const colorByKey = useMemo(() => createSpeakerColorMap(groups), [groups]);
  const totalSeconds = groups.reduce((sum, { seconds }) => sum + seconds, 0);
  const respondingGroups = groups.flatMap((group) =>
    group.medianResponseSeconds === null
      ? []
      : [{ ...group, medianResponseSeconds: group.medianResponseSeconds }],
  );

  return (
    <>
      <ChartCard
        title="화자 번호 품질"
        status="ready"
        method="화자 번호 수를 멤버 수와 비교했어요. PR #978 실험에서 녹음 5개의 화자 정확도는 46.9%에서 96.5%까지 벌어졌어요"
        dataFields="전사 구간의 화자 번호, 멤버 수"
        span={12}
      >
        <StatRow>
          <StatTile
            value={speakerCount}
            label="이 회의의 화자 번호"
            note={`워크스페이스 멤버 ${memberCount}명`}
          />
          <StatTile
            value={shortSpeakerCount}
            label="20초 미만 화자"
            note="잘못 나뉜 번호일 수 있어요"
          />
          <StatTile value={formatPercent(unknownRatio)} label="화자 미상" />
          <StatTile
            value="회의마다 달라요"
            label="참여자 번호의 뜻"
            note="사람과 연결되어 있지 않아요"
          />
        </StatRow>
      </ChartCard>

      <ChartCard
        title="말 차례 타임라인"
        status="speaker"
        method="참여자별 줄에 말한 구간을 시간 순서로 칠했어요. 많이 말한 7명 밖은 「기타」로 묶어요"
        dataFields={SPEAKER_DATA}
        span={12}
      >
        <SpeakerTimeline
          groups={groups}
          colorByKey={colorByKey}
          durationSeconds={durationSeconds}
        />
      </ChartCard>

      <ChartCard
        title="발화 비율"
        status="speaker"
        method="참여자별 말한 시간 ÷ 전체 말한 시간"
        dataFields={SPEAKER_DATA}
        span={6}
        table={{
          headers: ["참여자", "말한 시간", "비율"],
          rows: groups.map(({ label, seconds, ratio }) => [
            label,
            formatMinutes(seconds),
            formatPercent(ratio),
          ]),
        }}
      >
        <DonutChart
          items={groups.map(({ key, label, ratio }) => ({
            key,
            label,
            ratio,
            valueLabel: formatPercent(ratio),
            color: colorByKey.get(key) ?? "",
          }))}
          centerValue={formatMinutes(totalSeconds)}
          centerLabel="전체 말한 시간"
        />
      </ChartCard>

      <ChartCard
        title="대화 연결 그림"
        status="speaker"
        method="한 사람이 끝난 뒤 바로 다음 사람이 말한 횟수를 선 굵기로, 말한 시간을 원 크기로 그렸어요"
        dataFields={SPEAKER_DATA}
        span={6}
        table={{
          headers: ["앞사람", "뒷사람", "횟수"],
          rows: links.map(({ from, to, count }) => [from, to, count]),
        }}
      >
        <ConversationGraph
          groups={groups}
          links={links}
          colorByKey={colorByKey}
        />
      </ChartCard>

      <ChartCard
        title="가장 많이 말한 사람"
        status="speaker"
        method="1위 참여자의 말한 시간 ÷ 전체 말한 시간"
        dataFields={SPEAKER_DATA}
        span={3}
      >
        <StatTile value={formatPercent(topRatio)} label="1위의 비율" />
        <Meter
          ratio={topRatio}
          label={`1위의 비율 ${formatPercent(topRatio)}`}
        />
      </ChartCard>

      <ChartCard
        title="참여 균형"
        status="speaker"
        method={`20초 이상 말한 ${balance.countedSpeakers}명으로 계산했어요. 말하지 않은 사람은 알 수 없어서 들어가지 않아요`}
        dataFields={SPEAKER_DATA}
        span={3}
      >
        <StatRow>
          <StatTile
            value={balance.gini.toFixed(2)}
            label="지니 계수"
            note="0이면 고르게 말했어요"
          />
          <StatTile
            value={`${Math.round(balance.gapFromEven * 100)}%p`}
            label="1/N과의 차이"
          />
        </StatRow>
      </ChartCard>

      <ChartCard
        title="말 주고받기"
        status="speaker"
        method="화자가 바뀐 횟수 ÷ 회의 시간(시간당)"
        dataFields={SPEAKER_DATA}
        span={3}
      >
        <StatTile
          value={`${switchCount}번`}
          label="화자가 바뀐 횟수"
          note={`시간당 ${Math.round(switchesPerHour)}번`}
        />
      </ChartCard>

      <ChartCard
        title="가장 긴 연속 발화"
        status="speaker"
        method="화자가 바뀌지 않고 이어진 구간의 말한 시간을 더했어요"
        dataFields={SPEAKER_DATA}
        span={3}
      >
        {longestTurn !== null && (
          <StatTile
            value={formatSeconds(longestTurn.seconds)}
            label={longestTurn.label}
            note={`${formatClock(longestTurn.startMillis)}부터`}
          />
        )}
      </ChartCard>

      <ChartCard
        title="회의 앞, 중간, 끝의 발화 비율"
        status="speaker"
        method="회의를 시간으로 삼등분해 구간마다 말한 시간의 비율을 계산했어요"
        dataFields={SPEAKER_DATA}
        span={12}
      >
        <PhaseBars phases={phases} groups={groups} colorByKey={colorByKey} />
      </ChartCard>

      <ChartCard
        title="사람별 말 속도"
        status="speaker"
        method="글자 수 ÷ 말한 분. 영어권 기준값은 쓰지 않아요"
        dataFields={`${SPEAKER_DATA}, 전사 글`}
        span={6}
      >
        <HorizontalBars
          items={groups.map(({ key, label, charactersPerMinute }) => ({
            key,
            label,
            value: charactersPerMinute,
            valueLabel: `분당 ${Math.round(charactersPerMinute)}자`,
            color: colorByKey.get(key),
          }))}
        />
      </ChartCard>

      <ChartCard
        title="응답 간격"
        status="speaker"
        method="앞사람이 끝난 뒤 말을 시작하기까지 걸린 시간의 가운데값. 전사 구간의 경계가 붙어 있으면 0초로 나와서 실제 간격보다 짧아요"
        dataFields={SPEAKER_DATA}
        span={6}
      >
        <HorizontalBars
          items={respondingGroups.map(
            ({ key, label, medianResponseSeconds }) => ({
              key,
              label,
              value: medianResponseSeconds,
              valueLabel: `${medianResponseSeconds.toFixed(1)}초`,
              color: colorByKey.get(key),
            }),
          )}
        />
      </ChartCard>

      <ChartCard
        title="사람별 문장 수"
        status="speaker"
        method="문장 부호로 나눈 문장 수"
        dataFields={`${SPEAKER_DATA}, 전사 글`}
        span={6}
      >
        <HorizontalBars
          items={groups.map(({ key, label, sentenceCount }) => ({
            key,
            label,
            value: sentenceCount,
            valueLabel: `${sentenceCount}문장`,
            color: colorByKey.get(key),
          }))}
        />
      </ChartCard>

      <ChartCard
        title="사람별 질문 수"
        status="speaker"
        method="물음표로 끝나는 5글자 이상 문장 수"
        dataFields={`${SPEAKER_DATA}, 전사 글`}
        span={6}
      >
        <HorizontalBars
          items={groups.map(({ key, label, questionCount }) => ({
            key,
            label,
            value: questionCount,
            valueLabel: `${questionCount}개`,
            color: colorByKey.get(key),
          }))}
        />
      </ChartCard>
    </>
  );
};

export default SpeakerSection;
