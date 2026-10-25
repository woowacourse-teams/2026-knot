import { useTheme } from "@emotion/react";
import { hexToRgb } from "@utils/hexToRgb";
import { useEffect, useRef, useState } from "react";

import type { GraphLabel } from "../types/particle";

import { createLandingEngine, type LandingEngine } from "./createLandingEngine";

/** 엔진이 시작하기 전에 글꼴을 기다리는 최대 시간. 늦게 뜬 글꼴로 바뀌면 캔버스 글자 모양이 어긋나요. */
const FONT_WAIT_MS = 1800;

/**
 * 랜딩 무대의 엔진을 붙이고, 화면 글자가 바뀌어야 하는 엔진 상태를 React 상태로 옮겨요.
 *
 * 반환한 ref들을 무대의 각 요소에 걸어야 엔진이 시작합니다.
 */
const useLandingStage = () => {
  const theme = useTheme();

  const scrollerRef = useRef<HTMLDivElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const scrollCueRef = useRef<HTMLDivElement>(null);
  const documentsRef = useRef<HTMLDivElement>(null);
  const confirmationRef = useRef<HTMLDivElement>(null);
  const questionInputRef = useRef<HTMLDivElement>(null);
  const techRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<LandingEngine | null>(null);

  const [scene, setScene] = useState(0);
  const [isSettled, setIsSettled] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [search, setSearch] = useState({ typedText: "", isAnswerReady: false });
  const [questionIndex, setQuestionIndex] = useState(0);
  const [labels, setLabels] = useState<GraphLabel[]>([]);
  const [storyIndex, setStoryIndex] = useState(0);

  useEffect(() => {
    const scroller = scrollerRef.current;
    const stage = stageRef.current;
    const canvas = canvasRef.current;
    const scrollCue = scrollCueRef.current;
    const documents = documentsRef.current;
    const confirmation = confirmationRef.current;
    const questionInput = questionInputRef.current;
    const tech = techRef.current;
    if (!scroller || !stage || !canvas || !scrollCue || !documents || !confirmation || !questionInput || !tech) return;

    const ink = hexToRgb(theme.neutral[800]);
    const strong = hexToRgb(theme.neutral[900]);
    const accent = hexToRgb(theme.sub.accent[500]);
    const engine = createLandingEngine({
      elements: { scroller, stage, canvas, scrollCue, documents, confirmation, questionInput, tech },
      listeners: {
        onSceneChange: setScene,
        onSettledChange: setIsSettled,
        onRecordingSecondsChange: setRecordingSeconds,
        onSearchChange: setSearch,
        onLabelsChange: setLabels,
      },
      palette: [
        `rgba(${strong},0.9)`,
        `rgba(${ink},0.6)`,
        `rgba(${ink},0.32)`,
        `rgba(${ink},0.11)`,
        `rgba(${accent},0.95)`,
        `rgba(${accent},1)`,
      ],
    });
    if (!engine) return;
    engineRef.current = engine;

    // 장면 이동을 엔진이 맡아서, 화면 끝에 닿았을 때 브라우저의 당겨서 튕기는 동작을 꺼 둬요
    const root = document.documentElement;
    const previousOverscroll = root.style.overscrollBehavior;
    root.style.overscrollBehavior = "none";

    let isStarted = false;
    const start = () => {
      if (isStarted) return;
      isStarted = true;
      engine.start();
    };
    const fontTimer = window.setTimeout(start, FONT_WAIT_MS);
    if (document.fonts?.load) {
      Promise.all([
        document.fonts.load('700 64px "Pretendard Variable"'),
        document.fonts.load('600 26px "Pretendard Variable"'),
      ]).then(start, start);
    } else {
      start();
    }

    return () => {
      isStarted = true;
      window.clearTimeout(fontTimer);
      engine.destroy();
      engineRef.current = null;
      root.style.overscrollBehavior = previousOverscroll;
    };
  }, [theme]);

  // 바뀐 카드를 그린 뒤에 재야 새 카드 모양으로 입자가 모여서, 화면에 반영된 다음에 엔진에 알려요.
  // 기술 장면이 아닐 때는 엔진이 아무것도 하지 않아요.
  useEffect(() => {
    engineRef.current?.reshapeTech();
  }, [storyIndex]);

  const selectQuestion = (index: number) => {
    setQuestionIndex(index);
    engineRef.current?.selectQuestion(index);
  };

  const goToScene = (index: number) => {
    engineRef.current?.stepTo(index);
  };

  return {
    refs: { scrollerRef, stageRef, canvasRef, scrollCueRef, documentsRef, confirmationRef, questionInputRef, techRef },
    scene,
    isSettled,
    recordingSeconds,
    search,
    questionIndex,
    labels,
    storyIndex,
    selectQuestion,
    selectStory: setStoryIndex,
    goToScene,
  };
};

export default useLandingStage;
