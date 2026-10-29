import styled from "@emotion/styled";

import SceneLayer, { focusRing, interactive, mobile } from "./SceneLayer";

interface TechnologySceneProps {
  isOn: boolean;
  technology: "recording" | "search";
  onPrevious: () => void;
  onNext: () => void;
}

const RECORDING_SOURCE =
  "https://app.notion.com/p/404b43517522818bad7dff7cdfdcec87";
const SEARCH_SOURCE =
  "https://app.notion.com/p/3fc0691a002381899022e9a2f7e320d7";

/** 녹음의 보존·복구와 탐색의 응답 개선을 사용자 가치, 처리 흐름, 확인한 결과로 소개해요. */
export default function TechnologyScene({
  isOn,
  technology,
  onPrevious,
  onNext,
}: TechnologySceneProps) {
  const isRecording = technology === "recording";
  const titleId = `technology-${technology}-title`;

  return (
    <Container
      isOn={isOn}
      role="region"
      aria-labelledby={titleId}
      data-landing-scroll
      tabIndex={isOn ? 0 : -1}
    >
      <Copy>
        <Eyebrow>
          기술 소개 {isRecording ? "01" : "02"}
          <span>{isRecording ? "녹음의 신뢰성" : "탐색의 응답성"}</span>
        </Eyebrow>
        <Title id={titleId}>
          {isRecording ? (
            <>
              회의는 흘러가도,
              <br />
              기록은 남도록.
            </>
          ) : (
            <>
              답을 찾는 동안,
              <br />
              대화가 멈추지 않도록.
            </>
          )}
        </Title>
        <Lead>
          {isRecording
            ? "회의가 끝나기 전부터 소리를 저장하고, 이미 남긴 기록으로 실패한 처리를 이어가요."
            : "팀 문서를 빠르게 찾고, 근거와 함께 답해요. 기다림 때문에 대화가 끊기지 않도록요."}
        </Lead>
        <Explanation>
          <SmallLabel>어떤 문제를 풀었나요?</SmallLabel>
          <p>
            {isRecording
              ? "녹음이 끝난 뒤 한꺼번에 저장하면, 창이 닫힐 때 회의 전체를 잃을 수 있었어요."
              : "문서를 찾을 때마다 AI가 탐색 계획과 검색을 반복하면서, 답변을 받기까지 오래 기다려야 했어요."}
          </p>
          <SmallLabel>어떻게 바꿨나요?</SmallLabel>
          <p>
            {isRecording
              ? "5초마다 원본을 저장하고 2분 구간으로 AI 전사를 진행해요. DB 대기열에 진행 상태를 남겨, 실패하면 저장된 입력으로 다시 시도해요."
              : "워크스페이스의 문서를 PostgreSQL로 직접 찾는 Fast Path를 만들었어요. 반복 탐색은 줄이고, AI의 답변 생성과 근거 검증은 이어가요."}
          </p>
        </Explanation>
      </Copy>

      <Evidence>
        {isRecording ? (
          <>
            <Flow aria-label="녹음 처리 흐름">
              <FlowStep>
                <StepNumber>01</StepNumber>
                <div>
                  <strong>5초마다 저장</strong>
                  <p>원본 조각과 저장한 위치를 확인해요.</p>
                </div>
                <Chunks aria-hidden>
                  {Array.from({ length: 12 }, (_, index) => (
                    <i key={index} />
                  ))}
                </Chunks>
              </FlowStep>
              <FlowStep>
                <StepNumber>02</StepNumber>
                <div>
                  <strong>2분 구간 AI 전사</strong>
                  <p>회의 중에도 말을 글로 바꿔요.</p>
                </div>
                <Wave aria-hidden>
                  {[9, 18, 28, 14, 22, 34, 19, 11].map((height, index) => (
                    <i key={index} style={{ height }} />
                  ))}
                </Wave>
              </FlowStep>
              <FlowStep>
                <StepNumber>03</StepNumber>
                <div>
                  <strong>결과 연결·재시도</strong>
                  <p>구간을 잇고, 실패한 처리를 이어가요.</p>
                </div>
                <FlowMark aria-hidden>↗</FlowMark>
              </FlowStep>
            </Flow>
            <Recovery>
              <SmallLabel>실제로 복구한 기록</SmallLabel>
              <Metrics>
                <div>
                  <strong>
                    39<span>개</span>
                  </strong>
                  <p>저장된 조각</p>
                </div>
                <b aria-hidden>→</b>
                <div>
                  <strong>
                    189<span>초</span>
                  </strong>
                  <p>약 3분의 녹음</p>
                </div>
                <b aria-hidden>→</b>
                <div>
                  <strong>
                    2<span>개</span>
                  </strong>
                  <p>생성된 문서</p>
                </div>
              </Metrics>
              <Footnote>
                임시 디스크 부족으로 실패한 녹음에서, 사람이 공간을 확보한 뒤
                재시도해 전사와 문서 2개를 생성했어요.
              </Footnote>
            </Recovery>
            <Source href={RECORDING_SOURCE} target="_blank" rel="noreferrer">
              녹음 개선 과정 보기 <span aria-hidden>↗</span>
            </Source>
          </>
        ) : (
          <>
            <Comparison>
              <SmallLabel>첫 답변 조각을 받기까지 · 중앙값</SmallLabel>
              <Timing>
                <span>
                  24.337<small>초</small>
                </span>
                <b aria-hidden>→</b>
                <strong>
                  3.496<small>초</small>
                </strong>
              </Timing>
              <Chart aria-label="첫 답변 조각 중앙값: 기존 24.337초, Fast Path 3.496초">
                <ChartRow>
                  <span>기존</span>
                  <Track>
                    <Bar $percent={100} />
                  </Track>
                </ChartRow>
                <ChartRow>
                  <span>Fast Path</span>
                  <Track>
                    <Bar $percent={(3.496 / 24.337) * 100} $isAccent />
                  </Track>
                </ChartRow>
              </Chart>
            </Comparison>
            <SearchFlow aria-label="탐색 처리 흐름">
              <span>질문</span>
              <b aria-hidden>→</b>
              <span>
                팀 문서 바로 찾기<small>PostgreSQL</small>
              </span>
              <b aria-hidden>→</b>
              <span>
                AI 답변<small>근거 확인</small>
              </span>
            </SearchFlow>
            <Experiment>
              <SmallLabel>이렇게 비교했어요</SmallLabel>
              <Footnote>
                같은 고정 질문 30개를 로컬 Docker·PostgreSQL과 실제 Qwen API로
                순차 짝비교했어요.
              </Footnote>
              <Footnote>
                이 실행의 사실 정합은 29/30, 저장 근거 정합은 28/30이에요. 운영
                환경 전체의 속도를 보장하는 수치는 아니에요.
              </Footnote>
            </Experiment>
            <Source href={SEARCH_SOURCE} target="_blank" rel="noreferrer">
              실험 기록 보기 <span aria-hidden>↗</span>
            </Source>
          </>
        )}
      </Evidence>

      <Navigation
        aria-label={`${isRecording ? "녹음" : "탐색"} 기술 장면 이동`}
      >
        <button type="button" onClick={onPrevious}>
          ← {isRecording ? "사용법으로" : "녹음 기술"}
        </button>
        <button type="button" onClick={onNext}>
          {isRecording ? "탐색 기술" : "시작하기로"} →
        </button>
      </Navigation>
    </Container>
  );
}

