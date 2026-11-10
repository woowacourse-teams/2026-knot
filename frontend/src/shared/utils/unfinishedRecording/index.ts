/** 이 브라우저에서 시작해 아직 마무리를 보지 못한 녹음을 남기는 키 */
const UNFINISHED_RECORDING_KEY = "knot.recording.unfinished";

interface UnfinishedRecording {
  workspaceId: number;
  recordingId: number;
}

/**
 * 이 브라우저에서 녹음을 시작했다고 남겨요.
 *
 * 녹음 탭을 닫으면 sessionStorage가 사라져, 그 녹음이 연결 끊김으로 끝났는지와 브라우저에 남은 조각을 다른 탭이 알 길이 없어요(STT-R26).
 * 비밀값이 아니라 어느 녹음인지만 남겨요.
 */
export const rememberUnfinishedRecording = (recording: UnfinishedRecording) =>
  localStorage.setItem(UNFINISHED_RECORDING_KEY, JSON.stringify(recording));

/** 마무리를 보지 못한 녹음을 돌려줘요. 없으면 `null`이에요 */
export const getUnfinishedRecording = () => {
  const stored = localStorage.getItem(UNFINISHED_RECORDING_KEY);

  return stored ? (JSON.parse(stored) as UnfinishedRecording) : null;
};

/** 그 녹음의 마무리를 봤어요. 그 사이 새 녹음을 시작했으면 새 녹음의 기록은 두어요 */
export const forgetUnfinishedRecording = (recordingId: number) => {
  if (getUnfinishedRecording()?.recordingId === recordingId) {
    localStorage.removeItem(UNFINISHED_RECORDING_KEY);
  }
};
