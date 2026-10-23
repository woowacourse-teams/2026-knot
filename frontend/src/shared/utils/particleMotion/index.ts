/**
 * 입자 애니메이션에서 함께 쓰는 작은 계산.
 *
 * 랜딩의 입자 엔진과 같은 규칙(스프링 복귀, 감쇠, 둥근 사각형 둘레)을 작은 캔버스에서도 쓰려고 모았어요.
 */

/** 한 프레임의 기준 길이(ms). 애니메이션은 60fps 기준으로 설계됐어요 */
const FRAME_MS = 1000 / 60;

/**
 * 지난 프레임부터 흐른 시간을 60fps 기준 프레임 수로 바꿔요.
 * 120Hz 화면에서 프레임마다 1씩 더하면 두 배 빨라져서, 흐른 시간만큼만 더해요. 탭이 멈췄다 돌아오면 최대 2프레임만 쳐요.
 */
export const frameStep = (now: number, previous: number) => (previous ? Math.min(2, (now - previous) / FRAME_MS) : 1);

/** 가운데에 몰린 -1~1 난수. 입자를 선 둘레에 자연스럽게 퍼뜨릴 때 써요. 랜딩과 같아요 */
export const gaussian = () => (Math.random() + Math.random() + Math.random() - 1.5) / 1.5;

interface SpringParticle {
  x: number;
  y: number;
  vx: number;
  vy: number;
}

interface StepSpringParams {
  particle: SpringParticle;
  /** 돌아갈 자리 */
  homeX: number;
  homeY: number;
  /** 자리로 당기는 힘(0~1). 랜딩은 0.05~0.09를 써요 */
  spring: number;
  /** 속도가 남는 비율(0~1). 랜딩은 0.84예요 */
  damping: number;
  /** 매 프레임 더하는 흔들림의 크기 */
  jitter: number;
  /** 밀어내기·파동처럼 바깥에서 더하는 힘 */
  forceX?: number;
  forceY?: number;
}

/** 입자를 제자리로 당기며 한 프레임 움직여요. 입자를 직접 바꿔요. */
export const stepSpring = ({
  particle,
  homeX,
  homeY,
  spring,
  damping,
  jitter,
  forceX = 0,
  forceY = 0,
}: StepSpringParams) => {
  const ax = (homeX - particle.x) * spring + (Math.random() - 0.5) * jitter + forceX;
  const ay = (homeY - particle.y) * spring + (Math.random() - 0.5) * jitter + forceY;
  particle.vx = (particle.vx + ax) * damping;
  particle.vy = (particle.vy + ay) * damping;
  particle.x += particle.vx;
  particle.y += particle.vy;
};

interface RoundedRectPointParams {
  width: number;
  height: number;
  /** 모서리 반지름. 높이의 절반보다 크면 알약 모양으로 잘라요 */
  radius: number;
  /** 둘레에서 안쪽으로 들어갈 거리 */
  inset?: number;
  /** 윗변 왼쪽 모서리 끝에서 시계 방향으로 잰 둘레 비율(0~1). 범위를 벗어나면 한 바퀴를 돌아요 */
  distance: number;
}

/** 둥근 사각형 둘레 위의 한 점과 바깥을 향하는 방향. 랜딩의 `roundedRectPoint`와 같은 계산이에요. */
export const roundedRectPoint = ({
  width,
  height,
  radius,
  inset = 0,
  distance,
}: RoundedRectPointParams) => {
  const x = inset;
  const y = inset;
  const w = Math.max(0, width - inset * 2);
  const h = Math.max(0, height - inset * 2);
  const r = Math.max(0, Math.min(radius - inset, h / 2, w / 2));
  const straightX = w - 2 * r;
  const straightY = h - 2 * r;
  const arc = (Math.PI * r) / 2;
  const segments = [straightX, arc, straightY, arc, straightX, arc, straightY, arc];
  const total = segments.reduce((sum, segment) => sum + segment, 0);

  let rest = (((distance % 1) + 1) % 1) * total;
  let index = 0;
  while (index < 7 && rest > segments[index]) {
    rest -= segments[index];
    index++;
  }
  const t = segments[index] > 0 ? Math.min(1, rest / segments[index]) : 0;

  if (index === 0) return { x: x + r + straightX * t, y, nx: 0, ny: -1 };
  if (index === 2) return { x: x + w, y: y + r + straightY * t, nx: 1, ny: 0 };
  if (index === 4) return { x: x + w - r - straightX * t, y: y + h, nx: 0, ny: 1 };
  if (index === 6) return { x, y: y + h - r - straightY * t, nx: -1, ny: 0 };

  const corners: Record<number, [number, number, number]> = {
    1: [x + w - r, y + r, -Math.PI / 2],
    3: [x + w - r, y + h - r, 0],
    5: [x + r, y + h - r, Math.PI / 2],
    7: [x + r, y + r, Math.PI],
  };
  const [cx, cy, start] = corners[index];
  const angle = start + (t * Math.PI) / 2;
  return { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r, nx: Math.cos(angle), ny: Math.sin(angle) };
};

