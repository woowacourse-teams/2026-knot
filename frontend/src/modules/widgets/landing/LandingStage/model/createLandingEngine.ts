import {
  MOBILE_BREAKPOINT,
  PARTICLE_COUNT,
  SAMPLE_QUESTIONS,
  SCENE,
  SCENE_COUNT,
  SCENE_TWEEN_MS,
} from "../constants/landing";
import type { Graph, GraphLabel, Particle, ParticleTarget, StageSize } from "../types/particle";
import {
  advancePulses,
  createPulse,
  nextPulseBoost,
  pointerRepulsion,
  pulseForce,
  waveAmplitude,
  waveBreath,
  type PulseWave,
} from "@utils/particleMotion";

import { clamp, gaussian, seededRandom } from "../utils/particleMath";
import {
  buildCardTargets,
  buildDocumentTargets,
  buildGraph,
  buildGraphTargets,
  buildIntroTargets,
  buildSpeechTargets,
  buildSymbolTargets,
  buildWaveTargets,
  WAVE_COLUMNS,
} from "../utils/particleTargets";

export interface LandingEngineElements {
  /** 장면 수만큼 세로로 긴 스크롤 영역 */
  scroller: HTMLElement;
  /** 화면에 붙어 있는 무대 */
  stage: HTMLElement;
  canvas: HTMLCanvasElement;
  /** 소개 장면에서 스크롤 안내 마우스가 그려질 자리 */
  scrollCue: HTMLElement;
  /** 정리 장면의 문서 카드 묶음 */
  documents: HTMLElement;
  /** 확인 장면의 문서 카드 */
  confirmation: HTMLElement;
  /** 탐색 장면의 질문 입력창 */
  questionInput: HTMLElement;
}

export interface LandingEngineListeners {
  onSceneChange: (scene: number) => void;
  /** 입자가 모양을 다 갖춰 진짜 글자를 보여도 되는지 */
  onSettledChange: (isSettled: boolean) => void;
  onRecordingSecondsChange: (seconds: number) => void;
  onSearchChange: (search: { typedText: string; isAnswerReady: boolean }) => void;
  onLabelsChange: (labels: GraphLabel[]) => void;
}

interface CreateLandingEngineParams {
  elements: LandingEngineElements;
  listeners: LandingEngineListeners;
  /** 입자 색. 진한 글자·보통 글자·연한 글자·윤곽선·강조·가장 진한 강조 순서예요. */
  palette: string[];
}

type AssignMode = "morph" | "fall" | "instant";

/**
 * 랜딩 무대를 움직이는 엔진을 만들어요.
 *
 * 스크롤 한 번에 장면 하나씩 넘기고, 장면이 바뀌면 입자 수천 개를 그 장면의 모양으로 다시 모아요.
 * 매 프레임 그려야 하는 일이라 React 상태로 다루지 않고, 장면 번호처럼 화면 글자가 바뀌어야 하는 값만
 * `listeners`로 알립니다. 반환한 `destroy`를 부르면 붙인 이벤트와 반복을 모두 걷어요.
 */
