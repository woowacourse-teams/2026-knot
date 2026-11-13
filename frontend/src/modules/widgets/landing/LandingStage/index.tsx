import styled from "@emotion/styled";
import Logo from "@primitives/ui/Logo";

import { LANDING_SCENES, type LandingScenes, SCENE } from "./constants/landing";
import useLandingSceneAnalytics from "./model/useLandingSceneAnalytics";
import useLandingStage from "./model/useLandingStage";
import ConfirmationScene from "./ui/ConfirmationScene";
import DocumentsScene from "./ui/DocumentsScene";
import IntroScene from "./ui/IntroScene";
import ProblemScene from "./ui/ProblemScene";
import RecordingScene from "./ui/RecordingScene";
import RecordingTechnologyScene from "./ui/RecordingTechnologyScene";
import { focusRing, interactive, mobile, sceneFade } from "./ui/SceneLayer";
import SceneSteps from "./ui/SceneSteps";
import SearchScene from "./ui/SearchScene";
import SearchTechnologyScene from "./ui/SearchTechnologyScene";
import StartScene from "./ui/StartScene";

interface LandingStageProps {
  /**
   * 보여 줄 장면 묶음. 기본은 공개 랜딩의 7장면이고, 기술 소개 페이지는 녹음 기술과 탐색 기술 장면이 든
   * `LANDING_TECHNOLOGY_SCENES`를 넘겨요.
   */
  scenes?: LandingScenes;
}

/**
 * 서비스를 처음 만난 사람에게 knot이 무엇이고 어떻게 쓰는지 보여 주는 랜딩 무대.
 *
 * 스크롤 한 번에 한 장면씩 넘어가며 소개 → 문제 → 녹음 → 정리 → 확인 → 탐색 → 시작 순서로 보여 줘요.
 * 기술 소개 장면이 든 묶음을 받으면 탐색과 시작 사이에 녹음 기술, 탐색 기술 장면이 들어가고, 그 두 장면에서는 입자 캔버스를 숨겨요.
 * 입자 수천 개가 장면마다 워드마크, 말 조각, 파형, 문서 카드, 연결망, 새 심볼로 모양을 바꾸고,
 * 포인터를 가져가면 비켜나고 누르면 물결이 퍼져요. 소개와 시작 장면의 「시작하기」로 로그인 화면에 가요.
 * 장면을 처음 볼 때마다 사용 기록으로 남겨요(`useLandingSceneAnalytics`).
 */