const Container = styled(SceneLayer)`
  position: absolute;
  inset: 5.5rem 13% 2rem 7%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  align-content: safe center;
  align-items: center;
  column-gap: clamp(2rem, 7vw, 8rem);
  row-gap: 2rem;
  overflow-y: auto;
  ${({ isOn }) => interactive({ isOn })};
  ${({ theme }) => focusRing(theme)};

  ${mobile} {
    inset: 4.75rem 6% 1rem;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 1.75rem;
    padding: 0.75rem 0.125rem 1rem;
    touch-action: pan-y;
  }
`;
const Copy = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
`;
const Eyebrow = styled.p`
  display: flex;
  align-items: center;
  gap: 0.625rem;
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.sub.accent[500]};
  &::before {
    content: "";
    width: 1.125rem;
    height: 1.5px;
    background: currentColor;
  }
  span {
    margin-left: 0.25rem;
    color: ${({ theme }) => theme.neutral[800]};
  }
`;
const Title = styled.h2`
  ${({ theme }) => theme.text.title02};
  color: ${({ theme }) => theme.neutral[900]};
  font-size: clamp(1.75rem, 2.8vw, 2.5rem);
  ${mobile} {
    font-size: 1.75rem;
  }
`;
const Lead = styled.p`
  ${({ theme }) => theme.text.body02};
  line-height: 1.6;
  color: ${({ theme }) => theme.neutral[700]};
  text-wrap: pretty;
  ${mobile} {
    font-size: 1rem;
  }
`;
const Explanation = styled.div`
  display: grid;
  gap: 0.5rem;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
  > p + p {
    margin-top: 0.5rem;
  }
  ${mobile} {
    font-size: 0.9375rem;
  }
