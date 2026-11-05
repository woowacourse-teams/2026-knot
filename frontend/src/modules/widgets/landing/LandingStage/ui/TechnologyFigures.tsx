import { keyframes } from "@emotion/react";
import styled from "@emotion/styled";
import type { ReactNode } from "react";

/**
 * 기술 소개 카드의 도식. 글로 설명한 내용을 한눈에 보여 주는 장식이라 화면 낭독기에서는 숨겨요.
 * 장면이 보일 때만 움직이고, 움직임 줄이기를 켜면 멈춘 그림으로 보여요.
 */
interface FigureProps {
  isOn: boolean;
}

/** 가운데를 기준으로 세로 막대를 늘어놓은 작은 파형 */
function Bars({ x, y, heights, gap = 6, width = 3 }: { x: number; y: number; heights: number[]; gap?: number; width?: number }) {
  const start = x - ((heights.length - 1) * gap) / 2;
  return (
    <>
      {heights.map((height, index) => (
        <rect
          key={index}
          x={start + index * gap - width / 2}
          y={y - height / 2}
          width={width}
          height={height}
          rx={width / 2}
        />
      ))}
    </>
  );
}

/** 0~1 사이에서 매번 같은 값을 내는 의사 난수. 파형 모양을 고정하려고 써요. */
const wave = (index: number) => Math.abs(Math.sin(index * 1.7) * Math.cos(index * 0.37));

function Person({ x, y, className }: { x: number; y: number; className: string }) {
  return (
    <g className={className}>
      <circle cx={x} cy={y - 9} r={7} />
      <path d={`M${x - 13} ${y + 14} C${x - 13} ${y + 2} ${x - 7} ${y - 1} ${x} ${y - 1} C${x + 7} ${y - 1} ${x + 13} ${y + 2} ${x + 13} ${y + 14} Z`} />
    </g>
  );
}

function Doc({ x, y, className, children }: { x: number; y: number; className: string; children?: ReactNode }) {
  return (
    <g className={className}>
      <path d={`M${x} ${y} h14 l8 8 v20 a2 2 0 0 1 -2 2 h-18 a2 2 0 0 1 -2 -2 v-26 a2 2 0 0 1 2 -2 z`} />
      {children}
    </g>
  );
}

function Server({ x, y, isFilled }: { x: number; y: number; isFilled: boolean }) {
  return (
    <g className={isFilled ? "fill" : "line"}>
      {[0, 30, 60].map((offset) => (
        <g key={offset}>
          <rect x={x} y={y + offset} width={86} height={24} rx={8} />
          <circle className={isFilled ? "inverse" : "lineDot"} cx={x + 15} cy={y + offset + 12} r={3.5} />
          <path
            className={isFilled ? "inverseLine" : "line"}
            d={`M${x + 50} ${y + offset + 12} h22`}
            strokeLinecap="round"
          />
        </g>
      ))}
    </g>
  );
}

function Check({ x, y }: { x: number; y: number }) {
  return (
    <g>
      <circle className="badge" cx={x} cy={y} r={15} />
      <path className="checkMark" d={`M${x - 6} ${y} l4 4.5 l8.5 -9`} />
    </g>
  );
}

function Arrow({ from, to, y, className = "arrow" }: { from: number; to: number; y: number; className?: string }) {
  return <path className={className} d={`M${from} ${y} H${to} M${to - 7} ${y - 7} L${to} ${y} L${to - 7} ${y + 7}`} />;
}

/** 녹음 01. 5초 조각이 차례로 저장소에 쌓여요. */
export function ChunkSaveFigure({ isOn }: FigureProps) {
  const patterns = [
    [10, 22, 34, 22, 10, 26, 14],
    [12, 18, 30, 16, 28, 18, 10],
    [8, 20, 32, 24, 12, 30, 16],
  ];
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 130" aria-hidden>
      {patterns.map((heights, index) => (
        <g key={index} className="chunk" style={{ animationDelay: `${index * 0.45}s` }}>
          <rect className="box" x={8 + index * 112} y={10} width={100} height={92} rx={10} />
          <g className="fill">
            <Bars x={58 + index * 112} y={46} heights={heights} />
          </g>
          <text className="label" x={58 + index * 112} y={88} textAnchor="middle">
            5s
          </text>
        </g>
      ))}
      <Arrow from={350} to={378} y={56} />
      <Server x={386} y={14} isFilled />
      <Check x={466} y={92} />
    </Figure>
  );
}

