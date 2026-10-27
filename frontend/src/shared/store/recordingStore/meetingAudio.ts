import { captureCaughtError } from "@provider/sentry";

import { stopTracks } from "./microphoneConnection";

/**
 * 회의 소리를 받을 수 있는 브라우저인지. Chrome 계열(UA에 `Chrome/`가 있는 Chrome, Edge, Arc 등)이고 공유 창을 열 수 있어야 해요.
 *
 * getDisplayMedia는 Safari에도 있지만 탭 오디오와 시스템 오디오 공유는 Chrome 계열만 줘서, 기능 유무만으로는 알 수 없어요.
 * 휴대폰의 Chrome은 공유 창이 없어 받을 수 없는 쪽이에요.
 */
export const isMeetingAudioSupported = () =>
  /Chrome\//.test(navigator.userAgent) &&
  typeof navigator.mediaDevices?.getDisplayMedia === "function";

/**
 * 브라우저 공유 창을 열어 회의 소리를 받아요. 사용자의 누름 안에서 불러야 공유 창이 열려요.
 *
 * 소리 트랙이 있는 공유만 그대로 돌려줘요. 소리 없이 공유하면 공유를 바로 끄고 `"noAudio"`, 공유 창을 닫거나
 * 열지 못하면 `"cancelled"`예요.
 */
export const captureMeetingAudio = async (): Promise<
  MediaStream | "noAudio" | "cancelled"
> => {
  try {
    // Chrome은 화면 없이 소리만 공유할 수 없어 화면도 받아요. 화면 트랙만 먼저 꺼도 소리가 이어지는지는 확인하지 않아 녹음이 끝날 때 함께 꺼요
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: true,
      audio: true,
    });
    if (stream.getAudioTracks().length > 0) return stream;

    stopTracks(stream);
    return "noAudio";
  } catch (error) {
    // 공유 창을 닫은 거절(NotAllowedError)은 Sentry 보고 기준에서 빠져요
    captureCaughtError({ error, feature: "recording" });
    return "cancelled";
  }
};
