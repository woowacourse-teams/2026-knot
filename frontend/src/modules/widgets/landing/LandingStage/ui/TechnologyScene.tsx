import { keyframes } from "@emotion/react";
import styled from "@emotion/styled";
import { type CSSProperties, type ReactNode, useEffect, useRef } from "react";

import SceneLayer, { focusRing, interactive, mobile } from "./SceneLayer";

interface SceneMove {
  label: string;
  onClick: () => void;
}

interface TechnologySceneProps {
  isOn: boolean;
  /** 제목 요소의 id. 장면 영역의 이름이 돼요. */
  titleId: string;
  /** 머리말의 번호와 이름. 「기술 소개 01 / 녹음과 전사」처럼 보여요. */
  eyebrow: { number: string; name: string };
  title: ReactNode;
  /** 제목 아래 수치 비교 묶음 */
  board: ReactNode;
  /** 개선 카드 묶음의 제목 */
  sectionTitle: string;
  /** 개선 카드 네 장 */
  children: ReactNode;
  source: { href: string; label: string };
  previous: SceneMove;
  next: SceneMove;
}

/**
 * 기술 소개 장면의 틀. 큰 제목, 수치 비교, 개선 카드 네 장 순서로 놓아요.
 *
 * 한 화면에 다 들어가지 않으면 안쪽을 먼저 스크롤하고, 끝에 닿으면 기존처럼 장면을 넘겨요(`data-landing-scroll`).
 * 입자는 이 장면에서 숨겨서 글과 도식을 읽기 쉽게 해요.
 */
export default function TechnologyScene({
  isOn,
  titleId,
  eyebrow,
  title,
  board,
  sectionTitle,
  children,
  source,
  previous,
  next,
}: TechnologySceneProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  // 장면에 다시 들어오면 제목부터 읽도록 안쪽 스크롤을 맨 위로 돌려요.
  useEffect(() => {
    if (isOn && containerRef.current) containerRef.current.scrollTop = 0;
  }, [isOn]);

  return (
    <Container ref={containerRef} isOn={isOn} role="region" aria-labelledby={titleId} data-landing-scroll tabIndex={isOn ? 0 : -1}>
      <Inner>
        <Head $isOn={isOn}>
          <Eyebrow>
            기술 소개 {eyebrow.number}
            <span aria-hidden>/</span>
            <strong>{eyebrow.name}</strong>
          </Eyebrow>
          <Title id={titleId}>{title}</Title>
        </Head>

        {board}

        <Divider />

        <SectionTitle>{sectionTitle}</SectionTitle>
        <Cards>{children}</Cards>

        <Footer>
          <Source href={source.href} target="_blank" rel="noreferrer">
            {source.label} <span aria-hidden>↗</span>
          </Source>
          <Navigation aria-label={`${eyebrow.name} 장면 이동`}>
            <MoveButton type="button" onClick={previous.onClick}>
              ← {previous.label}
            </MoveButton>
            <MoveButton type="button" onClick={next.onClick}>
              {next.label} →
            </MoveButton>
          </Navigation>
        </Footer>
        <ScrollCue type="button" tabIndex={-1} aria-hidden onClick={next.onClick}>
          계속 스크롤하면 다음 장으로 넘어가요
          <Mouse viewBox="0 0 32 44">
            <rect x="2" y="2" width="28" height="40" rx="14" />
            <circle cx="16" cy="13" r="2.5" />
          </Mouse>
        </ScrollCue>
      </Inner>
    </Container>
  );
}

interface StatItem {
  name: string;
  value: ReactNode;
  /** 값 아래의 작은 비교 글 */
  sub?: string;
  isHighlighted?: boolean;
}

interface StatBoardProps {
  isOn: boolean;
  heading: string;
  note: string;
  items: StatItem[];
  footnote: ReactNode;
}

