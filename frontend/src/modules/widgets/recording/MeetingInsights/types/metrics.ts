/** 문서 구역별 항목 수 */
export interface ResultCounts {
  decisions: number;
  holds: number;
  undecided: number;
  todos: number;
}

/** 말이 없던 구간 */
export interface SilenceRange {
  startMillis: number;
  endMillis: number;
  seconds: number;
}

/** 문장 하나로 찾은 질문 */
export interface QuestionMark {
  startMillis: number;
  speakerNumber: number | null;
  text: string;
}

/** 회의 한 건의 화자와 관계없는 값 */
export interface MeetingMetrics {
  durationSeconds: number;
  spokenSeconds: number;
  spokenRatio: number;
  /** 끝 시각이 없어 시간 계산에서 뺀 구간 수 */
  missingEndCount: number;
  resultCounts: ResultCounts;
  topicCount: number;
  decidedTopicCount: number;
  decidedTopicRatio: number;
  density: { minute: number; characters: number }[];
  longSilences: SilenceRange[];
  questions: QuestionMark[];
  dateMentionCount: number;
  numberMentionCount: number;
  frequentWords: { word: string; count: number }[];
  confirmedTotal: number;
}

/** 화면에 한 줄로 보여 주는 참여자 또는 참여자 묶음 */
export interface SpeakerGroup {
  /** 화자 번호 문자열, 「미상」, 「기타」 묶음을 구분하는 값 */
  key: string;
  label: string;
  seconds: number;
  /** 전체 발화 시간에서 차지하는 비율 */
  ratio: number;
  characters: number;
  charactersPerMinute: number;
  sentenceCount: number;
  questionCount: number;
  /** 앞사람이 끝난 뒤 말을 시작하기까지 걸린 시간의 가운데값. 받은 차례가 없으면 null */
  medianResponseSeconds: number | null;
  intervals: { startMillis: number; endMillis: number }[];
}

/** 회의 한 건의 화자 값 */
export interface SpeakerMetrics {
  groups: SpeakerGroup[];
  /** 번호가 붙은 화자 수 */
  speakerCount: number;
  /** 20초 미만으로 말한 화자 수 */
  shortSpeakerCount: number;
  unknownRatio: number;
  topRatio: number;
  balance: {
    /** 20초 이상 말한 화자 수 */
    countedSpeakers: number;
    /** 고르게 나눈 값(1/N)과의 평균 차이 */
    gapFromEven: number;
    gini: number;
  };
  switchCount: number;
  switchesPerHour: number;
  longestTurn: { label: string; seconds: number; startMillis: number } | null;
  links: { from: string; to: string; count: number }[];
  phases: {
    label: string;
    shares: { key: string; ratio: number }[];
  }[];
}

/** 여러 회의 비교의 회의 한 건 */
export interface TrendPoint {
  recordingId: number;
  dateKey: string;
  label: string;
  durationSeconds: number;
  durationMinutes: number;
  decisionCount: number;
  undecidedCount: number;
  /** 지금 회의까지 쌓인 미결정 항목 수 */
  undecidedTotal: number;
  todoCount: number;
  documentCount: number;
  switchesPerHour: number;
  /** 회의에 나온 화자 번호 수 */
  speakerCount: number;
  viewTotal: number;
  searchEvidenceTotal: number;
}

/** 여러 회의를 비교한 값 */
export interface WorkspaceTrends {
  points: TrendPoint[];
  weeks: {
    weekStart: string;
    meetings: number;
    withoutDecision: number;
    ratio: number;
  }[];
  calendar: { dateKey: string; count: number; minutes: number }[];
  weekdayHours: { weekday: number; hour: number; count: number }[];
  repeatedFolders: {
    folderName: string;
    meetings: number;
    documents: number;
  }[];
}
