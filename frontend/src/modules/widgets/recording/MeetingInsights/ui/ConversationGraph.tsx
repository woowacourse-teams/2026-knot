import { useTheme } from "@emotion/react";
import styled from "@emotion/styled";

import { useChartTooltip } from "../context/chartTooltipContext";
import type { SpeakerGroup, SpeakerMetrics } from "../types/metrics";

interface ConversationGraphProps {
  groups: SpeakerGroup[];
  links: SpeakerMetrics["links"];
  colorByKey: Map<string, string>;
}

const WIDTH = 460;
const HEIGHT = 360;
const CENTER = { x: WIDTH / 2, y: HEIGHT / 2 };
const ORBIT = 125;
const MIN_NODE_RADIUS = 9;
const NODE_RADIUS_SCALE = 38;
const MAX_LINK_WIDTH = 9;
const LABEL_GAP = 14;

/** 양쪽 방향 횟수를 한 선으로 합쳐요 */
const mergeDirections = (links: SpeakerMetrics["links"]) => {
  const pairs = new Map<
    string,
    { left: string; right: string; forward: number; backward: number }
  >();

  links.forEach(({ from, to, count }) => {
    const [left, right] = [from, to].sort();
    const key = `${left}-${right}`;
    const pair = pairs.get(key) ?? { left, right, forward: 0, backward: 0 };

    if (from === left) pair.forward += count;
    else pair.backward += count;

    pairs.set(key, pair);
  });

  return [...pairs.values()];
};

/** 원 크기는 발화 시간, 선 굵기는 서로 이어 말한 횟수예요 */
const ConversationGraph = ({
  groups,
  links,
  colorByKey,
}: ConversationGraphProps) => {
  const theme = useTheme();
  const { bindTooltip } = useChartTooltip();
  const nodes = groups.map((group, index) => {
    const angle = (index / groups.length) * 2 * Math.PI - Math.PI / 2;

    return {
      ...group,
      x: CENTER.x + Math.cos(angle) * ORBIT,
      y: CENTER.y + Math.sin(angle) * ORBIT,
      radius: MIN_NODE_RADIUS + Math.sqrt(group.ratio) * NODE_RADIUS_SCALE,
    };
  });
  const nodeByKey = new Map(nodes.map((node) => [node.key, node]));
  const pairs = mergeDirections(links);
  const maxCount = Math.max(
    ...pairs.map(({ forward, backward }) => forward + backward),
    1,
  );

  return (
    <Graph
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label="참여자 사이에 이어 말한 횟수"
    >
      {pairs.map(({ left, right, forward, backward }) => {
        const from = nodeByKey.get(left);
        const to = nodeByKey.get(right);

        if (from === undefined || to === undefined) return null;

        return (
          <line
            key={`${left}-${right}`}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            stroke={theme.neutral[300]}
            strokeWidth={1 + ((forward + backward) / maxCount) * MAX_LINK_WIDTH}
            strokeLinecap="round"
            {...bindTooltip(
              {
                value: `${forward + backward}회`,
                label: `${from.label}, ${to.label}`,
                detail: `${from.label} 뒤에 ${to.label} ${forward}회, 반대로 ${backward}회`,
              },
              { isFocusable: false },
            )}
          />
        );
      })}
      {nodes.map(({ key, label, x, y, radius, ratio }) => (
        <g key={key}>
          <circle
            cx={x}
            cy={y}
            r={radius}
            fill={colorByKey.get(key)}
            stroke={theme.neutral[0]}
            strokeWidth={2}
            {...bindTooltip({
              value: `${Math.round(ratio * 100)}%`,
              label,
              color: colorByKey.get(key),
            })}
          />
          <NodeLabel x={x} y={y + radius + LABEL_GAP} textAnchor="middle">
            {label}
          </NodeLabel>
        </g>
      ))}
    </Graph>
  );
};

export default ConversationGraph;

const Graph = styled.svg`
  display: block;
  width: 100%;
  max-width: 28.75rem; /* 460px */
  height: auto;
  margin: 0 auto;
  overflow: visible;
`;

const NodeLabel = styled.text`
  ${({ theme }) => theme.text.caption01}
  fill: ${({ theme }) => theme.neutral[700]};
`;