/** 제목 아래의 수치 비교. 강조할 칸 하나만 「시작하기」 버튼과 같은 짙은 바탕으로 채워요. */
export function StatBoard({ isOn, heading, note, items, footnote }: StatBoardProps) {
  return (
    <Board $isOn={isOn}>
      <BoardHead>
        <h3>{heading}</h3>
        <span>{note}</span>
      </BoardHead>
      <Stats style={{ "--columns": items.length } as CSSProperties}>
        {items.map(({ name, value, sub, isHighlighted }) => (
          <Stat key={name} $isHighlighted={isHighlighted}>
            <dt>{name}</dt>
            <dd>
              <StatValue>{value}</StatValue>
              {sub && <StatSub>{sub}</StatSub>}
            </dd>
          </Stat>
        ))}
      </Stats>
      <BoardNote>{footnote}</BoardNote>
    </Board>
  );
}

interface TechCardProps {
  isOn: boolean;
  /** 카드 순서. 번호가 되고, 나타나는 시점을 차례로 늦춰요. */
  order: number;
  title: string;
  lead: string;
  figure: ReactNode;
  children?: ReactNode;
}

/** 개선 카드 한 장. 번호, 제목, 한 줄 설명, 도식, 결과 순서로 놓아요. */
export function TechCard({ isOn, order, title, lead, figure, children }: TechCardProps) {
  return (
    <Card $isOn={isOn} style={{ "--order": order + 2 } as CSSProperties}>
      <CardNumber>{String(order + 1).padStart(2, "0")}</CardNumber>
      <CardTitle>{title}</CardTitle>
      <CardLead>{lead}</CardLead>
      <CardFigure>{figure}</CardFigure>
      {children && <CardBody>{children}</CardBody>}
    </Card>
  );
}

/** 카드 도식 아래 굵은 한 줄 */
export const CardPoint = styled.p`
  ${({ theme }) => theme.text.heading04};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 카드 도식 아래 설명 글 */
export const CardText = styled.p`
  ${({ theme }) => theme.text.body01};
  line-height: 1.7;
  color: ${({ theme }) => theme.neutral[700]};
`;

/** 카드 맨 아래 굵은 결과 한 줄 */
export const CardOutcome = styled.p`
  ${({ theme }) => theme.text.body01};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[800]};
`;

/** 카드 안의 수치 결과 상자. 기준을 작게 적고 수치를 크게 적어요. */
export function CardResult({ basis, children }: { basis: string; children: ReactNode }) {
  return (
    <Result>
      <span>{basis}</span>
      <strong>{children}</strong>
    </Result>
  );
}

/** 장면에 들어설 때 아래에서 살짝 떠오르며 차례로 나타나요. `--order`만큼 늦게 시작해요. */
const reveal = ({ isOn }: { isOn: boolean }) => `
  opacity: ${isOn ? 1 : 0};
  transform: translateY(${isOn ? "0" : "1rem"});
  transition:
    opacity 0.6s ease calc(var(--order, 0) * 80ms + 120ms),
    transform 0.7s cubic-bezier(0.2, 0.8, 0.2, 1) calc(var(--order, 0) * 80ms + 120ms),
    border-color 0.3s,
    box-shadow 0.3s;

  @media (prefers-reduced-motion: reduce) {
    transform: none;
    transition: opacity 0.2s;
  }
`;

const Container = styled(SceneLayer)`
  position: absolute;
  inset: 4.75rem 0 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  mask-image: linear-gradient(to bottom, transparent 0, #000 1.5rem, #000 calc(100% - 2rem), transparent 100%);
  ${({ isOn }) => interactive({ isOn })};
  ${({ theme }) => focusRing(theme)};

  &:focus-visible {
    outline-offset: -3px;
  }

  ${mobile} {
    inset: 4rem 0 0;
    touch-action: pan-y;
  }
`;

const Inner = styled.div`
  display: flex;
  flex-direction: column;
  width: min(72rem, 100% - 14rem); /* 1152px. 오른쪽 장면 목록 자리를 비워요. */
  margin: 0 auto;
  /* 끝에 여유를 둬서 마지막 카드를 화면 가운데쯤에서 읽고, 다음 장면 안내를 볼 수 있게 해요. */
  padding: 1.5rem 0 8rem; /* 128px */

  ${mobile} {
    width: auto;
    margin: 0 1.25rem; /* 20px */
    padding: 1rem 0 5rem; /* 80px */
  }
`;

const Head = styled.div<{ $isOn: boolean }>`
  ${({ $isOn }) => reveal({ isOn: $isOn })};
  display: flex;
  flex-direction: column;
  gap: 1.25rem; /* 20px */
  margin-bottom: 3rem; /* 48px */

  ${mobile} {
    gap: 0.875rem;
    margin-bottom: 2rem;
  }
`;

const Eyebrow = styled.p`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.sub.accent[500]};

  &::before {
    content: "";
    width: 1.125rem; /* 18px */
    height: 1.5px;
    margin-right: 0.125rem;
    background-color: currentColor;
  }

  strong {
    font-weight: 600;
  }