/** 녹음 02. 녹음이 이어지는 동안 2분마다 구간을 잘라 바로 전사해요. */
export function SegmentTranscribeFigure({ isOn }: FigureProps) {
  const heights = Array.from({ length: 84 }, (_, index) => 6 + wave(index) * 44);
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 130" aria-hidden>
      <g className="fill">
        {heights.map((height, index) => (
          <rect key={index} x={14 + index * 5.2} y={44 - height / 2} width={2.4} height={height} rx={1.2} />
        ))}
      </g>
      {[
        [12, 146],
        [148, 282],
        [284, 418],
      ].map(([from, to], index) => (
        <g key={from} className="segment" style={{ animationDelay: `${index * 1.6}s` }}>
          <rect x={from} y={92} width={to - from} height={20} rx={10} />
          <text className="small" x={(from + to) / 2} y={106} textAnchor="middle">
            2min
          </text>
        </g>
      ))}
      <rect className="pending" x={420} y={92} width={48} height={20} rx={10} />
      <g className="playhead">
        <path className="lineStrong" d="M440 4 V124" />
      </g>
    </Figure>
  );
}

/** 녹음 03. 앞 구간의 마지막 10초를 겹쳐 넣고, 나뉜 화자를 한 사람으로 이어요. */
export function SpeakerLinkFigure({ isOn }: FigureProps) {
  const clip = Array.from({ length: 22 }, (_, index) => 4 + wave(index + 3) * 16);
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 170" aria-hidden>
      <text className="small" x={170} y={14} textAnchor="middle">
        0–120s
      </text>
      <rect className="clip" x={96} y={20} width={148} height={30} rx={5} />
      <g className="fill">
        <Bars x={170} y={35} heights={clip} gap={6} width={2.2} />
      </g>

      <text className="small" x={318} y={30} textAnchor="middle">
        110–240s
      </text>
      <rect className="clipSoft" x={226} y={36} width={148} height={30} rx={5} />
      <g className="softFill">
        <Bars x={300} y={51} heights={[...clip].reverse()} gap={6} width={2.2} />
      </g>

      <rect className="overlap" x={226} y={20} width={18} height={46} rx={3} />
      <path className="line" d="M235 70 V80" />
      <text className="small" x={235} y={94} textAnchor="middle">
        10s
      </text>

      <Person x={150} y={128} className="muted" />
      <Person x={320} y={128} className="softFill" />
      <Person x={235} y={150} className="fill" />
      <path className="arrow link" d="M166 132 L212 148 M203 151 L212 148 L206 141" />
      <path className="arrow link" d="M304 132 L258 148 M264 141 L258 148 L267 151" />
    </Figure>
  );
}

