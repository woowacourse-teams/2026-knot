/** 같은 브라우저의 탭끼리 녹음이 끝난 것을 알리는 채널 이름 */
const RECORDING_CHANNEL_NAME = "knot.recording";

interface RecordingEndedMessage {
  workspaceId: number;
  recordingId: number;
}

/** 지원하지 않는 브라우저에서는 다른 기기처럼 다음 신호 응답으로 알아요 */
const isChannelSupported = () => typeof BroadcastChannel !== "undefined";

/** 다른 탭에서 녹음을 끝냈다고 같은 브라우저의 탭에 알려요. 녹음 탭은 다음 신호를 기다리지 않고 멈춰요(STT-R28) */
export const postRecordingEnded = (message: RecordingEndedMessage) => {
  if (!isChannelSupported()) return;

  const channel = new BroadcastChannel(RECORDING_CHANNEL_NAME);

  channel.postMessage(message);
  channel.close();
};

/** 다른 탭이 끝낸 녹음의 알림을 받아요. 돌려준 함수를 부르면 그만 받아요 */
export const subscribeRecordingEnded = (
  onEnded: (message: RecordingEndedMessage) => void,
) => {
  if (!isChannelSupported()) return undefined;

  const channel = new BroadcastChannel(RECORDING_CHANNEL_NAME);

  channel.onmessage = (event: MessageEvent<RecordingEndedMessage>) =>
    onEnded(event.data);

  return () => channel.close();
};
