import type { DocumentTranscriptSegment } from "@api/mock/types/document";

/** 회의 전체 요약 상태. QUEUED · RUNNING은 화면의 「만드는 중」 */
export type MeetingSummaryStatus =
  "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface RecordingMeetingSummary {
  status: MeetingSummaryStatus;
  /** SUCCEEDED일 때만 값이 있음. 마크다운 */
  content: string | null;
  /** FAILED이고 지금 다시 접수할 수 있으면 true */
  retryable: boolean;
}

/** 녹음 문서의 처리 상태. 주제 분류 실패도 DOCUMENT_FAILED */
export type RecordingDocumentProcessingStatus =
  | "PROCESSING"
  | "TRANSCRIPTION_FAILED"
  | "DOCUMENT_FAILED"
  | "NO_CONTENT"
  | "COMPLETED";

export interface RecordingDocumentResponse {
  recordingId: number;
  /** AI 녹음 제목이 없으면 서버가 「{닉네임} 님의 녹음」으로 채움 */
  title: string;
  startedAt: string;
  recordingDurationSeconds: number;
  processingStatus: RecordingDocumentProcessingStatus;
  retryable: boolean;
  /** DOCUMENT_FAILED에서만 값이 있음. 나머지는 빈 배열 */
  retryableJobIds: number[];
  /** 요약이 접수되지 않았거나 요약 기능 전 녹음이면 null */
  meetingSummary: RecordingMeetingSummary | null;
}

export interface RecordingTranscriptResponse {
  recordingId: number;
  transcriptId: number;
  transcriptText: string;
  /** 시작 시각 오름차순. 문서 원문 조회와 같은 구조 */
  segments: DocumentTranscriptSegment[];
}

/** AVAILABLE(들을 수 있음) · PREPARING(음성 합치는 중) · NO_AUDIO(음성 파일 없음) */
export type RecordingAudioPlaybackStatus =
  "AVAILABLE" | "PREPARING" | "NO_AUDIO";

export interface RecordingAudioPlaybackResponse {
  recordingId: number;
  audioStatus: RecordingAudioPlaybackStatus;
  /** AVAILABLE일 때만 값이 있음 */
  playbackUrl: string | null;
  /** AVAILABLE일 때만 값이 있음 */
  expiresAt: string | null;
  /** audio/webm · audio/wav. AVAILABLE일 때만 값이 있음 */
  contentType: string | null;
  recordingDurationMillis: number;
}

export interface MeetingSummaryRetryResponse {
  recordingId: number;
  /** 항상 QUEUED */
  meetingSummaryStatus: MeetingSummaryStatus;
}
