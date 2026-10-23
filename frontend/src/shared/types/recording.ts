/**
 * 현재 녹음 조회에서 화면에 보여 주는 녹음 상태.
 * `organizing`은 녹음을 끝낸 뒤 문서를 정리하는 중이에요. 정리 화면과 같은 이름(`getRecordingDocumentsStatus`)을 써요.
 * `transcriptionFailed`는 전사(녹음을 글로 옮기기)가, `documentFailed`는 문서 생성이 끝내 실패해 다시 시도할 수 있는 녹음이에요
 */
export type CurrentRecordingStatus =
  | "recording"
  | "paused"
  | "organizing"
  | "transcriptionFailed"
  | "documentFailed";

/**
 * 오디오 저장 방식. `FINAL_WEBM_V1`은 끝낸 뒤 WebM 파일 하나를 올리는 기존 방식,
 * `PCM16_CHUNKS_V1`은 녹음 중 PCM 조각을 바로 올리는 방식이에요. 시작 요청의 `audioMode`와 같은 값이에요
 */
export type RecordingAudioMode = "FINAL_WEBM_V1" | "PCM16_CHUNKS_V1";

/**
 * 녹음 시작을 시도한 결과. `useRecordingControl`의 시작 결과 중 서버에 녹음을 열려고 한 경우예요.
 *
 * - `started`: 녹음을 시작했어요
 * - `microphoneUnavailable`: 마이크를 받지 못했어요(권한 거절, 장치 없음)
 * - `refused`: 서버가 거절했거나 남아 있던 녹음이 있어요
 * - `networkFailed`: 시작 요청이 응답을 받지 못했어요
 * - `failed`: 그 밖의 실패예요
 */
export type RecordingStartResult =
  "started" | "microphoneUnavailable" | "refused" | "networkFailed" | "failed";

/**
 * 녹음이 끝난 이유.
 *
 * - `userEnded`: 이 탭에서 사용자가 끝냈어요
 * - `connectionExpired`: 생존 신호가 끊겨 서버가 끝냈어요
 * - `maxDuration`: 누적 녹음 시간이 최대 길이에 닿아 서버가 끝냈어요
 * - `endedByServer`: 다른 탭에서 끝냈거나 서버가 사유 없이 끝냈어요
 * - `discarded`: 서버가 녹음을 버렸어요
 * - `unreachable`: 워크스페이스에서 나갔거나 서버에 녹음이 없어요
 * - `controlLost`: 이 탭이 녹음 제어권을 잃었어요
 */
export type RecordingEndCause =
  | "userEnded"
  | "connectionExpired"
  | "maxDuration"
  | "endedByServer"
  | "discarded"
  | "unreachable"
  | "controlLost";