export const createLandingEngine = ({ elements, listeners, palette }: CreateLandingEngineParams) => {
  const { scroller, stage, canvas, scrollCue, documents, confirmation, questionInput } = elements;
  const context = canvas.getContext("2d");
  if (!context) return null;

  const prefersReducedMotion = !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  const pixelRatio = Math.min(window.devicePixelRatio || 1, 1.75);
  const sceneStarts = Array.from({ length: SCENE_COUNT }, (_, i) => i / SCENE_COUNT);

  const size: StageSize = { width: 0, height: 0, isMobile: false, count: PARTICLE_COUNT.desktop };
  const particles: Particle[] = [];
  const pulses: PulseWave[] = [];
  const amplitudes = new Float32Array(WAVE_COLUMNS);
  const buckets: Particle[][] = [[], [], [], [], [], []];
  const timers = new Set<number>();

  let caches: Record<string, ParticleTarget[]> = {};
  let graph: Graph | null = null;
  let frameCount = 0;
  let scene = -1;
  let progress = 0;
  let pointerX = -9999;
  let pointerY = -9999;
  let lastPulseAt = 0;
  let pulseBoost = 1;
  let isSettled = false;
  let settleTimer = 0;
  let recordingFrames = 0;
  let lastRecordingSecond = -1;
  let searchFrames = 0;
  let searchProgress = 0;
  let questionIndex = 0;
  let isAnswerReady = false;
  let typedLength = -1;
  let animationFrame = 0;
  let isDestroyed = false;

  const later = (callback: () => void, ms: number) => {
    const id = window.setTimeout(() => {
      timers.delete(id);
      callback();
    }, ms);
    timers.add(id);
    return id;
  };
  const cancel = (id: number) => {
    window.clearTimeout(id);
    timers.delete(id);
  };

  const readProgress = () => {
    const total = scroller.offsetHeight - size.height;
    if (total <= 0) return 0;
    return clamp(-scroller.getBoundingClientRect().top / total, 0, 1);
  };
  const sceneFor = (value: number) => {
    let index = 0;
    while (index < SCENE_COUNT - 1 && value >= sceneStarts[index + 1]) index++;
    return index;
  };

  // 한 번의 제스처는 한 장면만 넘겨요. 트랙패드 관성으로 여러 장면이 한꺼번에 넘어가지 않도록,
  // 휠이 잠깐 멈춘 뒤에만 다음 제스처를 받아요.
  let tween: { from: number; to: number; startedAt: number; duration: number } | null = null;
  let goal = -1;
  let lockUntil = 0;
  let lastWheelAt = -1e9;
  let lastWheelDelta = 0;
  let isArmed = true;
  let steppedAt = -1e9;

  const scrollTopFor = (index: number) => {
    const total = scroller.offsetHeight - size.height;
    return index === 0 ? 0 : (sceneStarts[index] + 0.5 / SCENE_COUNT) * total;
  };
  const runTween = () => {
    if (!tween) return;
    const k = tween.duration ? clamp((performance.now() - tween.startedAt) / tween.duration, 0, 1) : 1;
    const eased = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
    window.scrollTo(0, tween.from + (tween.to - tween.from) * eased);
    if (k >= 1) tween = null;
  };
  const isBusy = () => !!tween || performance.now() < lockUntil;
  const currentScene = () => (goal >= 0 && isBusy() ? goal : scene);

  const stepTo = (index: number) => {
    const next = clamp(index, 0, SCENE_COUNT - 1);
    if (next === currentScene()) return;
    goal = next;
    steppedAt = performance.now();
    lockUntil = steppedAt + (prefersReducedMotion ? 250 : SCENE_TWEEN_MS + 80);
    tween = {
      from: window.scrollY,
      to: scroller.getBoundingClientRect().top + window.scrollY + scrollTopFor(next),
      startedAt: performance.now(),
      duration: prefersReducedMotion ? 0 : SCENE_TWEEN_MS,
    };
  };
  const move = (direction: 1 | -1) => stepTo(currentScene() + direction);

  const handleWheel = (event: WheelEvent) => {
    if (event.ctrlKey) return;
    event.preventDefault();
    const timestamp = event.timeStamp || performance.now();
    const delta =
      event.deltaMode === 1 ? event.deltaY * 16 : event.deltaMode === 2 ? event.deltaY * size.height : event.deltaY;
    const absolute = Math.abs(delta);
    const gap = timestamp - lastWheelAt;
    const isAccelerating = absolute >= 15 && absolute > lastWheelDelta * 2.5 && lastWheelDelta < 12;
    const isRecent = performance.now() - steppedAt < SCENE_TWEEN_MS + 500;
    // 장면을 막 넘긴 직후에는 긴 멈춤이나 작게 새로 시작한 움직임만 새 제스처로 쳐요
    if (isRecent ? gap > 400 || (gap > 180 && absolute <= 12) : gap > 180 || isAccelerating) isArmed = true;
    lastWheelAt = timestamp;
    lastWheelDelta = absolute;
    if (isBusy()) {
      isArmed = false;
      return;
    }
    if (!isArmed || absolute < 1) return;
    if (performance.now() - steppedAt < SCENE_TWEEN_MS + 350 && gap < 400) {
      isArmed = false;
      return;
    }
    isArmed = false;
    move(delta > 0 ? 1 : -1);
  };

  let touchStartY: number | null = null;
  const handleTouchStart = (event: TouchEvent) => {
    touchStartY = event.touches[0].clientY;
  };
  const handleTouchMove = (event: TouchEvent) => event.preventDefault();
  const handleTouchEnd = (event: TouchEvent) => {
    if (touchStartY === null) return;
    const touch = event.changedTouches[0];
    const distance = touchStartY - (touch ? touch.clientY : touchStartY);
    touchStartY = null;
    if (Math.abs(distance) > 30 && !isBusy()) move(distance > 0 ? 1 : -1);
  };

  const handleKeyDown = (event: KeyboardEvent) => {
    if (event.altKey || event.metaKey || event.ctrlKey) return;
    const tagName = (event.target as HTMLElement | null)?.tagName;
    const isSpace = event.key === " " && tagName !== "BUTTON" && tagName !== "A";
    const isNext = event.key === "ArrowDown" || event.key === "PageDown" || (isSpace && !event.shiftKey);
    const isPrevious = event.key === "ArrowUp" || event.key === "PageUp" || (isSpace && event.shiftKey);
    if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      stepTo(event.key === "Home" ? 0 : SCENE_COUNT - 1);
      return;
    }
    if (!isNext && !isPrevious) return;
    event.preventDefault();
    if (!event.repeat && !isBusy()) move(isNext ? 1 : -1);
  };

  const ensureGraph = () => {
    if (!graph) {
      graph = buildGraph({ stage, input: questionInput, size });
      listeners.onLabelsChange(graph.labels);
    }
    return graph;
  };
  const graphTargets = (index: number) => {
    const key = `graph${index}`;
    if (!caches[key]) caches[key] = buildGraphTargets({ graph: ensureGraph(), questionIndex: index, size });
    return caches[key];
  };
  const cached = (key: string, build: () => ParticleTarget[]) => {
    if (!caches[key]) caches[key] = build();
    return caches[key];
  };

  const targetsFor = (index: number) => {
    if (index === SCENE.INTRO) return cached("intro", () => buildIntroTargets({ stage, box: scrollCue, size }));
    if (index === SCENE.PROBLEM) return cached("speech", () => buildSpeechTargets(size));
    if (index === SCENE.RECORDING) return cached("wave", () => buildWaveTargets(size));
    // 카드 위치는 무대 크기마다 바뀌어서 장면에 들어설 때마다 다시 재요
    if (index === SCENE.DOCUMENTS) return buildDocumentTargets({ stage, box: documents, size });
    if (index === SCENE.CONFIRMATION) return buildCardTargets({ stage, box: confirmation, size });
    if (index === SCENE.SEARCH) return graphTargets(questionIndex);
    return cached("symbol", () => buildSymbolTargets(size));
  };

  const assign = (targets: ParticleTarget[], mode: AssignMode) => {
    const particleOrder: number[] = [];
    const targetOrder: number[] = [];
    for (let i = 0; i < size.count; i++) {
      particleOrder.push(i);
      targetOrder.push(i);
    }

    let span = 36;
    if (mode === "morph") {
      particleOrder.sort((a, b) => particles[a].x - particles[b].x);
      targetOrder.sort((a, b) => targets[a].x - targets[b].x);
    } else if (mode === "fall") {
      particleOrder.sort((a, b) => seededRandom(a) - seededRandom(b));
      targetOrder.sort((a, b) => targets[a].y + Math.random() * 3 - (targets[b].y + Math.random() * 3));
      span = 110;
    }

    for (let r = 0; r < size.count; r++) {
      const particle = particles[particleOrder[r]];
      const targetIndex = targetOrder[r];
      const target = targets[targetIndex];
      particle.targetIndex = targetIndex;
      particle.nextHomeX = target.x;
      particle.nextHomeY = target.y;
      particle.nextKind = target.kind;
      particle.nextPhase = target.phase;
      particle.nextTarget = target;
      particle.mode = "";

      if (prefersReducedMotion || mode === "instant") {
        particle.vx = 0;
        particle.vy = 0;
        particle.homeX = particle.x = target.x;
        particle.homeY = particle.y = target.y;
        particle.kind = target.kind;
        particle.phase = target.phase;
        particle.target = target;
        particle.isWaiting = false;
        particle.pendingMode = "";
        continue;
      }
      particle.delay = frameCount + (r / size.count) * span + Math.random() * 8;
      particle.isWaiting = true;
      particle.pendingMode = mode;
    }
  };

  const retarget = () => {
    if (scene < 0) return;
    const targets = targetsFor(scene);
    particles.forEach((particle) => {
      const target = targets[particle.targetIndex % targets.length];
      if (particle.isWaiting) {
        particle.nextHomeX = target.x;
        particle.nextHomeY = target.y;
        particle.nextKind = target.kind;
        particle.nextPhase = target.phase;
        particle.nextTarget = target;
        return;
      }
      particle.homeX = target.x;
      particle.homeY = target.y;
      particle.kind = target.kind;
      particle.phase = target.phase;
      particle.target = target;
    });
  };

  const notifySearch = (typedText: string) => listeners.onSearchChange({ typedText, isAnswerReady });

  // 첫 화면에서는 입자가 대상 모양의 폭만큼 가는 선으로 시작했다가 물결치며 모양을 갖춰요
  const seedIntro = (index: number) => {
    const targets = targetsFor(index);
    let x0 = Infinity;
    let x1 = -Infinity;
    let sumY = 0;
    let sampled = 0;
    // 화면 아래의 스크롤 안내는 빼고, 워드마크 자리만으로 선의 폭과 높이를 정해요
    for (let i = 0; i < targets.length; i += 7) {
      if (targets[i].kind === 61 || targets[i].kind === 62) continue;
      x0 = Math.min(x0, targets[i].x);
      x1 = Math.max(x1, targets[i].x);
      sumY += targets[i].y;
      sampled++;
    }
    const centerY = sampled ? sumY / sampled : size.height / 2;
    if (!isFinite(x0)) {
      x0 = size.width * 0.3;
      x1 = size.width * 0.7;
    }
    particles.forEach((particle) => {
      particle.x = particle.homeX = x0 + Math.random() * (x1 - x0);
      particle.y = particle.homeY =
        centerY + Math.sin(((particle.x - x0) / (x1 - x0)) * Math.PI * 3) * size.height * 0.012 + gaussian() * size.height * 0.022;
      particle.vx = 0;
      particle.vy = 0;
    });
  };

  const setScene = (index: number) => {
    const previous = scene;
    scene = index;
    if (index === SCENE.RECORDING) recordingFrames = 0;
    if (index === SCENE.SEARCH) {
      searchFrames = -45;
      searchProgress = 0;
      isAnswerReady = false;
      typedLength = -1;
      notifySearch("");
    }
    listeners.onSceneChange(index);

    const targets = targetsFor(index);
    if (previous < 0 && !prefersReducedMotion) seedIntro(index);
    const mode: AssignMode =
      previous < 0 && prefersReducedMotion
        ? "instant"
        : previous === SCENE.RECORDING && index === SCENE.DOCUMENTS
          ? "fall"
          : "morph";
    assign(targets, mode);

    isSettled = false;
    listeners.onSettledChange(false);
    cancel(settleTimer);
    settleTimer = later(
      () => {
        if (scene !== index) return;
        isSettled = true;
        listeners.onSettledChange(true);
      },
      mode === "fall" ? 2300 : mode === "instant" ? 0 : 1200,
    );
  };

  // 파동과 파형 계산은 독의 입자와 같은 함수를 써요(`@utils/particleMotion`)
  const addPulse = (x: number, y: number) => {
    const now = Date.now();
    pulseBoost = nextPulseBoost({ lastPulseAt, boost: pulseBoost, now });
    lastPulseAt = now;
    pulses.push(createPulse({ x, y, width: size.width, height: size.height, boost: pulseBoost }));
  };

  const updateWaveAmplitudes = () => {
    const scale = prefersReducedMotion ? 0.7 : waveBreath(frameCount);
    const maxAmplitude = size.height * (size.isMobile ? 0.12 : 0.2);
    for (let c = 0; c < WAVE_COLUMNS; c++) {
      amplitudes[c] = waveAmplitude({ column: c, columns: WAVE_COLUMNS, frame: frameCount, maxAmplitude, scale });
    }
  };

  /** 스크롤 안내의 휠 점이 한 번 굴러 내려가는 동안의 진행(0~1). 2초에 한 번 구르고, `lag`만큼 늦은 입자가 꼬리가 돼요. */
  const wheelProgress = (lag: number) => {
    if (prefersReducedMotion) return 0;
    const t = (frameCount / 120 - lag) % 1;
    return t < 0 ? t + 1 : t;
  };

  /** 입자를 어느 색 묶음으로 그릴지 정해요. 묶음 번호는 `palette`의 순서예요. */
  const bucketFor = (particle: Particle, threadPhase: number) => {
    const { kind, target } = particle;
    if (kind === 0 || kind === 50) return particle.shade;
    if (kind === 60) return particle.shade === 0 ? 1 : 2;
    if (kind === 61) return particle.shade === 2 ? 2 : 1;
    if (kind === 62) {
      // 굴러 내려가며 흐려지고, 꼬리는 머리보다 한 단계 옅게 그려요
      const t = wheelProgress(particle.phase);
      const fade = t < 0.55 ? 0 : t < 0.8 ? 1 : 3;
      return Math.min(3, fade + (particle.phase > 0.06 ? 1 : 0));
    }
    if (kind === 7) {
      const level = target ? Math.abs(target.level ?? 0) : 0;
      return level < 0.45 ? 0 : level < 0.8 ? 1 : 2;
    }
    if (kind === 4 || kind === 12) return 1;
    if (kind === 3) return isSettled ? 3 : particle.shade === 2 ? 1 : 0;
    if (kind === 10) {
      const distance = (((particle.phase - threadPhase) % 1) + 1) % 1;
      return distance < 0.12 ? (distance < 0.015 ? 5 : 4) : 2;
    }
    if (kind === 13) {
      if (searchProgress <= 0 || particle.phase > searchProgress) return 2;
      return searchProgress < 1 && searchProgress - particle.phase < 0.04 ? 5 : 4;
    }
    if (kind === 15) return searchProgress >= 1 ? 5 : 1;
    if (kind === 16) return searchProgress > 0 ? 4 : 1;
    if (kind === 6) return 3;
    return 2;
  };

  const frame = () => {
    frameCount++;
    runTween();
    progress = readProgress();
    const nextScene = sceneFor(progress);
    if (nextScene !== scene) setScene(nextScene);
    const threadPhase = (frameCount * 0.006) % 1;

    if (scene === SCENE.RECORDING) {
      recordingFrames++;
      const second = Math.floor(recordingFrames / 60);
      if (second !== lastRecordingSecond) {
        lastRecordingSecond = second;
        listeners.onRecordingSecondsChange(second);
      }
    }
    if (scene === SCENE.SEARCH) {
      searchFrames++;
      const question = SAMPLE_QUESTIONS[questionIndex].text;
      const isInstant = prefersReducedMotion && searchFrames > 0;
      const typed = isInstant ? question.length : clamp(Math.floor(searchFrames / 2), 0, question.length);
      searchProgress = isInstant ? 1 : clamp((searchFrames - question.length * 2 - 14) / 80, 0, 1);
      const isReady = searchProgress >= 1;
      if (typed !== typedLength || isReady !== isAnswerReady) {
        typedLength = typed;
        isAnswerReady = isReady;
        notifySearch(question.slice(0, typed));
      }
    }
    updateWaveAmplitudes();

    buckets.forEach((bucket) => (bucket.length = 0));
    advancePulses(pulses);
    particles.forEach((particle) => {
      if (particle.isWaiting && frameCount >= particle.delay) {
        particle.isWaiting = false;
        particle.homeX = particle.nextHomeX;
        particle.homeY = particle.nextHomeY;
        particle.kind = particle.nextKind;
        particle.phase = particle.nextPhase;
        particle.target = particle.nextTarget;
        if (particle.pendingMode === "fall") {
          particle.x = particle.homeX + gaussian() * 30;
          particle.y = -10 - Math.random() * 180;
          particle.vx = 0;
          particle.vy = 2 + Math.random() * 3;
          particle.mode = "fall";
        }
        particle.pendingMode = "";
      }

      const { target } = particle;
      if (!particle.isWaiting && target?.motion && !prefersReducedMotion) {
        if (target.motion === 1) {
          particle.homeX = target.x;
          particle.homeY = (target.centerY ?? 0) + (target.level ?? 0) * amplitudes[target.column ?? 0] * (target.amplitudeScale ?? 1);
        } else if (target.motion === 2) {
          const roll = clamp(wheelProgress(target.phase) / 0.75, 0, 1);
          const eased = roll < 0.5 ? 2 * roll * roll : 1 - Math.pow(-2 * roll + 2, 2) / 2;
          const nextY = (target.baseY ?? target.y) + eased * (target.travel ?? 0);
          // 다 굴러간 점이 위로 끌려 올라가는 모습이 보이지 않게, 다음 바퀴의 출발점으로 바로 옮겨요
          if (nextY < particle.homeY - 2) particle.y = nextY;
          particle.homeX = target.baseX ?? target.x;
          particle.homeY = nextY;
        } else if (target.motion === 3) {
          // 휠 점이 구르는 동안 마우스도 2px 내려갔다 돌아와요
          const roll = clamp(wheelProgress(0) / 0.75, 0, 1);
          particle.homeX = target.baseX ?? target.x;
          particle.homeY = (target.baseY ?? target.y) + Math.sin(roll * Math.PI) * 2;
        } else {
          const group = target.group ?? 0;
          particle.homeX = (target.baseX ?? target.x) + Math.cos(frameCount * 0.012 + group * 2.1) * 5;
          particle.homeY = (target.baseY ?? target.y) + Math.sin(frameCount * 0.017 + group * 1.7) * 7;
        }
      }

      if (particle.mode === "fall") {
        particle.vy += 0.42;
        particle.x += (particle.homeX - particle.x) * 0.06;
        particle.y += particle.vy;
        if (particle.y >= particle.homeY - 4) {
          particle.mode = "";
          particle.vy *= 0.15;
        }
      } else {
        const spring = target?.motion ? 0.09 : 0.05;
        let ax = (particle.homeX - particle.x) * spring + (Math.random() - 0.5) * 0.18;
        let ay = (particle.homeY - particle.y) * spring + (Math.random() - 0.5) * 0.18;

        const repulsion = pointerRepulsion({ x: particle.x, y: particle.y, pointerX, pointerY });
        ax += repulsion.fx;
        ay += repulsion.fy;
        pulses.forEach((pulse) => {
          const force = pulseForce({ x: particle.x, y: particle.y, pulse });
          ax += force.fx;
          ay += force.fy;
        });

        particle.vx = (particle.vx + ax) * 0.84;
        particle.vy = (particle.vy + ay) * 0.84;
        particle.x += particle.vx;
        particle.y += particle.vy;
      }

      buckets[bucketFor(particle, threadPhase)].push(particle);
    });

    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    context.clearRect(0, 0, size.width, size.height);
    buckets.forEach((bucket, index) => {
      context.fillStyle = palette[index];
      const scale = index === 5 ? 1.6 : index === 4 ? 1.15 : 1;
      bucket.forEach((particle) => {
        const dotSize = particle.size * scale;
        context.fillRect(particle.x, particle.y, dotSize, dotSize);
      });
    });
  };

  const resize = () => {
    const width = stage.clientWidth;
    const height = stage.clientHeight;
    if (!width || !height || (width === size.width && height === size.height)) return false;
    size.width = width;
    size.height = height;
    size.isMobile = width < MOBILE_BREAKPOINT;
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    caches = {};
    graph = null;
    return true;
  };

  const handlePointerMove = (event: PointerEvent) => {
    const rect = stage.getBoundingClientRect();
    pointerX = event.clientX - rect.left;
    pointerY = event.clientY - rect.top;
  };
  const handlePointerLeave = () => {
    pointerX = -9999;
    pointerY = -9999;
  };
  const handlePointerDown = (event: PointerEvent) => {
    if ((event.target as HTMLElement | null)?.closest("button,a")) return;
    const rect = stage.getBoundingClientRect();
    addPulse(event.clientX - rect.left, event.clientY - rect.top);
  };

  let resizeTimer = 0;
  const handleResize = () => {
    cancel(resizeTimer);
    resizeTimer = later(() => {
      if (!resize()) return;
      retarget();
      later(() => graphTargets(questionIndex), 300);
    }, 150);
  };
  const resizeObserver = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(handleResize);

  const loop = () => {
    if (isDestroyed) return;
    frame();
    animationFrame = requestAnimationFrame(loop);
  };

  const start = () => {
    resize();
    size.count = size.isMobile ? PARTICLE_COUNT.mobile : PARTICLE_COUNT.desktop;
    for (let i = 0; i < size.count; i++) {
      particles.push({
        x: Math.random() * size.width,
        y: Math.random() * size.height,
        vx: 0,
        vy: 0,
        homeX: size.width / 2,
        homeY: size.height / 2,
        nextHomeX: 0,
        nextHomeY: 0,
        kind: 0,
        nextKind: 0,
        phase: 0,
        nextPhase: 0,
        target: null,
        nextTarget: null,
        shade: Math.random() < 0.45 ? 0 : Math.random() < 0.7 ? 1 : 2,
        size: 0.9 + Math.random() * 0.9,
        delay: 0,
        pendingMode: "",
        mode: "",
        targetIndex: i,
        isWaiting: false,
      });
    }

    window.addEventListener("wheel", handleWheel, { passive: false });
    window.addEventListener("touchstart", handleTouchStart, { passive: true });
    window.addEventListener("touchmove", handleTouchMove, { passive: false });
    window.addEventListener("touchend", handleTouchEnd, { passive: true });
    window.addEventListener("keydown", handleKeyDown);
    scroller.addEventListener("pointermove", handlePointerMove);
    scroller.addEventListener("pointerleave", handlePointerLeave);
    scroller.addEventListener("pointerdown", handlePointerDown);
    if (resizeObserver) resizeObserver.observe(stage);
    else window.addEventListener("resize", handleResize);

    progress = readProgress();
    setScene(sceneFor(progress));

    // 다른 장면의 모양은 첫 화면이 자리 잡은 뒤 한가할 때 미리 계산해 둬요
    const warmups = [
      () => targetsFor(SCENE.START),
      () => targetsFor(SCENE.PROBLEM),
      () => targetsFor(SCENE.RECORDING),
      () => graphTargets(questionIndex),
    ];
    const warm = () => {
      if (isDestroyed) return;
      const job = warmups.shift();
      if (!job) return;
      if (isBusy()) {
        warmups.unshift(job);
        later(warm, 300);
        return;
      }
      job();
      later(warm, 400);
    };
    later(warm, 1500);
    animationFrame = requestAnimationFrame(loop);
  };

  return {
    start,
    stepTo,
    /** 탐색 장면에서 다른 예시 질문을 골라, 타이핑부터 다시 보여 줘요. */
    selectQuestion: (index: number) => {
      questionIndex = index;
      searchFrames = 0;
      searchProgress = 0;
      isAnswerReady = false;
      typedLength = -1;
      notifySearch("");
      if (scene === SCENE.SEARCH) retarget();
    },
    destroy: () => {
      isDestroyed = true;
      cancelAnimationFrame(animationFrame);
      timers.forEach((id) => window.clearTimeout(id));
      timers.clear();
      window.removeEventListener("wheel", handleWheel);
      window.removeEventListener("touchstart", handleTouchStart);
      window.removeEventListener("touchmove", handleTouchMove);
      window.removeEventListener("touchend", handleTouchEnd);
      window.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("resize", handleResize);
      scroller.removeEventListener("pointermove", handlePointerMove);
      scroller.removeEventListener("pointerleave", handlePointerLeave);
      scroller.removeEventListener("pointerdown", handlePointerDown);
      resizeObserver?.disconnect();
    },
  };
};

export type LandingEngine = NonNullable<ReturnType<typeof createLandingEngine>>;