/** 녹음 04. 연결이 끊긴 동안 기기에 모은 소리를 연결이 돌아오면 보내요. */
export function OfflineResendFigure({ isOn }: FigureProps) {
  return (
    <Figure $isOn={isOn} viewBox="0 0 520 140" aria-hidden>
      <rect className="line" x={14} y={14} width={116} height={74} rx={6} />
      <path className="line" d="M2 98 H142" strokeLinecap="round" />
      <g className="fill">
        <Bars x={72} y={51} heights={[8, 16, 28, 18, 32, 20, 10, 22, 12]} gap={6} width={2.6} />
      </g>
      <g className="db">
        <path d="M118 96 v26 a16 6 0 0 0 32 0 v-26" />
        <ellipse cx={134} cy={96} rx={16} ry={6} />
        <path d="M118 105 a16 6 0 0 0 32 0 M118 114 a16 6 0 0 0 32 0" />
      </g>

      {[164, 194, 224].map((x, index) => (
        <Doc key={x} x={x} y={26} className="doc stored">
          <rect className="fill" x={x + 5} y={42 - 3 - index} width={2} height={8 + index * 2} rx={1} />
          <rect className="fill" x={x + 10} y={42 - 6} width={2} height={12} rx={1} />
        </Doc>
      ))}
      <Arrow from={160} to={250} y={74} />

      <g className="signal">
        <path className="lineStrong" d="M262 52 a40 40 0 0 1 56 0" />
        <path className="lineStrong" d="M272 62 a26 26 0 0 1 36 0" />
        <circle className="fill" cx={290} cy={74} r={5} />
      </g>

      {[326, 356, 386].map((x, index) => (
        <Doc key={x} x={x} y={26} className="doc resend" />
      ))}
      <Arrow from={322} to={412} y={74} />
      <Server x={424} y={18} isFilled={false} />
      <Check x={506} y={96} />
    </Figure>
  );
}

/** 탐색 01. 기존 경로는 LLM과 MCP를 오가며 문서를 찾고, Fast Path는 한 번의 검색으로 바로 답해요. */
export function PathCompareFigure({ isOn }: FigureProps) {
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 160" aria-hidden>
      <text className="small mutedText" x={0} y={18}>
        기존 Agent/MCP
      </text>
      <path className="track" d="M40 54 H440" />
      <circle className="mutedDot slowDot" cx={40} cy={54} r={4} />
      <rect className="pill" x={20} y={40} width={56} height={28} rx={14} />
      <text className="small mutedText" x={48} y={58} textAnchor="middle">
        질문
      </text>
      <rect className="pill" x={140} y={40} width={64} height={28} rx={14} />
      <text className="small mutedText" x={172} y={58} textAnchor="middle">
        LLM
      </text>
      <rect className="pill" x={252} y={40} width={64} height={28} rx={14} />
      <text className="small mutedText" x={284} y={58} textAnchor="middle">
        MCP
      </text>
      <path className="loop" d="M178 38 C190 14 268 14 280 38 M272 32 L280 38 L284 29" />
      <path className="loop" d="M280 70 C268 94 190 94 178 70 M186 76 L178 70 L174 79" />
      <rect className="pill" x={384} y={40} width={56} height={28} rx={14} />
      <text className="small mutedText" x={412} y={58} textAnchor="middle">
        답
      </text>

      <text className="small inkText" x={0} y={110}>
        Fast Path
      </text>
      <path className="lineSoft" d="M40 140 H440" />
      <circle className="fill fastDot" cx={40} cy={140} r={4} />
      <rect className="pillAccent" x={20} y={126} width={56} height={28} rx={14} />
      <text className="small inkText" x={48} y={144} textAnchor="middle">
        질문
      </text>
      <rect className="fill" x={176} y={124} width={104} height={32} rx={16} />
      <text className="small inverseText" x={228} y={144} textAnchor="middle">
        문서 검색 1회
      </text>
      <rect className="pillAccent" x={384} y={126} width={56} height={28} rx={14} />
      <text className="small inkText" x={412} y={144} textAnchor="middle">
        답
      </text>
    </Figure>
  );
}