interface SmoothLevelParams {
  previous: number;
  next: number;
  /** 커질 때 따라가는 비율(0~1). 목소리가 들어오면 바로 반응하게 크게 둬요 */
  attack: number;
  /** 작아질 때 따라가는 비율(0~1). 소리가 끊겨도 천천히 가라앉게 작게 둬요 */
  release: number;
}

/** 소리 크기를 빠르게 따라가고 천천히 가라앉게 다듬어요. */
export const smoothLevel = ({ previous, next, attack, release }: SmoothLevelParams) =>
  previous + (next - previous) * (next > previous ? attack : release);

/** 움직임 줄이기 설정을 켰는지. 캔버스 애니메이션은 CSS 미디어 쿼리를 못 쓰므로 직접 읽어요. */
export const prefersReducedMotion = () =>
  typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/* ---------- 소리 이력과 랜딩 입자 엔진과 똑같이 쓰는 포인터·파동 계산 ---------- */

/** 소리 크기 이력. 왼쪽이 오래된 것, 오른쪽 끝이 지금이고 일정 간격마다 한 칸씩 왼쪽으로 밀려요 */
export interface LevelHistory {
  /** 칸마다의 소리 크기(0~1). 마지막 칸이 지금이에요 */
  values: Float32Array;
  /** 한 칸이 맡는 시간(ms) */
  intervalMs: number;
  /** 마지막으로 칸을 민 시각(ms). `null`이면 아직 시작 전이에요 */
  lastShiftAt: number | null;
}

/** 칸 수와 칸 하나가 맡는 시간으로 빈 이력을 만들어요 */
export const createLevelHistory = (columns: number, intervalMs: number): LevelHistory => ({
  values: new Float32Array(columns),
  intervalMs,
  lastShiftAt: null,
});

/** 이력을 비워 평평하게 만들어요. 일시정지하거나 소리를 읽을 수 없을 때 써요 */
export const clearLevelHistory = (history: LevelHistory) => {
  history.values.fill(0);
  history.lastShiftAt = null;
};

/**
 * 지금 소리 크기를 이력에 넣어요. 한 칸이 맡는 시간 안에서는 가장 큰 값을 남겨 짧은 소리도 보이고,
 * 시간이 지나면 한 칸씩 왼쪽으로 밀고 새 칸은 지금 크기에서 시작해요. 탭이 오래 멈췄다 돌아오면 이력을 비우고 다시 시작해요.
 */
export const pushLevel = (history: LevelHistory, level: number, now: number) => {
  const { values, intervalMs } = history;
  const last = values.length - 1;
  if (history.lastShiftAt === null) history.lastShiftAt = now;

  let elapsed = now - history.lastShiftAt;
  if (elapsed >= intervalMs * values.length) {
    values.fill(0);
    history.lastShiftAt = now;
    elapsed = 0;
  }
  values[last] = Math.max(values[last], level);
  while (elapsed >= intervalMs) {
    values.copyWithin(0, 1);
    values[last] = level;
    history.lastShiftAt += intervalMs;
    elapsed -= intervalMs;
  }
};

/** 다음 칸으로 밀리기까지 얼마나 지났는지(0~1). 이력이 부드럽게 흘러가 보이도록 그릴 때 그만큼 왼쪽으로 당겨요 */
export const levelHistoryPhase = (history: LevelHistory, now: number) =>
  history.lastShiftAt === null ? 0 : Math.max(0, Math.min(1, (now - history.lastShiftAt) / history.intervalMs));

interface SampleLevelHistoryParams {
  history: LevelHistory;
  /** 가로 위치(0~1). 0이 왼쪽, 1이 오른쪽 끝(지금)이에요 */
  position: number;
  /** `levelHistoryPhase`의 값. 그만큼 이력을 왼쪽으로 당겨 읽어요 */
  phase: number;
}

/** 가로 위치의 소리 크기(0~1). 칸 사이는 매끄럽게 이어요 */
export const sampleLevelHistory = ({ history, position, phase }: SampleLevelHistoryParams) => {
  const { values } = history;
  const last = values.length - 1;
  const u = Math.max(0, position) * last + phase;
  const left = Math.min(last, Math.floor(u));
  const right = Math.min(last, left + 1);
  const mix = Math.min(1, u - left);
  return values[left] * (1 - mix) + values[right] * mix;
};

/** 파형 전체가 커졌다 작아지는 숨. 랜딩 녹음 장면과 문서 정리 그림이 써요. 녹음 중 파형은 소리 이력을 쓰고 이 숨은 쓰지 않아요 */
export const waveBreath = (frame: number) =>
  0.62 + 0.38 * Math.sin(frame * 0.019) * Math.sin(frame * 0.0073 + 1.3);

