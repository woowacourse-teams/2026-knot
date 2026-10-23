import {
  clearLevelHistory,
  createLevelHistory,
  frameStep,
  levelHistoryPhase,
  prefersReducedMotion,
  pushLevel,
  sampleLevelHistory,
  smoothLevel,
  stepSpring,
  waveAmplitude,
} from "@utils/particleMotion";
import { measureAnalyserLevel } from "@utils/measureAudioLevel";
import { sampleShapeTargets } from "@utils/sampleShapeTargets";
import { useCallback, useEffect, useRef, type RefObject } from "react";

import {
  ARROW_ICON_PATH,
  BUBBLE_DOTS,
  BUBBLE_ICON_PATH,
  BUBBLE_OUTLINE_SHARE,
  DISC_DOT_SCALE,
  DISC_RADIUS_SCALE,
  DISC_SETTLED_MOTION,
  MIC_ICON_PATHS,
  MIC_SHAPE_SCALE,
  ORB_DAMPING,
  ORB_DOT_MIN,
  ORB_DOT_RANGE,
  ORB_PARTICLE_COUNT,
  ORB_SPRING,
  WAVE_DOT_SCALE,
  WAVE_SHAPE_COLUMNS,
  WAVE_SHAPE_FLAT_AMPLITUDE,
  WAVE_SHAPE_INTERVAL_MS,
  WAVE_SHAPE_SPAN_SCALE,
} from "../constants/particleOrb";
import type { ParticleOrbPalette, ParticleOrbShape, ParticleOrbTone } from "../types/particleOrb";

interface OrbParticle {
  /** 구 위의 각도. 구름 모양일 때의 자리를 정해요 */
  u: number;
  v: number;
  /** 입자마다 고정된 0~1 난수. 안쪽·바깥쪽, 색, 파형 참여 여부를 정해요 */
  seed: number;
  size: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
  /** 이번 프레임에 돌아갈 자리 */
  homeX: number;
  homeY: number;
  /** 앞뒤 깊이(-1~1). 뒤쪽은 작고 옅게 그려요 */
  depth: number;
  /** 가운데에서 얼마나 떨어졌는지(0~1). 가장자리는 작게 그려요 */
  edge: number;
  bucket: number;
  /** 파형에서의 세로 위치(-1~1)와 가로 흔들림 */
  /** `wave` 모양에서의 세로 위치(-1~1). 가운데에 몰린 난수라 파형 가운데가 빽빽하고 끝이 성겨요 */
  waveLevel: number;
  level: number;
  jitter: number;
  /** 마이크 모양에서의 자리 */
  micX: number;
  micY: number;
  micBucket: number;
  /** 말풍선 모양에서 점 몇 번째를 맡는지(-1이면 윤곽)와 점 안에서의 흩어짐 */
  dot: number;
  dotX: number;
  dotY: number;
}

interface Pulse {
  radius: number;
  max: number;
  strength: number;
}

interface UseParticleOrbParams {
  canvasRef: RefObject<HTMLCanvasElement | null>;
  radius: number;
  shape: ParticleOrbShape;
  tone: ParticleOrbTone;
  analyser: AnalyserNode | null;
  isActive: boolean;
  /** `true`면 무언가를 기다리는 중이라 구슬이 빠르게 돌며 모였다 풀렸다 해요 */
  isWaiting: boolean;
  idlePalette: ParticleOrbPalette;
  neutralPalette: ParticleOrbPalette;
  /** `disc` 모양의 단색 원과 테두리 고리 색. `r,g,b` 꼴이에요 */
  discRgb: string;
}

/** 구가 보는 사람 쪽으로 기울어진 각도. 위쪽이 조금 보이게 해요 */
const TILT = 0.38;