export default function LandingStage({
  scenes = LANDING_SCENES,
}: LandingStageProps) {
  const {
    refs,
    scene,
    isSettled,
    recordingSeconds,
    search,
    questionIndex,
    labels,
    selectQuestion,
    goToScene,
  } = useLandingStage(scenes);
  const hasTechnology =
    scenes.RECORDING_TECHNOLOGY !== undefined &&
    scenes.SEARCH_TECHNOLOGY !== undefined;
  const isTechnologyScene =
    scene === scenes.RECORDING_TECHNOLOGY || scene === scenes.SEARCH_TECHNOLOGY;
  useLandingSceneAnalytics(scene, scenes);

  return (
    <Scroller ref={refs.scrollerRef} $sceneCount={scenes.names.length}>
      <Stage ref={refs.stageRef}>
        <Canvas ref={refs.canvasRef} aria-hidden $isOn={!isTechnologyScene} />

        <Header>
          <HomeLink
            href="#"
            aria-label="knot 처음으로"
            $isOn={scene !== SCENE.INTRO}
            aria-hidden={scene === SCENE.INTRO}
            inert={scene === SCENE.INTRO}
            onClick={(event) => {
              event.preventDefault();
              goToScene(SCENE.INTRO);
            }}
          >
            <Logo width={3.125} /> {/* 50px */}
          </HomeLink>
        </Header>

        <SceneSteps
          names={scenes.names}
          scene={scene}
          onSceneSelect={goToScene}
        />

        <IntroScene
          isOn={scene === SCENE.INTRO}
          scrollCueRef={refs.scrollCueRef}
          onNext={() => goToScene(SCENE.PROBLEM)}
        />
        <ProblemScene isOn={scene === SCENE.PROBLEM} />
        <RecordingScene
          isOn={scene === SCENE.RECORDING}
          recordingSeconds={recordingSeconds}
        />
        <DocumentsScene
          isOn={scene === SCENE.DOCUMENTS}
          isRevealed={isSettled}
          documentsRef={refs.documentsRef}
        />
        <ConfirmationScene
          isOn={scene === SCENE.CONFIRMATION}
          isRevealed={isSettled}
          confirmationRef={refs.confirmationRef}
        />
        <SearchScene
          isOn={scene === SCENE.SEARCH}
          questionIndex={questionIndex}
          typedText={search.typedText}
          isAnswerReady={search.isAnswerReady}
          labels={labels}
          questionInputRef={refs.questionInputRef}
          onQuestionSelect={selectQuestion}
        />
        {hasTechnology && (
          <>
            <RecordingTechnologyScene
              isOn={scene === scenes.RECORDING_TECHNOLOGY}
              onPrevious={() => goToScene(SCENE.SEARCH)}
              onNext={() => goToScene(scenes.SEARCH_TECHNOLOGY ?? scenes.START)}
            />
            <SearchTechnologyScene
              isOn={scene === scenes.SEARCH_TECHNOLOGY}
              onPrevious={() =>
                goToScene(scenes.RECORDING_TECHNOLOGY ?? SCENE.SEARCH)
              }
              onNext={() => goToScene(scenes.START)}
            />
          </>
        )}
        <StartScene isOn={scene === scenes.START} />
      </Stage>
    </Scroller>
  );
}

/**
 * 장면 수만큼 세로로 긴 스크롤 영역. 스크롤 위치로 지금 장면이 정해져요.
 * 장면마다 화면 한 장 높이를 두고, 마지막 장면이 화면에 붙어 있을 자리로 한 장을 더해요.
 */
const Scroller = styled.div<{ $sceneCount: number }>`
  position: relative;
  width: 100%;
  height: calc(${({ $sceneCount }) => $sceneCount * 100}vh + 100dvh);
  background-color: ${({ theme }) => theme.neutral[50]};
  color: ${({ theme }) => theme.neutral[900]};
  word-break: keep-all;
`;

const Stage = styled.div`
  position: sticky;
  top: env(safe-area-inset-top, 0px);
  width: 100%;
  height: calc(
    100dvh - env(safe-area-inset-top, 0px) - env(safe-area-inset-bottom, 0px)
  );
  min-height: 32.5rem; /* 520px */
  overflow: hidden;
`;

/** 기술 소개 장면은 글이 많아 입자 캔버스를 숨기고, 다시 입자 장면으로 돌아오면 서서히 보여요. */
const Canvas = styled.canvas<{ $isOn: boolean }>`
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: ${({ $isOn }) => ($isOn ? 1 : 0)};
  transition: opacity 0.7s ease;
  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
`;

const Header = styled.header`
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 4.75rem; /* 76px */
  padding: 0 2.5rem; /* 40px */

  ${mobile} {
    height: 4rem; /* 64px */
    padding: 0 1.25rem; /* 20px */
  }
`;

/** 첫 장면에서는 워드마크가 입자로 크게 그려져 있어서, 머리말의 로고는 다음 장면부터 보여요. */
const HomeLink = styled.a<{ $isOn: boolean }>`
  ${({ $isOn }) => sceneFade({ isOn: $isOn })};
  ${({ $isOn }) => interactive({ isOn: $isOn })};
  display: flex;
  align-items: center;
  height: 2.75rem; /* 44px */
  ${({ theme }) => focusRing(theme)};

  ${mobile} {
    svg {
      width: 2.5625rem; /* 41px */
    }
  }
`;