`;
const SmallLabel = styled.p`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[800]};
`;
const Evidence = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  min-width: 0;
`;
const Flow = styled.ol`
  list-style: none;
  padding: 0;
  margin: 0;
`;
const FlowStep = styled.li`
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1.125rem 0;
  border-bottom: 1px solid ${({ theme }) => theme.neutral[200]};
  strong {
    ${({ theme }) => theme.text.label01};
    color: ${({ theme }) => theme.neutral[800]};
  }
  p {
    margin-top: 0.25rem;
    ${({ theme }) => theme.text.caption02};
    color: ${({ theme }) => theme.neutral[600]};
  }
  ${mobile} {
    gap: 0.75rem;
  }
`;
const StepNumber = styled.span`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.sub.accent[500]};
  font-variant-numeric: tabular-nums;
`;
const Chunks = styled.span`
  display: grid;
  grid-template-columns: repeat(6, 5px);
  gap: 3px;
  margin-left: auto;
  i {
    width: 5px;
    height: 9px;
    border-radius: 1px;
    background: ${({ theme }) => theme.sub.accent[500]};
  }
  i:nth-of-type(n + 7) {
    opacity: 0.35;
  }
`;
const Wave = styled.span`
  display: flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
  i {
    width: 3px;
    border-radius: 2px;
    background: ${({ theme }) => theme.sub.accent[500]};
  }
`;
const FlowMark = styled.span`
  margin-left: auto;
  font-size: 2rem;
  color: ${({ theme }) => theme.sub.accent[500]};
`;
const Recovery = styled.div`
  padding: 1.5rem;
  border-radius: 1rem;
  background: ${({ theme }) => theme.neutral[100]};
  ${mobile} {
    padding: 1.25rem;
  }
`;
const Metrics = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin: 1rem 0;
  strong {
    font-size: clamp(1.75rem, 3vw, 2.5rem);
    font-weight: 600;
    letter-spacing: -0.04em;
    color: ${({ theme }) => theme.sub.accent[700]};
  }
  strong span {
    margin-left: 0.125rem;
    ${({ theme }) => theme.text.caption02};
  }
  p {
    ${({ theme }) => theme.text.caption01};
    color: ${({ theme }) => theme.neutral[600]};
  }
  > b {
    font-weight: 400;
    color: ${({ theme }) => theme.neutral[400]};
  }
`;
const Footnote = styled.p`
  ${({ theme }) => theme.text.caption02};
  line-height: 1.65;
  color: ${({ theme }) => theme.neutral[600]};
`;
const Source = styled.a`
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 2.75rem;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.sub.accent[700]};
  text-decoration: underline;
  text-underline-offset: 4px;
  ${({ theme }) => focusRing(theme)};
`;
const Comparison = styled.div`
  display: grid;
  gap: 1.5rem;
`;
const Timing = styled.div`
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
  font-size: clamp(2rem, 3.5vw, 3.125rem);
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
  > span {
    color: ${({ theme }) => theme.neutral[400]};
  }
  > strong {
    color: ${({ theme }) => theme.sub.accent[700]};
    font-weight: 600;
  }
  > b {
    font-size: 1.5rem;
    font-weight: 400;
    color: ${({ theme }) => theme.neutral[400]};
  }
  small {
    margin-left: 0.25rem;
    ${({ theme }) => theme.text.caption02};
  }
  ${mobile} {
    font-size: clamp(1.625rem, 7vw, 2.5rem);
  }
`;
const Chart = styled.div`
  display: grid;
  gap: 1rem;
`;
const ChartRow = styled.div`
  display: flex;
  align-items: center;
  gap: 1rem;
  > span {
    flex-shrink: 0;
    width: 4.5rem;
    ${({ theme }) => theme.text.caption02};
    color: ${({ theme }) => theme.neutral[600]};
  }
`;
const Track = styled.div`
  flex: 1;
  height: 0.5rem;
  border-radius: 1rem;
  background: ${({ theme }) => theme.neutral[100]};
`;
const Bar = styled.div<{ $percent: number; $isAccent?: boolean }>`
  width: ${({ $percent }) => $percent}%;
  height: 100%;
  border-radius: inherit;
  background: ${({ theme, $isAccent }) =>
    $isAccent ? theme.sub.accent[500] : theme.neutral[300]};
`;
const SearchFlow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 1.25rem 0;
  border-block: 1px solid ${({ theme }) => theme.neutral[200]};
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[800]};
  > span {
    text-align: center;
  }
  small {
    display: block;
    margin-top: 0.25rem;
    ${({ theme }) => theme.text.caption01};
    color: ${({ theme }) => theme.neutral[600]};
  }
  > b {
    color: ${({ theme }) => theme.sub.accent[500]};
    font-weight: 400;
  }
`;
const Experiment = styled.div`
  display: grid;
  gap: 0.5rem;
`;
const Navigation = styled.nav`
  grid-column: 1 / -1;
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  button {
    min-height: 2.75rem;
    ${({ theme }) => theme.text.caption02};
    color: ${({ theme }) => theme.neutral[700]};
    ${({ theme }) => focusRing(theme)};
  }
`;