interface WaveAmplitudeParams {
  column: number;
  columns: number;
  frame: number;
  /** 소리가 가장 클 때 막대가 가운데에서 퍼지는 거리(px) */
  maxAmplitude: number;
  /** 전체 배율(0~1). 랜딩은 `waveBreath`, 녹음 중엔 그 칸 시각의 소리 크기를 넣어요 */
  scale: number;
  /** 양 끝으로 갈수록 낮출지. 랜딩처럼 가운데에 놓인 파형은 낮추고, 오른쪽 끝이 지금인 소리 흐름은 낮추지 않아요 */
  taper?: boolean;
  /** 소리가 없을 때 남는 가장 낮은 높이(px). 랜딩은 2.5예요 */
  floor?: number;
}

/** 파형 한 막대의 높이. 막대마다 다른 박자로 출렁여 랜딩 녹음 장면과 같은 모양이에요. 랜딩 `updateWaveAmplitudes`와 같아요 */
export const waveAmplitude = ({ column, columns, frame, maxAmplitude, scale, taper = true, floor = 2.5 }: WaveAmplitudeParams) => {
  const energy = Math.abs(
    Math.sin(column * 0.23 + frame * 0.05) * Math.sin(column * 0.071 - frame * 0.023) +
      0.35 * Math.sin(column * 0.87 + frame * 0.13),
  );
  const edge = taper ? Math.pow(Math.sin((Math.PI * (column + 0.5)) / columns), 0.7) : 1;
  return Math.max(floor, maxAmplitude * Math.min(1, 0.1 + energy) * edge * scale);
};

interface PointerRepulsionParams {
  x: number;
  y: number;
  pointerX: number;
  pointerY: number;
  /** 피하기 시작하는 거리(px). 랜딩은 95예요 */
  radius?: number;
  /** 가장 가까울 때의 힘. 랜딩은 6이에요 */
  strength?: number;
}

/** 포인터 가까이의 입자를 바깥으로 미는 힘. 랜딩과 같아요 */
export const pointerRepulsion = ({ x, y, pointerX, pointerY, radius = 95, strength = 6 }: PointerRepulsionParams) => {
  const dx = x - pointerX;
  const dy = y - pointerY;
  const distanceSquared = dx * dx + dy * dy;
  if (distanceSquared >= radius * radius) return { fx: 0, fy: 0 };
  const distance = Math.sqrt(distanceSquared) || 1;
  const closeness = 1 - distance / radius;
  const force = closeness * closeness * strength;
  return { fx: (dx / distance) * force, fy: (dy / distance) * force };
};

/** 누른 자리에서 퍼져 나가는 파동 하나 */
export interface PulseWave {
  x: number;
  y: number;
  radius: number;
  max: number;
  strength: number;
}

interface PulseBoostParams {
  lastPulseAt: number;
  boost: number;
  now: number;
}

/** 연달아 누르면 파동이 세져요. 랜딩처럼 1.5초 안이면 1.6배씩, 최대 3.2배예요 */
export const nextPulseBoost = ({ lastPulseAt, boost, now }: PulseBoostParams) =>
  now - lastPulseAt < 1500 ? Math.min(3.2, boost * 1.6) : 1;

interface CreatePulseParams {
  x: number;
  y: number;
  /** 파동이 퍼질 무대 크기. 큰 변의 55%까지 퍼져요 */
  width: number;
  height: number;
  /** 기본 세기 5에 곱할 배율 */
  boost?: number;
}

/** 랜딩 `addPulse`와 같은 파동을 만들어요 */
export const createPulse = ({ x, y, width, height, boost = 1 }: CreatePulseParams): PulseWave => ({
  x,
  y,
  radius: 0,
  max: Math.max(width, height) * 0.55,
  strength: 5 * boost,
});

/** 파동을 한 프레임 키우고 다 퍼진 것은 지워요. 랜딩은 한 프레임에 12px씩 퍼져요 */
export const advancePulses = (pulses: PulseWave[], speed = 12) => {
  for (let i = pulses.length - 1; i >= 0; i--) {
    pulses[i].radius += speed;
    if (pulses[i].radius > pulses[i].max) pulses.splice(i, 1);
  }
};

interface PulseForceParams {
  x: number;
  y: number;
  pulse: PulseWave;
  /** 파동 테두리의 두께(px). 랜딩은 46이에요 */
  ring?: number;
}

/** 파동 테두리에 걸린 입자를 바깥으로 미는 힘. 랜딩과 같아요 */
export const pulseForce = ({ x, y, pulse, ring = 46 }: PulseForceParams) => {
  const ex = x - pulse.x;
  const ey = y - pulse.y;
  const distance = Math.sqrt(ex * ex + ey * ey) || 1;
  const band = Math.abs(distance - pulse.radius);
  if (band >= ring) return { fx: 0, fy: 0 };
  const force = (1 - band / ring) * pulse.strength * (1 - pulse.radius / pulse.max);
  return { fx: (ex / distance) * force, fy: (ey / distance) * force };
};