/** 탐색 02. 질문한 워크스페이스 안의 문서에서만 후보를 찾아요. */
export function WorkspaceSearchFigure({ isOn }: FigureProps) {
  const docs = [
    [52, 46],
    [108, 46],
    [164, 46],
    [52, 96],
    [108, 96],
    [164, 96],
  ];
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 150" aria-hidden>
      <rect className="boundary" x={12} y={14} width={300} height={128} rx={12} />
      <text className="small inkText" x={28} y={34}>
        우리 팀 워크스페이스
      </text>
      {docs.map(([x, y], index) => (
        <Doc key={index} x={x} y={y} className={index === 4 ? "fillDoc" : "doc"} />
      ))}
      <g className="lens">
        <circle className="lineStrong" cx={0} cy={0} r={18} />
        <path className="lineStrong" d="M13 13 L24 24" strokeLinecap="round" />
      </g>
      <text className="small inkText" x={232} y={118} textAnchor="middle">
        PostgreSQL
      </text>
      <g className="db">
        <path d="M216 64 v26 a16 6 0 0 0 32 0 v-26" />
        <ellipse cx={232} cy={64} rx={16} ry={6} />
        <path d="M216 73 a16 6 0 0 0 32 0 M216 82 a16 6 0 0 0 32 0" />
      </g>

      <rect className="boundaryMuted" x={340} y={14} width={128} height={128} rx={12} />
      <text className="small mutedText" x={404} y={34} textAnchor="middle">
        다른 워크스페이스
      </text>
      <Doc x={370} y={62} className="mutedDoc" />
      <Doc x={414} y={62} className="mutedDoc" />
      <Doc x={392} y={100} className="mutedDoc" />
    </Figure>
  );
}

/** 탐색 03. 검색어는 다듬어 찾고, 답은 사용자가 쓴 원래 질문으로 만들어요. */
export function NormalizeFigure({ isOn }: FigureProps) {
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 170" aria-hidden>
      <rect className="bubble" x={8} y={8} width={300} height={36} rx={18} />
      <text className="small darkText" x={158} y={31} textAnchor="middle">
        탈퇴한 사용자 게시글은 어떻게 하기로 했지?
      </text>

      <path className="arrow" d="M60 46 V78 H82 M75 71 L82 78 L75 85" />
      <text className="small mutedText" x={20} y={66}>
        검색어
      </text>
      {[
        ["탈퇴", 92],
        ["사용자", 152],
        ["게시글", 226],
      ].map(([word, x], index) => (
        <g key={word} className="chip" style={{ animationDelay: `${index * 0.25}s` }}>
          <rect className="softFill" x={Number(x)} y={66} width={word === "탈퇴" ? 52 : 66} height={24} rx={12} />
          <text className="small inkText" x={Number(x) + (word === "탈퇴" ? 26 : 33)} y={82} textAnchor="middle">
            {word}
          </text>
        </g>
      ))}
      <Arrow from={300} to={364} y={78} />
      <g className="db">
        <path d="M376 66 v24 a20 7 0 0 0 40 0 v-24" />
        <ellipse cx={396} cy={66} rx={20} ry={7} />
      </g>
      <text className="small inkText" x={396} y={112} textAnchor="middle">
        문서 근거
      </text>

      <path className="dash" d="M300 26 H446 V128" />
      <path className="arrow" d="M396 118 V132" />
      <rect className="fill" x={300} y={134} width={176} height={30} rx={15} />
      <text className="small inverseText" x={388} y={154} textAnchor="middle">
        원래 질문으로 답변
      </text>
    </Figure>
  );
}

/** 탐색 04. 검색 자리만 바꾸고, 의도 분류부터 실시간 전송까지는 그대로 이어요. */
export function PipelineFigure({ isOn }: FigureProps) {
  const steps = ["의도 분류", "문서 검색", "근거 검증", "답변 저장", "SSE 전송"];
  return (
    <Figure $isOn={isOn} viewBox="0 0 480 150" aria-hidden>
      <path className="track" d="M40 50 H440" />
      <circle className="fill pipeDot" cx={47} cy={50} r={4} />
      {steps.map((step, index) => {
        const x = 4 + index * 96;
        const isSearch = index === 1;
        return (
          <g key={step}>
            <rect className={isSearch ? "fill" : "pillAccent"} x={x} y={34} width={86} height={32} rx={16} />
            <text className={`small ${isSearch ? "inverseText" : "inkText"}`} x={x + 43} y={54} textAnchor="middle">
              {step}
            </text>
          </g>
        );
      })}
      <text className="small inkText" x={143} y={22} textAnchor="middle">
        Fast Path
      </text>

      <path className="dash" d="M147 68 V108" />
      <rect className="pillDashed" x={92} y={108} width={110} height={30} rx={15} />
      <text className="small mutedText" x={147} y={127} textAnchor="middle">
        Agent/MCP 선택
      </text>
    </Figure>
  );
}