const createParticles = (count: number): OrbParticle[] =>
  Array.from({ length: count }, () => ({
    u: Math.random() * Math.PI * 2,
    v: Math.acos(2 * Math.random() - 1),
    seed: Math.random(),
    size: ORB_DOT_MIN + Math.random() * ORB_DOT_RANGE,
    x: 0,
    y: 0,
    vx: 0,
    vy: 0,
    homeX: 0,
    homeY: 0,
    depth: 0,
    edge: 0,
    bucket: 1,
    waveLevel: Math.random() + Math.random() - 1,
    level: Math.random() * 2 - 1,
    jitter: (Math.random() - 0.5) * 0.42,
    micX: 0,
    micY: 0,
    micBucket: 0,
    dot: -1,
    // 점 안에서는 가운데가 빽빽한 동그란 구름이 되도록 극좌표로 흩어요
    ...(() => {
      const r = Math.sqrt(Math.random());
      const a = Math.random() * Math.PI * 2;
      return { dotX: Math.cos(a) * r, dotY: Math.sin(a) * r };
    })(),
  }));

const drawMic = (context: CanvasRenderingContext2D) => {
  context.lineWidth = 1.05;
  MIC_ICON_PATHS.forEach((path) => context.stroke(new Path2D(path)));
};

const drawArrow = (context: CanvasRenderingContext2D) => {
  context.lineWidth = 1.6;
  context.stroke(new Path2D(ARROW_ICON_PATH));
};

const drawBubble = (context: CanvasRenderingContext2D) => {
  context.lineWidth = 1.3;
  context.stroke(new Path2D(BUBBLE_ICON_PATH));
};

/**
 * 입자 구슬을 캔버스에 매 프레임 그려요.
 *
 * 입자 수백 개가 구름 같은 구를 이루고, `shape`가 `mic`면 마이크, `arrow`면 오른쪽 화살표, `bubble`이면 점 세 개가 든 말풍선으로 모여요.
 * `typing`은 말풍선의 점들이 입력 중처럼 차례로 튀어요.
 * `disc`는 입자가 단색 원 안에 빽빽이 모이고 그 위에 테두리 고리를 둘러 한눈에 녹음 버튼으로 읽혀요. 입자가 조용히 모여야 단색 원이 차오르고,
 * 다른 모양으로 바뀌면 단색 원이 먼저 사라져 입자가 분해되는 것이 보여요. `scatter()`는 그때 입자를 바깥으로 살짝 흩뜨려요.
 * `wave`는 입자 전부가 구름처럼 흩어진 채 소리 크기의 흐름을 이뤄요. 오른쪽 끝이 지금이고 왼쪽으로 흘러가며, 조용하면 가는 선, 말하면 그 순간만 커져요. 일시정지면 평평한 선이에요.
 * `isWaiting`이면 구름이 빠르게 돌며 모였다 풀렸다 해요. 누른 뒤 결과를 기다리는 동안 멈춰 보이지 않게 해요.
 * 매 프레임 바뀌는 값이라 React 상태로 다루지 않고, 바뀌는 props는 ref로 받아 다음 프레임부터 반영해요.
 * 움직임 줄이기 설정에서는 회전·숨쉬기·흔들림을 멈추고 소리와 모양만 반영해요.
 */
