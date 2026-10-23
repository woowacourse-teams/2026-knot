import {
  advancePulses,
  clearLevelHistory,
  createLevelHistory,
  createPulse,
  frameStep,
  gaussian,
  levelHistoryPhase,
  nextPulseBoost,
  pointerRepulsion,
  prefersReducedMotion,
  pulseForce,
  pushLevel,
  roundedRectPoint,
  sampleLevelHistory,
  smoothLevel,
  stepSpring,
  type PulseWave,
  waveAmplitude,
} from "@utils/particleMotion";
import { measureAnalyserLevel } from "@utils/measureAudioLevel";
import { sampleShapeTargets } from "@utils/sampleShapeTargets";
import { useCallback, useEffect, useRef, type RefObject } from "react";

import {
  SEED_FADE_DELAY_MS,
  SEED_ICON_SIZE,
  SEED_MOBILE_BREAKPOINT,
  SEED_ORB_RADIUS,
  SEED_PARTICLE_COUNT,
  SEED_SLOW_FRAME_MS,
  SEED_WAVE_AMPLITUDE,
  SEED_WAVE_BOTTOM,
  SEED_WAVE_COLUMNS,
  SEED_WAVE_INTERVAL_MS,
  SEED_WAVE_MAX_WIDTH,
  SEED_WAVE_WIDTH_RATIO,
} from "../constants/dockSeed";
import { drawSpeechBubbleIcon } from "../utils/drawDockIcons";

/** 독 스케치에서 입자가 맡는 부분. 알약 윤곽, 채팅 아이콘, 녹음 구슬이에요 */
type SketchPart = "frame" | "icon" | "orb";

interface SeedParticle {
  /** 막대 번호. 0이 왼쪽, 마지막이 오른쪽 끝(지금)이에요 */
  column: number;
  /** 막대 안의 세로 위치(-1~1). 랜딩 파형과 같아요 */
  level: number;
  jitter: number;
  /** 입자마다 고정된 0~1 난수 */
  seed: number;
  size: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
  homeX: number;
  homeY: number;
  /** 올렸을 때 모일 독 모양에서의 자리(캔버스 가로 가운데·독 자리 세로 가운데 기준)와 맡은 부분 */
  sketchX: number;
  sketchY: number;
  part: SketchPart;
  /** 녹음 칩 자리에서 구슬을 이룰 때의 구 위 각도와 농담(0~4). 열린 독의 구슬과 같은 계산이에요 */
  u: number;
  v: number;
  shade: number;
}

/** 독의 배치. 입자가 스케치할 알약 크기와 채팅·녹음 칸의 가운데(캔버스 가로 가운데 기준 px)예요 */
export interface SeedSplitTargets {
  barWidth: number;
  barHeight: number;
  chatOffsetX: number;
  /** 녹음 칸을 아직 재지 못했으면 `null` */
  recordOffsetX: number | null;
}

interface DockSeedPalette {
  /** 먹색 세 단계. 녹음 화면 파형과 같은 색으로 파형과 녹음 칩 자리의 구슬에 써요 */
  wave: [string, string, string];
  /** 어두운 알약 위의 밝은 세 단계(아이콘 진한, 아이콘 옅은, 윤곽) */
  light: [string, string, string];
}

interface UseDockSeedParams {
  canvasRef: RefObject<HTMLCanvasElement | null>;
  /** `true`면 입자가 독 자리로 갈라져 가고, 곧 사라져요 */
  isOpen: boolean;
  analyser: AnalyserNode | null;
  isActive: boolean;
  splitTargets: SeedSplitTargets;
  palette: DockSeedPalette;
}

/** 독 자리의 세로 가운데가 캔버스 아래에서 떨어진 거리(px). 접힌 독 높이의 절반에 캔버스가 더 내려간 만큼이에요 */
const SLOT_CENTER_BOTTOM = SEED_WAVE_BOTTOM;