`;

const Title = styled.h2`
  font-size: clamp(2.5rem, 4.4vw, 3.75rem); /* 40~60px */
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.03em;
  color: ${({ theme }) => theme.neutral[900]};

  ${mobile} {
    font-size: 2rem; /* 32px */
  }
`;

const Board = styled.section<{ $isOn: boolean }>`
  ${({ $isOn }) => reveal({ isOn: $isOn })};
  --order: 1;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
`;

const BoardHead = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem 1.5rem;

  h3 {
    ${({ theme }) => theme.text.heading02};
    color: ${({ theme }) => theme.neutral[800]};
  }

  span {
    ${({ theme }) => theme.text.caption02};
    color: ${({ theme }) => theme.neutral[600]};
  }

  ${mobile} {
    h3 {
      font-size: 1.25rem; /* 20px */
    }
  }
`;

const Stats = styled.dl`
  display: grid;
  grid-template-columns: repeat(var(--columns), minmax(0, 1fr));
  gap: 0.25rem;
  padding: 1rem; /* 16px */
  border-radius: 1rem;
  background-color: ${({ theme }) => theme.neutral[100]};

  @media (max-width: 1023px) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  ${mobile} {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 0.5rem;
  }
`;

const Stat = styled.div<{ $isHighlighted?: boolean }>`
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1.5rem 1.25rem; /* 24px 20px */
  border-radius: 0.625rem; /* 10px */
  background-color: ${({ theme, $isHighlighted }) => ($isHighlighted ? theme.neutral[800] : "transparent")};
  color: ${({ theme, $isHighlighted }) => ($isHighlighted ? theme.neutral[0] : theme.neutral[900])};

  dt {
    ${({ theme }) => theme.text.body01};
    color: ${({ theme, $isHighlighted }) => ($isHighlighted ? theme.neutral[0] : theme.neutral[600])};
  }

  dd {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    margin: 0;
  }

  span {
    color: ${({ theme, $isHighlighted }) => ($isHighlighted ? theme.neutral[300] : theme.neutral[500])};
  }

  ${mobile} {
    padding: 1rem 0.875rem;

    &:first-of-type {
      grid-column: 1 / -1;
    }
  }
`;

const StatValue = styled.strong`
  font-size: clamp(1.75rem, 2.8vw, 2.5rem); /* 28~40px */
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
`;

const StatSub = styled.span`
  ${({ theme }) => theme.text.caption02};
`;

const BoardNote = styled.p`
  ${({ theme }) => theme.text.caption02};
  line-height: 1.6;
  color: ${({ theme }) => theme.neutral[600]};
`;

const Divider = styled.hr`
  height: 1px;
  margin: 3rem 0; /* 48px */
  border: 0;
  background-color: ${({ theme }) => theme.neutral[200]};

  ${mobile} {
    margin: 2rem 0;
  }
`;

const SectionTitle = styled.h3`
  margin-bottom: 1.75rem; /* 28px */
  font-size: clamp(1.5rem, 2.3vw, 2rem); /* 24~32px */
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.02em;
  color: ${({ theme }) => theme.neutral[900]};

  ${mobile} {
    margin-bottom: 1.25rem;
  }
`;

const Cards = styled.div`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1.5rem; /* 24px */

  ${mobile} {
    grid-template-columns: minmax(0, 1fr);
    gap: 1rem;
  }
`;

