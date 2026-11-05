import type { DocumentTranscriptSegmentRaw } from "@api/dto/document";

/** 회의에서 나온 문서 하나. 필드 이름은 문서 상세 API를 따라요 */
export interface MeetingInsightsDocument {
  id: number;
  /** AI가 지은 주제 이름 */
  topic: string;
  /** 문서 탭의 주제 폴더 이름 */
  folderName: string | null;
  title: string;
  summary: string | null;
  /** 마크다운 본문. 결정 · 보류 · 미결정 · 할 일 구역이 들어 있어요 */
  content: string;
  createdAt: string;
  /** 문서를 확인한 멤버 수 */
  confirmedCount: number;
  /** 탐색 답변의 근거로 쓰인 횟수 */
  searchEvidenceCount: number;
  /** 문서 보기 화면이 열린 횟수 */
  viewCount: number;
}

/** 녹음 하나. 전사 구간은 녹음 전사 API, 요약은 녹음 문서 API의 값이에요 */
export interface MeetingInsightsMeeting {
  recordingId: number;
  /** AI가 지은 녹음 제목. 없으면 null */
  title: string | null;
  startedAt: string;
  endedAt: string;
  /** 일시정지를 뺀 녹음 길이(초) */
  recordingDurationSeconds: number;
  /** 회의 전체 요약 마크다운. 요약이 없는 녹음은 null */
  meetingSummary: string | null;
  /** 녹음 문서 화면이 열린 횟수 */
  viewCount: number;
  segments: DocumentTranscriptSegmentRaw[];
  documents: MeetingInsightsDocument[];
}

/** 페이지가 읽는 데이터 묶음 */
export interface MeetingInsightsDataset {
  /** 저장소의 가상 예시 데이터면 `true` */
  isExample: boolean;
  /** 화면 위쪽에 보여 줄 데이터 출처 이름 */
  sourceLabel: string;
  workspaceName: string;
  memberCount: number;
  exportedAt: string;
  /** 끝난 시각 오름차순 */
  meetings: MeetingInsightsMeeting[];
}