/**
 * 다른 화면에서 녹음이 이어질 때 접힌 독 자리에 흐르는 입자 파형을 그려요.
 *
 * 녹음 화면 파형과 같은 먹색 파형이 소리 크기의 흐름을 보여 줘요. 모양은 랜딩 녹음 장면의 96칸 막대 그대로이고, 높이만 그 칸 시각의 소리 크기예요.
 * 오른쪽 끝이 지금이고 왼쪽으로 흘러가며, 조용하면 가는 선, 말하면 그 순간만 커져요. 일시정지거나 다른 탭의 녹음이라 소리를 읽을 수 없으면 평평한 선이에요.
 * 올리면 입자가 포인터를 피하고(95px), 누르면 파동이 퍼져요.
 * `isOpen`이 되면 입자가 독 모양(알약 윤곽, 채팅 아이콘, 녹음 칩 자리)으로 모이고 그 뒤에서 진짜 독이 나타나면 사라져요. 닫히면 그 자리에서 다시 파형이 돼요.
 * 숨어 있는 동안은 그리지 않아요.
 * 프레임이 자주 느려지면 입자 절반만 그리고 흔들림을 빼서 어느 기기에서도 버벅이지 않게 해요.
 * 움직임 줄이기 설정에서는 이 입자를 쓰지 않으므로 여기서는 고려하지 않아요.
 */
