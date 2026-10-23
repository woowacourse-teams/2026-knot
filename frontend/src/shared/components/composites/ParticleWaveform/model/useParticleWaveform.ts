import { measureAnalyserLevel } from "@utils/measureAudioLevel";
import {
  advancePulses,
  clearLevelHistory,
  createLevelHistory,
  createPulse,
  frameStep,
  levelHistoryPhase,
  nextPulseBoost,
  pointerRepulsion,
  prefersReducedMotion,
  pulseForce,
  pushLevel,
  sampleLevelHistory,
  smoothLevel,
  stepSpring,
  type PulseWave,
  waveAmplitude,
} from "@utils/particleMotion";
import { useCallback, useEffect, useRef, type RefObject } from "react";

import {
  WAVEFORM_AMPLITUDE_RATIO,
  WAVEFORM_COLUMNS,
  WAVEFORM_INTERVAL_MS,
  WAVEFORM_PARTICLE_RANGE,
  WAVEFORM_PARTICLES_PER_AREA,
  WAVEFORM_POINTER_RADIUS,
} from "../constants/particleWaveform";

interface WaveParticle {
  /** 막대 번호. 0이 왼쪽, 마지막이 오른쪽 끝(지금)이에요 */
  column: number;
  /** 막대 안의 세로 위치(-1~1). 랜딩 파형과 같아요 */
  level: number;
  /** 막대 안의 가로 흩어짐. 랜딩 파형과 같아요 */
  jitter: number;
  size: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
}

interface UseParticleWaveformParams {
  canvasRef: RefObject<HTMLCanvasElement | null>;
  analyser: AnalyserNode | null;
  isActive: boolean;
  /** 진한, 보통, 옅은 잉크와 일시정지 색 */
  palette: [string, string, string, string];
}

/**
 * 소리 크기의 흐름을 입자 파형으로 캔버스에 매 프레임 그려요.
 *
 * 모양은 랜딩 녹음 장면 그대로예요. 96칸 막대가 저마다 다른 박자로 출렁이고, 입자는 막대 안에 고르게 흩어져 세로 위치에 따라 잉크 세 단계로 그려요.
 * 다른 점은 높이예요. 랜딩은 숨쉬기로 정하지만 여기서는 그 칸 시각의 소리 크기로 정해요. 오른쪽 끝이 지금 소리이고 시간이 지나면 왼쪽으로 흘러가요.
 * 조용하면 가운데에 가는 선만 남고, 말하면 그 순간의 막대만 소리만큼 커져요.
 * 올리면 입자가 포인터를 피하고, 누르면 파동이 퍼져요.
 * 일시정지면 이력을 비워 옅은 색의 평평한 선으로 멈춰 있고, 이어서 녹음하면 오른쪽부터 다시 채워져요.
 * 움직임 줄이기 설정에서는 흔들림 없이 소리만 따라요.
 */
