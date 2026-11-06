import type { RecordingAudioMode } from "@/shared/types/recording";

/** 같은 탭을 새로고침한 뒤 녹음을 이어 받을 증명을 두는 키. 녹음이 끝나거나 버려지면 지워요 */
const RESUME_PROOF_KEY = "knot.recording.resumeProof";

/** 제어 증명은 32바이트 난수예요 */
const CONTROL_TOKEN_BYTES = 32;

interface ControlProof {
  tabId: string;
  controlToken: string;
}

interface SaveRecordingResumeProofParams {
  workspaceId: number;
  recordingId: number;
  audioMode: RecordingAudioMode;
}

interface RecordingResumeProof
  extends SaveRecordingResumeProofParams, ControlProof {
  /** 이어 받기를 보내 놓고 응답을 아직 받지 못한 새 증명. 재시도에는 같은 값을 보내요 */
  next?: ControlProof;
}

/** 이 페이지의 탭 ID. sessionStorage에 두면 탭을 복제할 때 같이 복사돼서, 페이지를 열 때마다 새로 만들어요(STT-R28) */
const pageTabId = crypto.randomUUID();

/** 응답을 받기 전인 녹음 시작 하나의 요청 ID·제어 증명. 페이지를 떠나면 그 시작은 다시 보내지 않아요 */
let startProof: { requestId: string; controlToken: string } | null = null;

/** 패딩 없는 Base64URL. 32바이트면 43자가 돼요 */
const createControlToken = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(CONTROL_TOKEN_BYTES));

  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
};

const writeResumeProof = (proof: RecordingResumeProof) =>
  sessionStorage.setItem(RESUME_PROOF_KEY, JSON.stringify(proof));

/** 이 탭에 남은 이어 녹음 증명을 돌려줘요. 없으면 `null`이에요 */
export const getRecordingResumeProof = () => {
  const stored = sessionStorage.getItem(RESUME_PROOF_KEY);

  return stored ? (JSON.parse(stored) as RecordingResumeProof) : null;
};

/**
 * 녹음 시작 요청에 실을 증명(요청 ID·탭 ID·제어 증명)을 돌려줘요.
 *
 * 서버는 같은 요청 ID로 다시 온 시작을 새 녹음이 아니라 재시도로 보고 기존 세션을 돌려줘요.
 * 그래서 응답을 받지 못한 시작을 다시 보낼 때 같은 값을 쓰도록, 녹음을 끝내거나 버려
 * `clearRecordingStartProof`를 부르기 전까지는 같은 값을 돌려줘요.
 */
export const getRecordingStartProof = () => {
  startProof ??= {
    requestId: crypto.randomUUID(),
    controlToken: createControlToken(),
  };

  return { ...startProof, tabId: pageTabId };
};

/** 서버에 녹음이 열린 뒤 불러요. 같은 탭을 새로고침해도 이어 받을 수 있게 지금 증명을 sessionStorage에 남겨요 */
export const saveRecordingResumeProof = (
  params: SaveRecordingResumeProofParams,
) => {
  const { tabId, controlToken } = getRecordingStartProof();

  writeResumeProof({ ...params, tabId, controlToken });
};

/** 일시정지·재개·종료 요청에 실을 녹음 탭 증명(탭 ID·제어 증명)을 돌려줘요. 시작한 적이 없으면 `null`이에요 */
export const getRecordingControlProof = (): ControlProof | null => {
  const resumeProof = getRecordingResumeProof();
  if (resumeProof) {
    const { tabId, controlToken } = resumeProof;

    return { tabId, controlToken };
  }

  return startProof
    ? { tabId: pageTabId, controlToken: startProof.controlToken }
    : null;
};

/**
 * 이어 받기 요청에 실을 이전 증명과 새 증명을 돌려줘요. 증명이 없으면 `null`이에요.
 *
 * 새 증명은 응답을 받기 전에 남겨 둬요. 서버는 바꿨는데 응답을 못 받은 채 새로고침해도 같은 값으로 다시 보내야 받아 주기 때문이에요.
 */
export const prepareRecordingReconnect = () => {
  const proof = getRecordingResumeProof();
  if (!proof) return null;

  const next = proof.next ?? {
    tabId: crypto.randomUUID(),
    controlToken: createControlToken(),
  };
  writeResumeProof({ ...proof, next });

  return {
    previousTabId: proof.tabId,
    previousControlToken: proof.controlToken,
    ...next,
  };
};

/** 서버가 이어 받기를 받아 준 뒤 불러요. 이제부터 신호·조각·끝내기에는 새 증명을 써요 */
export const completeRecordingReconnect = () => {
  const proof = getRecordingResumeProof();
  if (!proof?.next) return;

  const { next, ...rest } = proof;
  writeResumeProof({ ...rest, ...next });
};

/**
 * 끝낸 녹음의 요청 ID·제어 증명을 지워요. 다음 시작은 새 녹음으로 보내야 하기 때문이에요.
 * 탭 ID는 탭이 바뀐 게 아니라 남겨 둬요.
 */
export const clearRecordingStartProof = () => {
  startProof = null;
  sessionStorage.removeItem(RESUME_PROOF_KEY);
};