export const useDockSeed = ({
  canvasRef,
  isOpen,
  analyser,
  isActive,
  splitTargets,
  palette,
}: UseDockSeedParams) => {
  const paramsRef = useRef({ isOpen, analyser, isActive, splitTargets, palette });
  paramsRef.current = { isOpen, analyser, isActive, splitTargets, palette };

  const pointerRef = useRef({ x: -9999, y: -9999 });
  const pulsesRef = useRef<PulseWave[]>([]);
  const pulseBoostRef = useRef({ at: 0, boost: 1 });
  const wakeRef = useRef<() => void>(() => {});

  const setPointer = useCallback((x: number, y: number) => {
    pointerRef.current = { x, y };
  }, []);
  const clearPointer = useCallback(() => {
    pointerRef.current = { x: -9999, y: -9999 };
  }, []);
  /** 랜딩 `addPulse`와 같아요 */
  const pulse = useCallback((x: number, y: number, canvasWidth: number, canvasHeight: number) => {
    const now = Date.now();
    const boost = pulseBoostRef.current;
    boost.boost = nextPulseBoost({ lastPulseAt: boost.at, boost: boost.boost, now });
    boost.at = now;
    pulsesRef.current.push(createPulse({ x, y, width: canvasWidth, height: canvasHeight, boost: boost.boost }));
    wakeRef.current();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context || prefersReducedMotion()) return;

    const pixelRatio = Math.min(window.devicePixelRatio || 1, 1.5);
    const history = createLevelHistory(SEED_WAVE_COLUMNS, SEED_WAVE_INTERVAL_MS);
    const amplitudes = new Float32Array(SEED_WAVE_COLUMNS);
    let particles: SeedParticle[] = [];
    let width = 0;
    let height = 0;
    let isMobile = false;
    let frame = Math.random() * 1000;
    let level = 0;
    let phase = 0;
    let blend = 0;
    let alpha = 1;
    let alphaGoal = 1;
    let fadeTimer = 0;
    let animation = 0;
    let sketchKey = "";
    let lastFrameAt = 0;
    let slowFrames = 0;
    let isLite = false;
    let shouldSnap = false;
    /** 새 자리에 고정한 채 그리지 않고 기다리는 프레임 수. 독 배치를 다시 잰 뒤 살아나게 해요 */
    let holdFrames = 0;

    const resize = () => {
      const nextWidth = canvas.clientWidth;
      const nextHeight = canvas.clientHeight;
      if (!nextWidth || !nextHeight || (nextWidth === width && nextHeight === height)) return;
      width = nextWidth;
      height = nextHeight;
      isMobile = width < SEED_MOBILE_BREAKPOINT;
      canvas.width = Math.round(width * pixelRatio);
      canvas.height = Math.round(height * pixelRatio);
      const count = isMobile ? SEED_PARTICLE_COUNT.mobile : SEED_PARTICLE_COUNT.desktop;
      particles = Array.from({ length: count }, (_, index) => ({
        column: index % SEED_WAVE_COLUMNS,
        level: Math.random() * 2 - 1,
        jitter: (Math.random() - 0.5) * 0.42,
        seed: Math.random(),
        size: 0.8 + Math.random() * 0.7,
        x: width / 2 + (Math.random() - 0.5) * 300,
        y: height - SEED_WAVE_BOTTOM + (Math.random() - 0.5) * 40,
        vx: 0,
        vy: 0,
        homeX: 0,
        homeY: 0,
        sketchX: 0,
        sketchY: 0,
        part: "frame",
        u: Math.random() * Math.PI * 2,
        v: Math.acos(2 * Math.random() - 1),
        shade: 1,
      }));
      sketchKey = "";
    };

    /**
     * 올렸을 때 모일 독 모양의 자리를 뽑아 입자에 나눠 줘요. 알약 윤곽은 랜딩 말풍선 윤곽과 같은 밀도(둘레 1px당 1.1개)로,
     * 채팅 아이콘은 실제 아이콘 모양으로, 녹음 칩 자리는 가운데가 빽빽한 구름으로 둬요. 자리는 열린 독의 배치에서 잰 값이에요.
     */
    const assignSketch = () => {
      const { splitTargets: layout } = paramsRef.current;
      const key = `${layout.barWidth}:${layout.barHeight}:${layout.chatOffsetX}:${layout.recordOffsetX}`;
      if (key === sketchKey || !layout.barWidth) return;
      const targets: { x: number; y: number; part: SketchPart }[] = [];

      const radius = layout.barHeight / 2;
      const perimeter = 2 * (layout.barWidth + layout.barHeight - 4 * radius) + 2 * Math.PI * radius;
      const frameCount = Math.round(perimeter * 0.9);
      for (let i = 0; i < frameCount; i++) {
        const point = roundedRectPoint({ width: layout.barWidth, height: layout.barHeight, radius, distance: i / frameCount });
        const offset = gaussian() * 1.6;
        targets.push({ x: point.x + point.nx * offset - layout.barWidth / 2, y: point.y + point.ny * offset - layout.barHeight / 2, part: "frame" });
      }

      sampleShapeTargets({ draw: drawSpeechBubbleIcon, size: SEED_ICON_SIZE, step: 0.75 }).forEach((target) => {
        targets.push({ x: layout.chatOffsetX + target.x, y: target.y, part: "icon" });
      });

      if (layout.recordOffsetX !== null) {
        // 구슬은 가운데만 정해 두고 매 프레임 구 위의 자리를 계산해요(열린 독의 구슬과 같은 방식)
        const orbCount = Math.round(SEED_ORB_RADIUS * SEED_ORB_RADIUS * 3.2);
        for (let i = 0; i < orbCount; i++) targets.push({ x: layout.recordOffsetX, y: 0, part: "orb" });
      }
      if (!targets.length) return;

      // 자리가 입자보다 많으면 고르게 골라 쓰고, 적으면 둘레에 살짝 흩어 채워요(랜딩 fitTargets와 같은 생각)
      const spread = targets.length < particles.length ? 1.2 : 0;
      particles.forEach((particle, index) => {
        const target = targets[Math.floor((index * targets.length) / particles.length) % targets.length];
        particle.sketchX = target.x + (Math.random() - 0.5) * spread;
        particle.sketchY = target.y + (Math.random() - 0.5) * spread;
        particle.part = target.part;
      });
      sketchKey = key;
    };

    const placeParticles = () => {
      const centerX = width / 2;
      const centerY = height - SEED_WAVE_BOTTOM;
      const slotY = height - SLOT_CENTER_BOTTOM;
      assignSketch();
      // 막대 모양은 랜딩 공식 그대로, 배율만 그 칸 시각의 소리 크기예요. 조용하면 2.5px 선이에요
      for (let column = 0; column < SEED_WAVE_COLUMNS; column++) {
        const scale = sampleLevelHistory({ history, position: (column + 0.5) / SEED_WAVE_COLUMNS, phase });
        amplitudes[column] = waveAmplitude({ column, columns: SEED_WAVE_COLUMNS, frame, maxAmplitude: SEED_WAVE_AMPLITUDE, scale, taper: false });
      }

      const waveWidth = Math.min(SEED_WAVE_MAX_WIDTH, width * SEED_WAVE_WIDTH_RATIO);
      const startX = centerX - waveWidth / 2;
      const columnWidth = waveWidth / SEED_WAVE_COLUMNS;

      // 녹음 칩 자리의 구슬: 왼쪽 위에서 빛이 들어오는 구가 천천히 돌아요. 열린 독의 구슬과 같은 계산이에요
      const rotation = frame * 0.006;
      const tilt = 0.38;
      particles.forEach((particle) => {
        let sketchX = centerX + particle.sketchX;
        let sketchY = slotY + particle.sketchY;
        if (particle.part === "orb") {
          const breathe = 1 + 0.05 * Math.sin(frame * 0.028 + particle.seed * 6.28);
          const r = SEED_ORB_RADIUS * breathe * Math.pow(particle.seed, 0.42);
          const sinV = Math.sin(particle.v);
          const x = sinV * Math.cos(particle.u + rotation);
          const y0 = Math.cos(particle.v);
          const z0 = sinV * Math.sin(particle.u + rotation);
          const y = y0 * Math.cos(tilt) - z0 * Math.sin(tilt);
          const z = y0 * Math.sin(tilt) + z0 * Math.cos(tilt);
          sketchX += x * r;
          sketchY += y * r;
          const light = 0.5 + 0.5 * (-x * 0.45 - y * 0.78 + z * 0.3) * (0.6 + 0.4 * (r / SEED_ORB_RADIUS));
          particle.shade = z < -0.3 ? 4 : light > 0.78 ? 0 : light > 0.62 ? 1 : light > 0.46 ? 2 : light > 0.3 ? 3 : 4;
        }
        // 파형이었다가 열리면 독 모양 자리로 모여요. 막대는 랜딩처럼 제자리에 있고 그 자리 시각의 소리만큼 퍼져요
        const waveX = startX + (particle.column + 0.5 + particle.jitter) * columnWidth;
        const waveY = centerY + particle.level * amplitudes[particle.column];
        particle.homeX = waveX + (sketchX - waveX) * blend;
        particle.homeY = waveY + (sketchY - waveY) * blend;
      });
    };

    /** 입자 하나의 색. 파형은 밝은 바탕 위라 먹색이고, 독 모양으로 모이면 어두운 알약 위라 밝은 입자예요 */
    const colorOf = (particle: SeedParticle, colors: DockSeedPalette) => {
      if (blend < 0.55) {
        const strength = Math.abs(particle.level);
        return colors.wave[strength < 0.45 ? 0 : strength < 0.8 ? 1 : 2];
      }
      switch (particle.part) {
        case "orb":
          return colors.wave[Math.min(2, particle.shade)];
        case "icon":
          return colors.light[particle.seed < 0.7 ? 0 : 1];
        default:
          return colors.light[2];
      }
    };

    const tick = (now: number) => {
      resize();
      // 화면 주사율과 상관없이 같은 속도로 움직이게 흐른 시간만큼 프레임을 더해요
      frame += frameStep(now, lastFrameAt);
      // 느린 프레임이 잦으면 가볍게 그려요. 한 번 가벼워지면 그 세션에선 되돌리지 않아 깜빡이지 않아요
      if (lastFrameAt && now - lastFrameAt > SEED_SLOW_FRAME_MS) slowFrames++;
      else slowFrames = Math.max(0, slowFrames - 1);
      if (!isLite && slowFrames > 12) isLite = true;
      lastFrameAt = now;

      const { isOpen: open, analyser: node, isActive: active, palette: colors } = paramsRef.current;
      // 일시정지거나 다른 탭의 녹음이라 소리를 읽을 수 없으면 이력을 비워 평평한 선이에요
      if (node && active) {
        level = smoothLevel({ previous: level, next: measureAnalyserLevel(node), attack: 0.6, release: 0.3 });
        pushLevel(history, level, now);
      } else {
        level = 0;
        clearLevelHistory(history);
      }
      phase = levelHistoryPhase(history, now);
      blend += ((open ? 1 : 0) - blend) * 0.2;
      if (blend < 0.01) blend = 0;
      alpha += (alphaGoal - alpha) * (alphaGoal < alpha ? 0.3 : 0.16);
      placeParticles();

      if (shouldSnap) {
        shouldSnap = false;
        holdFrames = 3;
        if (!open) alpha = 0;
      }
      if (holdFrames > 0) {
        holdFrames--;
        particles.forEach((particle) => {
          particle.x = particle.homeX;
          particle.y = particle.homeY;
          particle.vx = 0;
          particle.vy = 0;
        });
      }

      const pulses = pulsesRef.current;
      advancePulses(pulses);
      const pointer = pointerRef.current;
      const hasPointer = pointer.x > -1000 && blend < 0.5;

      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
      context.clearRect(0, 0, width, height);
      const isHidden = alpha < 0.02 || holdFrames > 0;
      if (!isHidden) {
        context.globalAlpha = alpha;
        const step = isLite ? 2 : 1;
        let lastColor = "";
        for (let i = 0; i < particles.length; i += step) {
          const particle = particles[i];
          let forceX = 0;
          let forceY = 0;
          if (hasPointer) {
            const repulsion = pointerRepulsion({ x: particle.x, y: particle.y, pointerX: pointer.x, pointerY: pointer.y });
            forceX += repulsion.fx;
            forceY += repulsion.fy;
          }
          if (blend < 0.5) {
            for (const wave of pulses) {
              const force = pulseForce({ x: particle.x, y: particle.y, pulse: wave });
              forceX += force.fx;
              forceY += force.fy;
            }
          }
          stepSpring({
            particle,
            homeX: particle.homeX,
            homeY: particle.homeY,
            spring: 0.09,
            damping: 0.84,
            // 조용할 땐 거의 멈춰 있고 소리가 들어올수록 조금 흔들려요. 독 모양으로 모여 있을 땐 살짝 살아 있어요
            jitter: isLite ? 0 : blend > 0.5 ? 0.12 : 0.04 + level * 0.2,
            forceX,
            forceY,
          });
          const color = colorOf(particle, colors);
          if (color !== lastColor) {
            context.fillStyle = color;
            lastColor = color;
          }
          context.fillRect(particle.x, particle.y, particle.size, particle.size);
        }
        context.globalAlpha = 1;
      }

      // 숨어서 할 일이 없으면 멈춰요. 파동이나 열림·닫힘이 생기면 다시 깨워요. 다시 살아나는 중(alphaGoal 1)에는 멈추지 않아요
      const isSettled = isHidden && alphaGoal === 0 && pulses.length === 0;
      animation = isSettled ? 0 : requestAnimationFrame(tick);
    };

    /** 열림 상태에 맞춰 보일지 정하고, 멈춰 있던 반복을 다시 돌려요 */
    const wake = () => {
      clearTimeout(fadeTimer);
      // 숨어 있다가 다시 보일 때는 옛 자리에서 날아오지 않고 새 자리에서 살아나요(화면을 옮긴 뒤 등)
      if (!paramsRef.current.isOpen && alpha < 0.05) shouldSnap = true;
      if (paramsRef.current.isOpen) {
        fadeTimer = window.setTimeout(() => {
          alphaGoal = 0;
        }, SEED_FADE_DELAY_MS);
      } else {
        alphaGoal = 1;
      }
      if (!animation) {
        lastFrameAt = 0;
        animation = requestAnimationFrame(tick);
      }
    };
    wakeRef.current = wake;

    resize();
    wake();

    return () => {
      cancelAnimationFrame(animation);
      clearTimeout(fadeTimer);
      wakeRef.current = () => {};
    };
  }, [canvasRef]);

  // 열리고 닫힐 때 엔진을 깨워 갈라지거나 모이게 해요
  useEffect(() => {
    wakeRef.current();
  }, [isOpen]);

  return { setPointer, clearPointer, pulse };
};