const chunkPulse = keyframes`
  0%, 100% { fill-opacity: 0; }
  20%, 60% { fill-opacity: 1; }
`;

const sweep = keyframes`
  from { transform: translateX(-428px); }
  to { transform: translateX(28px); }
`;

const segmentFill = keyframes`
  0%, 8% { fill-opacity: 0; }
  20%, 100% { fill-opacity: 1; }
`;

const signalBlink = keyframes`
  0%, 35% { opacity: 0.2; }
  45%, 100% { opacity: 1; }
`;

const resendIn = keyframes`
  0%, 45% { opacity: 0; transform: translateX(-12px); }
  60%, 100% { opacity: 1; transform: translateX(0); }
`;

const travel = (distance: number) => keyframes`
  from { transform: translateX(0); }
  to { transform: translateX(${distance}px); }
`;

const lensMove = keyframes`
  0% { transform: translate(64px, 62px); }
  30% { transform: translate(176px, 62px); }
  55%, 100% { transform: translate(120px, 112px); }
`;

const chipIn = keyframes`
  0%, 10% { opacity: 0; transform: translateY(6px); }
  30%, 100% { opacity: 1; transform: translateY(0); }
`;

const Figure = styled.svg<{ $isOn: boolean }>`
  /* 랜딩의 다른 장면처럼 무채색으로 그리고, 눈여겨볼 곳(--point)에만 accent를 써요. */
  --ink: ${({ theme }) => theme.neutral[700]};
  --ink-strong: ${({ theme }) => theme.neutral[800]};
  --soft: ${({ theme }) => theme.neutral[100]};
  --soft-line: ${({ theme }) => theme.neutral[300]};
  --point: ${({ theme }) => theme.sub.accent[500]};
  --muted: ${({ theme }) => theme.neutral[400]};
  --muted-soft: ${({ theme }) => theme.neutral[200]};

  font-family: inherit;

  * {
    animation-play-state: ${({ $isOn }) => ($isOn ? "running" : "paused")} !important;
  }

  .fill {
    fill: var(--ink);
  }

  .softFill {
    fill: var(--soft-line);
  }

  .muted {
    fill: var(--muted);
  }

  .line,
  .lineStrong,
  .arrow,
  .loop,
  .track,
  .lineSoft,
  .dash,
  .boundary,
  .boundaryMuted,
  .clip,
  .clipSoft,
  .box,
  .pill,
  .pillAccent,
  .pillDashed,
  .bubble,
  .db,
  .doc,
  .mutedDoc,
  .fillDoc {
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .line,
  .doc,
  .db {
    stroke: var(--ink);
    stroke-width: 2;
  }

  .db {
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .lineDot {
    fill: none;
    stroke: var(--ink);
    stroke-width: 2;
  }

  .lineStrong {
    stroke: var(--ink-strong);
    stroke-width: 3;
  }

  .arrow {
    stroke: var(--ink);
    stroke-width: 2.5;
  }

  .link {
    stroke-width: 2;
  }

  .box {
    stroke: var(--ink);
    stroke-width: 2.5;
  }

  .chunk .box {
    fill: var(--soft-line);
    fill-opacity: 0;
    animation: ${chunkPulse} 2.7s ease-in-out infinite;
    animation-delay: inherit;
  }

  .inverse {
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .inverseLine {
    stroke: ${({ theme }) => theme.neutral[0]};
    stroke-width: 3;
  }

  .badge {
    fill: ${({ theme }) => theme.neutral[0]};
    stroke: var(--point);
    stroke-width: 2.5;
  }

  .checkMark {
    fill: none;
    stroke: var(--point);
    stroke-width: 2.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .label {
    font-size: 15px;
    font-weight: 700;
    fill: var(--ink-strong);
  }

  .small {
    font-size: 11px;
    font-weight: 600;
    fill: var(--ink-strong);
  }

  .mutedText {
    fill: ${({ theme }) => theme.neutral[500]};
  }

  .inkText {
    fill: var(--ink-strong);
  }

  .inverseText {
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .darkText {
    fill: ${({ theme }) => theme.neutral[800]};
    font-weight: 500;
  }

  .segment rect {
    fill: var(--soft-line);
    fill-opacity: 0;
    stroke: var(--ink);
    stroke-width: 1.5;
    animation: ${segmentFill} 4.8s ease-out infinite;
    animation-delay: inherit;
  }

  .segment text {
    font-size: 9px;
  }

  .pending {
    fill: none;
    stroke: var(--soft-line);
    stroke-width: 1.5;
    stroke-dasharray: 4 3;
  }

  .playhead .lineStrong {
    stroke: var(--point);
  }

  .playhead {
    animation: ${sweep} 4.8s linear infinite;
  }

  .clip {
    stroke: var(--ink);
    stroke-width: 2;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .clipSoft {
    stroke: var(--soft-line);
    stroke-width: 2;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .overlap {
    fill: var(--point);
    fill-opacity: 0.15;
    stroke: var(--point);
    stroke-width: 1.5;
    stroke-dasharray: 3 2;
  }

  .doc.stored {
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .doc.resend {
    animation: ${resendIn} 3.2s ease-out infinite;
  }

  .signal {
    animation: ${signalBlink} 3.2s ease-in-out infinite;
  }

  .track {
    stroke: var(--muted-soft);
    stroke-width: 2;
  }

  .lineSoft {
    stroke: var(--soft-line);
    stroke-width: 2;
  }

  .pill {
    stroke: var(--muted);
    stroke-width: 1.5;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .pillAccent {
    stroke: var(--ink);
    stroke-width: 1.5;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .pillDashed {
    stroke: var(--muted);
    stroke-width: 1.5;
    stroke-dasharray: 4 3;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .loop {
    stroke: var(--muted);
    stroke-width: 1.5;
    stroke-dasharray: 4 3;
  }

  .dash {
    stroke: var(--soft-line);
    stroke-width: 1.5;
    stroke-dasharray: 4 3;
  }

  .mutedDot {
    fill: var(--muted);
  }

  .slowDot {
    animation: ${travel(400)} 7s linear infinite;
  }

  .fastDot,
  .pipeDot {
    fill: var(--point);
  }

  .fastDot {
    animation: ${travel(400)} 1.4s ease-in-out infinite;
  }

  .pipeDot {
    animation: ${travel(386)} 3s ease-in-out infinite;
  }

  .boundary {
    stroke: var(--ink);
    stroke-width: 1.5;
    stroke-dasharray: 6 4;
    fill: var(--soft);
    fill-opacity: 0.6;
  }

  .boundaryMuted {
    stroke: var(--muted);
    stroke-width: 1.5;
    stroke-dasharray: 6 4;
  }

  .mutedDoc {
    stroke: var(--muted);
    stroke-width: 1.5;
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .doc {
    fill: ${({ theme }) => theme.neutral[0]};
  }

  .fillDoc {
    fill: var(--point);
    stroke: var(--point);
    stroke-width: 2;
  }

  .lens {
    transform: translate(120px, 112px);
    animation: ${lensMove} 4s ease-in-out infinite alternate;
  }

  .bubble {
    fill: ${({ theme }) => theme.neutral[100]};
  }

  .chip {
    animation: ${chipIn} 3.6s ease-out infinite;
    animation-delay: inherit;
  }

  @media (prefers-reduced-motion: reduce) {
    * {
      animation: none !important;
    }

    .playhead {
      transform: none;
    }

    .lens {
      transform: translate(120px, 112px);
    }
  }
`;
