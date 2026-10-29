import TechnologyScene, { CardOutcome, CardResult, CardText, StatBoard, TechCard } from "./TechnologyScene";
import { NormalizeFigure, PathCompareFigure, PipelineFigure, WorkspaceSearchFigure } from "./TechnologyFigures";

interface SearchTechnologySceneProps {
  isOn: boolean;
  onPrevious: () => void;
  onNext: () => void;
}

const SOURCE = "https://app.notion.com/p/3fc0691a002381899022e9a2f7e320d7";

const RESPONSE = [
  { name: "첫 답변까지", value: "3.5초", sub: "기존 24.3초", isHighlighted: true },
  { name: "답변 완료까지", value: "5.8초", sub: "기존 26.5초" },
  { name: "탐색용 LLM 호출", value: "0회", sub: "기존 233회" },
  { name: "답변 완료", value: "30/30", sub: "기존 30/30" },
  { name: "사실·근거 정합", value: "30/30", sub: "정규화 반영 후" },
];

/** 기술 소개 02. 기존 탐색 경로와 비교한 응답 시간과, 빠르게 답하게 한 네 번의 개선을 보여 줘요. */
export default function SearchTechnologyScene({ isOn, onPrevious, onNext }: SearchTechnologySceneProps) {
  return (
    <TechnologyScene
      isOn={isOn}
      titleId="technology-search-title"
      eyebrow={{ number: "02", name: "AI 탐색" }}
      title={
        <>
          찾는 건 빠르게,
          <br />
          답은 근거 있게.
        </>
      }
      board={
        <StatBoard
          isOn={isOn}
          heading="기존 탐색 경로와 비교한 응답 시간"
          note="고정 질문 30개 · 시간은 중앙값"
          items={RESPONSE}
          footnote="같은 고정 질문 30개를 로컬 Docker·PostgreSQL과 실제 Qwen API로 순차 짝비교 · 사실·근거 정합은 질의 정규화를 반영한 별도 실행 결과 (첫 비교 실행은 29/30, 28/30)"
        />
      }
      sectionTitle="기다림 없는 답을 위한 네 번의 개선"
      source={{ href: SOURCE, label: "탐색 속도 실험 자세히 보기" }}
      previous={{ label: "녹음 기술", onClick: onPrevious }}
      next={{ label: "시작하기로", onClick: onNext }}
    >
      <TechCard
        isOn={isOn}
        order={0}
        title="반복 탐색을 한 번의 검색으로"
        lead="LLM과 MCP를 오가던 문서 탐색을 걷어냈습니다."
        figure={<PathCompareFigure isOn={isOn} />}
      >
        <CardResult basis="질문 30개 · 탐색용 LLM 호출">233회 → 0회</CardResult>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={1}
        title="우리 팀 문서 안에서 바로 찾기"
        lead="워크스페이스 범위 PostgreSQL 검색으로 후보를 찾습니다."
        figure={<WorkspaceSearchFigure isOn={isOn} />}
      >
        <CardResult basis="첫 답변 조각 · 중앙값">24.3초 → 3.5초</CardResult>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={2}
        title="질문은 다듬어 찾고, 답은 그대로"
        lead="검색어만 정규화하고, 답변엔 원래 질문을 전달."
        figure={<NormalizeFigure isOn={isOn} />}
      >
        <CardText>
          검색에 맞게 다듬은 말로 문서를 찾고,
          <br />
          답은 사용자가 물은 그대로 만듭니다.
        </CardText>
        <CardOutcome>정규화 반영 후 사실·근거 정합 30/30</CardOutcome>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={3}
        title="근거 검증과 실시간 전송은 그대로"
        lead="검색 자리만 바꾸고 나머지 흐름은 유지."
        figure={<PipelineFigure isOn={isOn} />}
      >
        <CardText>
          의도 분류, 근거 검증, 답변 저장, SSE 전송을
          <br />
          기존과 같은 흐름으로 거칩니다.
        </CardText>
        <CardOutcome>기존 Agent/MCP 경로도 선택 가능</CardOutcome>
      </TechCard>
    </TechnologyScene>
  );
}
