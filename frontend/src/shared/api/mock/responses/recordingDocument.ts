import {
  documentRecordingsResponse,
  documentTranscriptsResponse,
} from "@api/mock/responses/document";
import type {
  MeetingSummaryRetryResponse,
  RecordingAudioPlaybackResponse,
  RecordingDocumentResponse,
  RecordingTranscriptResponse,
} from "@api/mock/types/recordingDocument";

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

// 지난 시각을 고정값으로 두면 언젠가 전부 오래된 날짜가 되므로 지금을 기준으로 만들어요
const fromNow = (elapsed: number) =>
  new Date(Date.now() - elapsed).toISOString();

/** 정한 길이(초)의 무음 WAV를 data URL로 만들어요. 8kHz · 8비트 · 모노라 1초에 8KB예요 */
const createSilentWavDataUrl = (seconds: number) => {
  const sampleRate = 8000;
  const dataLength = sampleRate * seconds;
  const bytes = new Uint8Array(44 + dataLength);
  const view = new DataView(bytes.buffer);
  const writeText = (offset: number, text: string) => {
    [...text].forEach((char, index) =>
      view.setUint8(offset + index, char.charCodeAt(0)),
    );
  };

  writeText(0, "RIFF");
  view.setUint32(4, 36 + dataLength, true);
  writeText(8, "WAVE");
  writeText(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate, true);
  view.setUint16(32, 1, true);
  view.setUint16(34, 8, true);
  writeText(36, "data");
  view.setUint32(40, dataLength, true);
  // 8비트 WAV는 128이 무음이에요
  bytes.fill(128, 44);

  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });

  return `data:audio/wav;base64,${btoa(binary)}`;
};

// 문서 탭 「녹음별」 mock의 녹음 42(「회원 탈퇴 정책 논의」)예요. 이 녹음에서 나온 문서는 문서 mock의 101 · 102예요
const withdrawalRecording = documentRecordingsResponse.items.find(
  ({ recordingId }) => recordingId === 42,
)!;
const [withdrawalTranscript] = documentTranscriptsResponse;

// 백엔드 요약 양식(한눈에 보기 → 번호 주제 → 회의 핵심 결론)을 따른 요약이에요. 내용은 녹음 42의 전사 mock과 맞췄어요
const meetingSummaryContent = [
  "## 한눈에 보기",
  "",
  "### 정한 방향",
  "",
  "- 탈퇴한 사용자의 게시글은 유지하고 작성자를 '탈퇴한 사용자'로 표시한다.",
  "- 댓글이 없는 글은 탈퇴할 때 함께 삭제한다.",
  "",
  "### 아직 결정하지 못한 것",
  "",
  "- 첨부파일 유지: 게시글과 함께 남길지 다음 논의에서 확인한다.",
  "- 홈 화면 개편: 개편 범위를 정하지 못해 다음 회의에서 다시 본다.",
  "",
  "## 1. 회원 탈퇴 정책",
  "",
  "- 탈퇴하면 게시글을 삭제할지 논의했다.",
  "- 댓글이 달린 글이 사라지면 대화 흐름이 끊긴다.",
  "",
  "적용 방향: 댓글이 달린 글만 남기고 작성자 표시를 '탈퇴한 사용자'로 통일한다.",
  "",
  "## 2. 홈 화면 개편",
  "",
  "- 홈 전체 개편과 일부 개선 두 안을 비교했다.",
  "",
  "정리: 개편 범위는 다음 회의에서 다시 본다.",
  "",
  "회의 핵심 결론: 탈퇴한 사용자의 글은 댓글이 있으면 남기고, 홈 개편은 다음 회의에서 정한다.",
].join("\n");

// 문서 정리와 요약을 모두 마친 녹음이에요. 처리 상태 · 요약 상태의 다른 경우는 테스트와 스토리에서 핸들러를 덮어 만들어요
export const recordingDocumentResponse = {
  recordingId: withdrawalRecording.recordingId,
  title: withdrawalRecording.title,
  startedAt: fromNow(2 * DAY),
  recordingDurationSeconds: withdrawalTranscript.recordingDurationSeconds,
  processingStatus: "COMPLETED",
  retryable: false,
  retryableJobIds: [],
  meetingSummary: {
    status: "SUCCEEDED",
    content: meetingSummaryContent,
    retryable: false,
  },
} satisfies RecordingDocumentResponse;

// 녹음 42의 전사예요. 같은 녹음에서 나온 문서의 원문(81)과 같은 데이터예요
export const recordingTranscriptResponse = {
  recordingId: withdrawalRecording.recordingId,
  transcriptId: withdrawalTranscript.transcriptId,
  transcriptText: withdrawalTranscript.transcriptText,
  segments: withdrawalTranscript.segments,
} satisfies RecordingTranscriptResponse;

// 개발 서버와 스토리북에서 실제로 재생되도록 10초짜리 무음을 넣었어요. 화면의 전체 길이는 서버가 준 녹음 길이를 따라요
export const recordingAudioPlaybackResponse = {
  recordingId: withdrawalRecording.recordingId,
  audioStatus: "AVAILABLE",
  playbackUrl: createSilentWavDataUrl(10),
  expiresAt: new Date(Date.now() + HOUR).toISOString(),
  contentType: "audio/wav",
  recordingDurationMillis: withdrawalTranscript.recordingDurationSeconds * 1000,
} satisfies RecordingAudioPlaybackResponse;

export const meetingSummaryRetryResponse = {
  recordingId: withdrawalRecording.recordingId,
  meetingSummaryStatus: "QUEUED",
} satisfies MeetingSummaryRetryResponse;