export const useParticleWaveform = ({ canvasRef, analyser, isActive, palette }: UseParticleWaveformParams) => {
  const paramsRef = useRef({ analyser, isActive, palette });
  paramsRef.current = { analyser, isActive, palette };
  const pointerRef = useRef({ x: -9999, y: -9999 });
  const pulsesRef = useRef<PulseWave[]>([]);
  const pulseBoostRef = useRef({ at: 0, boost: 1 });

  const setPointer = useCallback((x: number, y: number) => {
    pointerRef.current = { x, y };
  }, []);
  const clearPointer = useCallback(() => {
    pointerRef.current = { x: -9999, y: -9999 };
  }, []);
  const pulse = useCallback((x: number, y: number, width: number, height: number) => {
    const now = Date.now();
    const boost = pulseBoostRef.current;
    boost.boost = nextPulseBoost({ lastPulseAt: boost.at, boost: boost.boost, now });
    boost.at = now;
    pulsesRef.current.push(createPulse({ x, y, width, height, boost: boost.boost }));
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;

    const reduced = prefersReducedMotion();
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 1.75);
    const history = createLevelHistory(WAVEFORM_COLUMNS, WAVEFORM_INTERVAL_MS);
    const amplitudes = new Float32Array(WAVEFORM_COLUMNS);
    const particles: WaveParticle[] = [];
    let width = 0;
    let height = 0;
    let frame = Math.random() * 1000;
    let lastFrameAt = 0;
    let level = 0;
    let animation = 0;

    const resize = () => {
      const nextWidth = canvas.clientWidth;
      const nextHeight = canvas.clientHeight;
      if (!nextWidth || !nextHeight || (nextWidth === width && nextHeight === height)) return;
      width = nextWidth;
      height = nextHeight;
      canvas.width = Math.round(width * pixelRatio);
      canvas.height = Math.round(height * pixelRatio);
      const count = Math.round(
        Math.min(WAVEFORM_PARTICLE_RANGE.max, Math.max(WAVEFORM_PARTICLE_RANGE.min, width * height * WAVEFORM_PARTICLES_PER_AREA)),
      );
      particles.length = 0;
      for (let i = 0; i < count; i++) {
        particles.push({
          column: i % WAVEFORM_COLUMNS,
          level: Math.random() * 2 - 1,
          jitter: (Math.random() - 0.5) * 0.42,
          size: 0.9 + Math.random() * 0.9,
          x: Math.random() * width,
          y: height / 2 + (Math.random() - 0.5) * height * 0.2,
          vx: 0,
          vy: 0,
        });
      }
    };

    const tick = (now: number) => {
      // 화면 주사율과 상관없이 같은 속도로 움직이게 흐른 시간만큼 프레임을 더해요
      frame += frameStep(now, lastFrameAt);
      lastFrameAt = now;
      resize();
      const { analyser: node, isActive: active, palette: colors } = paramsRef.current;
      // 일시정지거나 소리를 읽을 수 없으면 이력을 비워 평평한 선이에요. 소리는 빠르게 따라가고 조금 천천히 가라앉아요
      if (node && active) {
        level = smoothLevel({ previous: level, next: measureAnalyserLevel(node), attack: 0.6, release: 0.3 });
        pushLevel(history, level, now);
      } else {
        level = 0;
        clearLevelHistory(history);
      }
      const phase = levelHistoryPhase(history, now);
      const maxAmplitude = height * WAVEFORM_AMPLITUDE_RATIO;
      // 막대 모양은 랜딩 공식 그대로, 배율만 그 칸 시각의 소리 크기예요. 조용하면 2.5px 선, 오른쪽 끝(지금)도 낮추지 않아요
      for (let column = 0; column < WAVEFORM_COLUMNS; column++) {
        const scale = sampleLevelHistory({ history, position: (column + 0.5) / WAVEFORM_COLUMNS, phase });
        amplitudes[column] = waveAmplitude({ column, columns: WAVEFORM_COLUMNS, frame, maxAmplitude, scale, taper: false });
      }
      const columnWidth = width / WAVEFORM_COLUMNS;

      const pulses = pulsesRef.current;
      advancePulses(pulses);
      const pointer = pointerRef.current;
      const hasPointer = pointer.x > -1000;
      const centerY = height / 2;
      const buckets: WaveParticle[][] = [[], [], [], []];

      particles.forEach((particle) => {
        const homeX = (particle.column + 0.5 + particle.jitter) * columnWidth;
        const homeY = centerY + particle.level * amplitudes[particle.column];
        let forceX = 0;
        let forceY = 0;
        if (hasPointer) {
          const repulsion = pointerRepulsion({
            x: particle.x,
            y: particle.y,
            pointerX: pointer.x,
            pointerY: pointer.y,
            radius: WAVEFORM_POINTER_RADIUS,
          });
          forceX += repulsion.fx;
          forceY += repulsion.fy;
        }
        for (const wave of pulses) {
          const force = pulseForce({ x: particle.x, y: particle.y, pulse: wave });
          forceX += force.fx;
          forceY += force.fy;
        }
        stepSpring({
          particle,
          homeX,
          homeY,
          spring: reduced ? 0.3 : 0.09,
          damping: 0.84,
          // 조용할 땐 거의 멈춰 있고 소리가 들어올수록 조금 흔들려요. 일시정지면 전혀 움직이지 않아요
          jitter: reduced || !active ? 0 : 0.04 + level * 0.2,
          forceX,
          forceY,
        });
        const strength = Math.abs(particle.level);
        buckets[!active ? 3 : strength < 0.45 ? 0 : strength < 0.8 ? 1 : 2].push(particle);
      });

      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
      context.clearRect(0, 0, width, height);
      buckets.forEach((bucket, index) => {
        if (!bucket.length) return;
        context.fillStyle = colors[index];
        bucket.forEach((particle) => context.fillRect(particle.x, particle.y, particle.size, particle.size));
      });
      animation = requestAnimationFrame(tick);
    };

    resize();
    animation = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(animation);
  }, [canvasRef]);

  return { setPointer, clearPointer, pulse };
};
