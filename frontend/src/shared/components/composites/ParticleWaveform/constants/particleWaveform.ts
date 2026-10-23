/** 소리 이력의 칸 수. 오른쪽 끝이 지금이고 왼쪽으로 갈수록 오래된 소리예요 */
export const WAVEFORM_COLUMNS = 96;

/** 한 칸이 맡는 시간(ms). 96칸이면 약 5초가 한눈에 보여요 */
export const WAVEFORM_INTERVAL_MS = 50;

/** 넓이 1px²당 입자 수와 상한·하한. 랜딩 녹음 장면의 밀도를 녹음 바 크기에 맞춘 값이에요 */
export const WAVEFORM_PARTICLES_PER_AREA = 0.28;
export const WAVEFORM_PARTICLE_RANGE = { min: 1200, max: 6500 } as const;

/** 소리가 가장 클 때 입자가 가운데에서 퍼지는 거리. 높이에 곱해요 */
export const WAVEFORM_AMPLITUDE_RATIO = 0.48;

/** 올리면 입자가 포인터를 피하는 반지름(px). 랜딩의 95를 녹음 바 높이에 맞춰 줄였어요 */
export const WAVEFORM_POINTER_RADIUS = 48;
