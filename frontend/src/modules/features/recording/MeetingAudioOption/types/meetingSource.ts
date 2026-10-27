import type { MEETING_AUDIO_COPY } from "../constants/meetingAudioCopy";

/**
 * 「화상 회의 상대방 목소리」 줄이 보여 주는 상태.
 *
 * - `off` : 마이크만 녹음 중. 함께 녹음을 고를 수 있어요
 * - `connecting` : 브라우저 공유 창에서 고르는 중
 * - `on` / `paused` : 회의 소리를 함께 녹음 중 / 함께 녹음하다 일시정지
 * - `noAudio` / `stopped` : 소리 없이 공유함 / 공유가 멈춤. 마이크만 녹음하며 다시 연결할 수 있어요
 * - `unsupported` / `mobile` : 회의 소리를 받을 수 없는 컴퓨터 브라우저 / 휴대폰, 태블릿
 */
export type MeetingSourceState =
  | "off"
  | "connecting"
  | "on"
  | "paused"
  | "noAudio"
  | "stopped"
  | "unsupported"
  | "mobile";

/** 연결 안내에서 고르는 회의를 연 곳. 브라우저(`browserTab`) 또는 설치한 앱(`meetingApp`)이에요 */
export type MeetingLocation = keyof typeof MEETING_AUDIO_COPY.LOCATIONS;
