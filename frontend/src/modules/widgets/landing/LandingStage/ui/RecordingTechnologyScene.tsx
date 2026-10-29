import TechnologyScene, { CardOutcome, CardPoint, CardResult, CardText, StatBoard, TechCard } from "./TechnologyScene";
import {
  ChunkSaveFigure,
  OfflineResendFigure,
  SegmentTranscribeFigure,
  SpeakerLinkFigure,
} from "./TechnologyFigures";

interface RecordingTechnologySceneProps {
  isOn: boolean;
  onPrevious: () => void;
  onNext: () => void;
}

const SOURCE = "https://app.notion.com/p/404b43517522818bad7dff7cdfdcec87";

const ACCURACY = [
  { name: "Knot", value: "약 94%", isHighlighted: true },
  { name: "클로바노트", value: "약 94%" },
  { name: "다글로", value: "약 94%" },
  { name: "Aiko", value: "약 89%" },
  { name: "애플 음성 녹음", value: "약 69%" },
];

/** 기술 소개 01. 상용 서비스와 비교한 전사 정확도와, 녹음을 잃지 않게 한 네 번의 개선을 보여 줘요. */
export default function RecordingTechnologyScene({ isOn, onPrevious, onNext }: RecordingTechnologySceneProps) {
  return (
    <TechnologyScene
      isOn={isOn}
      titleId="technology-recording-title"
      eyebrow={{ number: "01", name: "녹음과 전사" }}
      title={
        <>
          녹음은 정확하게,
          <br />
          기록은 이어지게.
        </>
      }
      board={
        <StatBoard
          isOn={isOn}
          heading="상용 전사 서비스와 비교한 정확도"
          note="일상 / 의료·보건 토론 20시간 기준"
          items={ACCURACY}
          footnote="문자 기준 전사 정확도 · 동일 음원과 정답을 기준으로 비교"
        />
      }
      sectionTitle="신뢰도 높은 녹음을 위한 네 번의 개선"
      source={{ href: SOURCE, label: "녹음 개선 과정 자세히 보기" }}
      previous={{ label: "사용법으로", onClick: onPrevious }}
      next={{ label: "탐색 기술", onClick: onNext }}
    >
      <TechCard
        isOn={isOn}
        order={0}
        title="창이 닫히더라도 원본 유실 방지"
        lead="녹음이 끝나기 전부터 원본을 보존합니다."
        figure={<ChunkSaveFigure isOn={isOn} />}
      >
        <CardPoint>녹음을 5초마다 저장</CardPoint>
        <CardOutcome>저장한 조각으로 재시도 가능</CardOutcome>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={1}
        title="녹음 전사 시간 단축"
        lead="종료 후 전체 전사 → 녹음 중 2분씩 전사"
        figure={<SegmentTranscribeFigure isOn={isOn} />}
      >
        <CardResult basis="1시간 녹음 기준">평균 20분 → 평균 26초</CardResult>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={2}
        title="나눠진 문맥과 화자를 연결"
        lead="구간이 바뀌어도 같은 사람의 발언으로."
        figure={<SpeakerLinkFigure isOn={isOn} />}
      >
        <CardText>
          앞 10초 문맥을 겹치고,
          <br />
          시각과 목소리 특징을 맞춥니다.
        </CardText>
        <CardOutcome>회의 전체 기준으로 화자 다시 묶기</CardOutcome>
      </TechCard>

      <TechCard
        isOn={isOn}
        order={3}
        title="연결이 끊겨도 녹음을 이어갈 수 있게"
        lead="인터넷이 잠깐 끊겨도 소리는 기기에 보관."
        figure={<OfflineResendFigure isOn={isOn} />}
      >
        <CardText>
          30초에는 끊김 표시, 종료는 30분 유예.
          <br />
          연결이 돌아오면 보관한 음성을 재전송합니다.
        </CardText>
        <CardOutcome>기기 보관 → 연결 복구 → 재전송</CardOutcome>
      </TechCard>
    </TechnologyScene>
  );
}