const Card = styled.article<{ $isOn: boolean }>`
  ${({ $isOn }) => reveal({ isOn: $isOn })};
  display: flex;
  flex-direction: column;
  padding: 2rem; /* 32px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem;
  background-color: ${({ theme }) => theme.neutral[0]};

  &:hover {
    border-color: ${({ theme }) => theme.neutral[300]};
    box-shadow: ${({ theme }) => theme.shadow03};
  }

  ${mobile} {
    padding: 1.5rem 1.25rem;
  }
`;

const CardNumber = styled.span`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[500]};
`;

const CardTitle = styled.h4`
  margin-top: 1rem;
  font-size: 1.5rem; /* 24px */
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.02em;
  color: ${({ theme }) => theme.neutral[900]};

  ${mobile} {
    font-size: 1.25rem;
  }
`;

const CardLead = styled.p`
  margin-top: 0.75rem;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const CardFigure = styled.div`
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 10rem; /* 160px */
  margin: 1.5rem 0;

  svg {
    width: 100%;
    max-width: 30rem;
    height: auto;
    overflow: visible;
  }
`;

const CardBody = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-top: auto;
`;

const Result = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  padding: 1.25rem 1.5rem;
  border-radius: 0.5rem;
  background-color: ${({ theme }) => theme.neutral[100]};

  span {
    ${({ theme }) => theme.text.caption02};
    color: ${({ theme }) => theme.neutral[600]};
  }

  strong {
    font-size: 1.75rem; /* 28px */
    font-weight: 700;
    line-height: 1.3;
    letter-spacing: -0.02em;
    color: ${({ theme }) => theme.neutral[900]};
    font-variant-numeric: tabular-nums;
  }

  ${mobile} {
    padding: 1rem 1.125rem;

    strong {
      font-size: 1.375rem;
    }
  }
`;

const Footer = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-top: 2.5rem;
`;

/**
 * 본문 끝의 스크롤 안내. 첫 장면의 「스크롤하면 한 장씩 넘어가요」와 같은 모양으로, 마우스의 휠 점이 아래로 굴러가요.
 * 첫 장면의 마우스는 입자로 그리지만 기술 소개에서는 입자를 숨기므로 선으로 그려요. 누르면 다음 장면으로 넘어가요.
 */
const ScrollCue = styled.button`
  display: flex;
  flex-direction: column;
  align-items: center;
  align-self: center;
  gap: 0.5rem; /* 8px */
  margin-top: 4rem; /* 64px */
  padding: 0.5rem 1rem 0;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
  white-space: nowrap;
  transition: color 0.2s;

  &:hover {
    color: ${({ theme }) => theme.neutral[800]};
  }

  ${mobile} {
    margin-top: 2.5rem;
  }
`;

const wheelRoll = keyframes`
  0% { opacity: 0; transform: translateY(-2px); }
  25% { opacity: 1; }
  70% { opacity: 1; transform: translateY(9px); }
  100% { opacity: 0; transform: translateY(11px); }
`;

const Mouse = styled.svg`
  width: 2rem; /* 32px */
  height: 2.75rem; /* 44px */

  rect {
    fill: none;
    stroke: ${({ theme }) => theme.neutral[700]};
    stroke-width: 2.5;
  }

  circle {
    fill: ${({ theme }) => theme.neutral[800]};
    animation: ${wheelRoll} 1.6s ease-in-out infinite;
  }

  @media (prefers-reduced-motion: reduce) {
    circle {
      animation: none;
    }
  }
`;

const Source = styled.a`
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  color: ${({ theme }) => theme.neutral[700]};
  text-decoration: underline;
  text-underline-offset: 0.2em;
  ${({ theme }) => focusRing(theme)};
`;

const Navigation = styled.nav`
  display: flex;
  gap: 0.5rem;
`;

const MoveButton = styled.button`
  min-height: 2.75rem; /* 44px */
  padding: 0 1.125rem;
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 1.375rem;
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  color: ${({ theme }) => theme.neutral[800]};
  transition:
    border-color 0.2s,
    color 0.2s;
  ${({ theme }) => focusRing(theme)};

  &:hover {
    border-color: ${({ theme }) => theme.neutral[800]};
    color: ${({ theme }) => theme.neutral[900]};
  }
`;