export const useParticleOrb = ({
  canvasRef,
  radius,
  shape,
  tone,
  analyser,
  isActive,
  isWaiting,
  idlePalette,
  neutralPalette,
  discRgb,
}: UseParticleOrbParams) => {
  const paramsRef = useRef({ radius, shape, tone, analyser, isActive, isWaiting, idlePalette, neutralPalette, discRgb });
  paramsRef.current = { radius, shape, tone, analyser, isActive, isWaiting, idlePalette, neutralPalette, discRgb };

  const pulsesRef = useRef<Pulse[]>([]);
  const pulse = useCallback(() => {
    const { radius: r } = paramsRef.current;
    pulsesRef.current.push({ radius: 0, max: r * 2.4, strength: 2.2 });
  }, []);

  // 다음 프레임에 입자 전부를 바깥으로 한 번 밀어요. 값은 프레임마다 바뀌어 ref로 둬요
  const scatterRef = useRef(false);
  const scatter = useCallback(() => {
    scatterRef.current = true;
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;

    const reduced = prefersReducedMotion();
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    const particles = createParticles(ORB_PARTICLE_COUNT);
    const waveHistory = createLevelHistory(WAVE_SHAPE_COLUMNS, WAVE_SHAPE_INTERVAL_MS);
    const amplitudes = new Float32Array(WAVE_SHAPE_COLUMNS);
    let wavePhase = 0;
    let width = 0;
    let height = 0;
    let frame = Math.random() * 1000;
    let lastFrameAt = 0;
    let level = 0;
    let hover = 0;
    let wait = 0;
    let spin = 0;
    /** `disc`의 단색 원과 고리가 얼마나 차올랐는지(0~1). 입자가 모이면 차오르고 다른 모양이 되면 먼저 빠져요 */
    let solid = 0;
    /** 지난 프레임 입자들의 평균 속도. 말풍선·원이 자리를 잡았는지 볼 때 써요 */
    let motion = Infinity;
    let assignedShape: ParticleOrbShape | null = null;
    let animation = 0;

    const resize = () => {
      const nextWidth = canvas.clientWidth;
      const nextHeight = canvas.clientHeight;
      if (!nextWidth || !nextHeight || (nextWidth === width && nextHeight === height)) return;
      width = nextWidth;
      height = nextHeight;
      canvas.width = Math.round(width * pixelRatio);
      canvas.height = Math.round(height * pixelRatio);
      particles.forEach((particle) => {
        particle.x = width / 2 + (Math.random() - 0.5) * width;
        particle.y = height / 2 + (Math.random() - 0.5) * height;
      });
    };

    /** 입자를 x 순서로 줄 세워 아이콘 자리에 배정해요. 랜딩의 morph 배정과 같아 가까운 자리로 자연스럽게 흘러요. */
    const assignShape = (shape: Exclude<ParticleOrbShape, "cloud">) => {
      const isBubble = shape === "bubble" || shape === "typing";
      const targets = sampleShapeTargets({
        draw: shape === "mic" ? drawMic : shape === "arrow" ? drawArrow : drawBubble,
        size: paramsRef.current.radius * 2 * (shape === "mic" ? MIC_SHAPE_SCALE : isBubble ? 1.25 : 1.1),
        step: 1 / 3,
      });
      if (!targets.length) return;
      // 말풍선은 윤곽을 맡는 입자와 점 세 개를 맡는 입자로 나눠요
      const members = isBubble ? particles.filter((p) => p.seed < BUBBLE_OUTLINE_SHARE) : particles;
      particles.forEach((p) => {
        p.dot = isBubble && p.seed >= BUBBLE_OUTLINE_SHARE ? Math.min(2, Math.floor(((p.seed - BUBBLE_OUTLINE_SHARE) / (1 - BUBBLE_OUTLINE_SHARE)) * 3)) : -1;
      });
      const order = members.map((_, i) => i).sort((a, b) => members[a].x - members[b].x);
      const targetOrder = targets.map((_, i) => i).sort((a, b) => targets[a].x - targets[b].x);
      order.forEach((memberIndex, rank) => {
        const particle = members[memberIndex];
        const target = targets[targetOrder[Math.floor((rank * targets.length) / order.length)]];
        particle.micX = target.x + (Math.random() - 0.5) * 0.2;
        particle.micY = target.y + (Math.random() - 0.5) * 0.2;
        // 마이크는 녹음 전 원과 같은 붉은색으로 모여요. 옅은 분홍은 말풍선 윤곽에만 써요
        particle.micBucket = !isBubble ? 2 : particle.seed < 0.8 * BUBBLE_OUTLINE_SHARE ? 0 : 1;
      });
    };

    const placeParticles = () => {
      const { radius: baseRadius, shape: nextShape } = paramsRef.current;
      const centerX = width / 2;
      const centerY = height / 2;

      if (nextShape === "disc") {
        // 녹음 버튼: 안쪽 원을 입자로 빽빽이 채워요. 단색 원이 차오르면 입자는 그 위에 숨고, 분해될 때 드러나요
        assignedShape = null;
        const discRadius = baseRadius * DISC_RADIUS_SCALE;
        particles.forEach((particle) => {
          const r = discRadius * Math.sqrt(particle.seed);
          const x = Math.cos(particle.u) * r;
          const y = Math.sin(particle.u) * r;
          particle.homeX = centerX + x;
          particle.homeY = centerY + y;
          particle.depth = 1;
          particle.edge = r / discRadius;
          // 단색 원과 같은 붉은색이라 모여 있을 땐 원에 숨고, 가장자리만 조금 어두워 분해될 때 입체감이 나요
          particle.bucket = particle.edge < 0.9 ? 2 : 3;
        });
        return;
      }

      if (nextShape === "wave") {
        // 녹음 칩: 입자 전부가 소리 크기의 흐름을 이뤄요. 오른쪽 끝이 지금이고, 조용하면 가는 선, 말한 순간만 캔버스 끝까지 커져요
        assignedShape = null;
        const span = Math.min(width - 4, baseRadius * WAVE_SHAPE_SPAN_SCALE);
        const limit = height / 2 - 1;
        // 막대 모양은 랜딩 공식 그대로, 배율만 그 칸 시각의 소리 크기예요. 작은 칩이라 칸 사이는 매끄럽게 이어요
        for (let column = 0; column < WAVE_SHAPE_COLUMNS; column++) {
          const scale = sampleLevelHistory({ history: waveHistory, position: (column + 0.5) / WAVE_SHAPE_COLUMNS, phase: wavePhase });
          amplitudes[column] = waveAmplitude({ column, columns: WAVE_SHAPE_COLUMNS, frame: frame * 1.5, maxAmplitude: limit, scale, taper: false, floor: WAVE_SHAPE_FLAT_AMPLITUDE });
        }
        // 입자가 가로로 고르게 흩어진 채 파형 윤곽을 따라요. 가운데가 빽빽하고 위아래 끝은 성겨 구름 같은 파형이에요
        particles.forEach((particle) => {
          const position = particle.seed * WAVE_SHAPE_COLUMNS - 0.5;
          const left = Math.max(0, Math.min(WAVE_SHAPE_COLUMNS - 1, Math.floor(position)));
          const right = Math.min(WAVE_SHAPE_COLUMNS - 1, left + 1);
          const mix = Math.max(0, Math.min(1, position - left));
          const amplitude = amplitudes[left] * (1 - mix) + amplitudes[right] * mix;
          particle.homeX = centerX - span / 2 + particle.seed * span + particle.jitter;
          particle.homeY = centerY + particle.waveLevel * amplitude;
          particle.depth = 1;
          particle.edge = 0.3;
          const strength = Math.abs(particle.waveLevel);
          // 흰 심은 가늘게, 바깥으로 갈수록 청록이 짙어져 뿌연 안개가 아니라 파형으로 읽혀요
          particle.bucket = strength < 0.18 ? 0 : strength < 0.4 ? 1 : strength < 0.65 ? 2 : strength < 0.88 ? 3 : 5;
        });
        return;
      }

      if (nextShape !== "cloud") {
        // 말풍선과 입력 중은 같은 자리라 서로 바뀔 때 다시 배정하지 않아요
        const isBubble = nextShape === "bubble" || nextShape === "typing";
        const assignKey = isBubble ? "bubble" : nextShape;
        if (assignedShape !== assignKey) {
          assignShape(nextShape);
          assignedShape = assignKey;
        }
        const wobble = reduced ? 0 : Math.sin(frame * 0.05) * 0.25;
        const dotScale = (baseRadius * 2 * 1.25) / 24;
        particles.forEach((particle) => {
          if (particle.dot >= 0) {
            // 점 세 개: 가운데가 빽빽한 작은 구름. 입력 중이면 차례로 통통 튀어요
            const dot = BUBBLE_DOTS[particle.dot];
            const bounce = nextShape === "typing" && !reduced ? Math.max(0, Math.sin(frame * 0.16 - particle.dot * 0.9)) * 2.2 : 0;
            particle.homeX = centerX + (dot.x - 12) * dotScale + particle.dotX * 1.4;
            particle.homeY = centerY + (dot.y - 12) * dotScale + particle.dotY * 1.4 + wobble - bounce;
            particle.depth = 1;
            particle.edge = 0.5;
            particle.bucket = 0;
            return;
          }
          particle.homeX = centerX + particle.micX;
          particle.homeY = centerY + particle.micY + wobble;
          particle.depth = 1;
          particle.edge = 0.75;
          particle.bucket = particle.micBucket;
        });
        return;
      }
      assignedShape = null;

      // 기다리는 동안은 빠르게 돌며 숨을 크게 쉬어, 누른 뒤 멈춰 있지 않고 무언가 준비되고 있다고 느끼게 해요
      spin += 0.006 + level * 0.018 + hover * 0.004 + wait * 0.03;
      const rotation = reduced ? 0.6 : spin;
      const gather = reduced ? 0 : wait * 0.14 * (0.5 + 0.5 * Math.sin(frame * 0.11));
      const orbRadius = baseRadius * (1 + hover * 0.08 + level * 0.28 - gather);
      particles.forEach((particle) => {
        const breathe = reduced
          ? 1
          : 1 + 0.05 * Math.sin(frame * 0.028 + particle.seed * 6.28) + level * 0.22 * Math.sin(frame * 0.21 + particle.seed * 9);
        // 가운데가 빽빽하고 가장자리로 갈수록 성긴 구름 같은 구슬
        const r = orbRadius * breathe * Math.pow(particle.seed, 0.42);
        const sinV = Math.sin(particle.v);
        const x = sinV * Math.cos(particle.u + rotation);
        const y0 = Math.cos(particle.v);
        const z0 = sinV * Math.sin(particle.u + rotation);
        const y = y0 * Math.cos(TILT) - z0 * Math.sin(TILT);
        const z = y0 * Math.sin(TILT) + z0 * Math.cos(TILT);
        particle.homeX = centerX + x * r;
        particle.homeY = centerY + y * r;
        particle.depth = z;
        particle.edge = r / orbRadius;
        // 왼쪽 위에서 빛이 들어와 오른쪽 아래가 그늘이에요
        const light = 0.5 + 0.5 * (-x * 0.45 - y * 0.78 + z * 0.3) * (0.6 + 0.4 * particle.edge);
        if (z < -0.3) particle.bucket = 5;
        else particle.bucket = light > 0.78 ? 0 : light > 0.62 ? 1 : light > 0.46 ? 2 : light > 0.3 ? 3 : 4;
      });
    };

    /** `disc`의 단색 원과 테두리 고리. 입자가 모인 만큼 차오르고, 고리는 녹음 표시처럼 천천히 맥박을 쳐요 */
    const drawDisc = () => {
      if (solid < 0.01) return;
      const { radius: baseRadius, discRgb: rgb } = paramsRef.current;
      const centerX = width / 2;
      const centerY = height / 2;
      const beat = reduced ? 1 : 0.8 + 0.2 * Math.sin(frame * 0.04);
      context.fillStyle = `rgba(${rgb},${solid})`;
      context.beginPath();
      context.arc(centerX, centerY, baseRadius * DISC_RADIUS_SCALE, 0, Math.PI * 2);
      context.fill();
      context.strokeStyle = `rgba(${rgb},${solid * beat})`;
      context.lineWidth = 1.5;
      context.beginPath();
      context.arc(centerX, centerY, baseRadius - 0.75, 0, Math.PI * 2);
      context.stroke();
    };

    let isOddFrame = false;
    const tick = (now: number) => {
      resize();
      const { analyser: node, isActive: active, tone: nextTone, shape: nextShape } = paramsRef.current;
      // 가만히 떠 있는 구름, 자리를 잡은 말풍선·녹음 버튼, 일시정지한 파형은 30fps면 충분해요. 모양이 바뀌는 중이거나 소리·파동이 있을 땐 60fps예요.
      // 말풍선과 녹음 버튼은 독이 늘 보여 주는 모양이라, 여기서 빼면 독이 있는 모든 화면에서 매 프레임 그리게 돼요
      const isSettled = motion < 0.15;
      const isResting =
        (nextShape === "cloud" && hover < 0.01) ||
        ((nextShape === "bubble" || nextShape === "disc") && isSettled) ||
        (nextShape === "wave" && !active && isSettled);
      const isCalm =
        isResting &&
        level < 0.01 &&
        wait < 0.01 &&
        !paramsRef.current.isWaiting &&
        !scatterRef.current &&
        pulsesRef.current.length === 0 &&
        !(node && active);
      isOddFrame = !isOddFrame;
      if (isCalm && isOddFrame) {
        animation = requestAnimationFrame(tick);
        return;
      }
      // 화면 주사율과 상관없이 같은 속도로 움직이게 흐른 시간만큼 프레임을 더해요
      frame += frameStep(now, lastFrameAt);
      lastFrameAt = now;
      hover += ((nextShape !== "cloud" ? 1 : 0) - hover) * 0.12;
      wait += ((paramsRef.current.isWaiting ? 1 : 0) - wait) * 0.1;
      // 일시정지거나 소리를 읽을 수 없으면 이력을 비워 파형이 평평한 선이에요
      if (node && active) {
        level = smoothLevel({ previous: level, next: measureAnalyserLevel(node), attack: 0.6, release: 0.3 });
        pushLevel(waveHistory, level, now);
      } else {
        level = 0;
        clearLevelHistory(waveHistory);
      }
      wavePhase = levelHistoryPhase(waveHistory, now);
      placeParticles();

      // 단색 원은 입자가 조용히 모여야 차오르고, 다른 모양이 되면 입자보다 먼저 빠져 분해가 보여요
      const solidTarget = nextShape === "disc" && motion < DISC_SETTLED_MOTION ? 1 : 0;
      solid += (solidTarget - solid) * (solidTarget ? 0.1 : 0.3);

      const pulses = pulsesRef.current;
      for (let i = pulses.length - 1; i >= 0; i--) {
        pulses[i].radius += 1.4;
        if (pulses[i].radius > pulses[i].max) pulses.splice(i, 1);
      }

      const buckets: OrbParticle[][] = [[], [], [], [], [], []];
      const centerX = width / 2;
      const centerY = height / 2;
      let moved = 0;
      // 흩뜨리기: 가운데에서 바깥으로 저마다 다른 속도로 한 번 밀어요. 스프링이 곧 다음 모양으로 되돌려요
      const isScattering = scatterRef.current && !reduced;
      scatterRef.current = false;
      particles.forEach((particle) => {
        if (isScattering) {
          const dx = particle.x - centerX;
          const dy = particle.y - centerY;
          const distance = Math.hypot(dx, dy) || 1;
          const speed = 1.1 + Math.random() * 1.8;
          particle.vx += (dx / distance) * speed + (Math.random() - 0.5) * 1.4;
          particle.vy += (dy / distance) * speed + (Math.random() - 0.5) * 1.4;
        }
        let forceX = 0;
        let forceY = 0;
        pulses.forEach((wave) => {
          const dx = particle.x - centerX;
          const dy = particle.y - centerY;
          const distance = Math.sqrt(dx * dx + dy * dy) || 1;
          const ring = Math.abs(distance - wave.radius);
          if (ring >= 7) return;
          const force = (1 - ring / 7) * wave.strength * (1 - wave.radius / wave.max);
          forceX += (dx / distance) * force;
          forceY += (dy / distance) * force;
        });
        stepSpring({
          particle,
          homeX: particle.homeX,
          homeY: particle.homeY,
          spring: reduced ? 0.3 : ORB_SPRING,
          damping: ORB_DAMPING,
          jitter: reduced ? 0 : 0.05 + level * 0.3,
          forceX,
          forceY,
        });
        moved += Math.abs(particle.vx) + Math.abs(particle.vy);
        buckets[particle.bucket].push(particle);
      });
      motion = moved / particles.length;

      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
      context.clearRect(0, 0, width, height);
      drawDisc();
      const palette = nextTone === "neutral" ? paramsRef.current.neutralPalette : paramsRef.current.idlePalette;
      const shapeDotScale = nextShape === "disc" ? DISC_DOT_SCALE : nextShape === "wave" ? WAVE_DOT_SCALE : 1;
      // 구슬은 작아서 네모 점이면 모자이크처럼 보여요. 칸마다 한 번에 둥근 점으로 채워요
      buckets.forEach((bucket, index) => {
        if (!bucket.length) return;
        context.fillStyle = palette[index];
        const scale = (index === 5 ? 0.8 : 1) * shapeDotScale;
        context.beginPath();
        bucket.forEach((particle) => {
          const dot = particle.size * scale * (0.7 + 0.3 * ((particle.depth + 1) / 2)) * (1.15 - 0.45 * particle.edge);
          context.moveTo(particle.x + dot / 2, particle.y);
          context.arc(particle.x, particle.y, dot / 2, 0, Math.PI * 2);
        });
        context.fill();
      });
      animation = requestAnimationFrame(tick);
    };

    resize();
    animation = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(animation);
  }, [canvasRef]);

  return { pulse, scatter };
};
