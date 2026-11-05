import type {
  MeetingInsightsDataset,
  MeetingInsightsMeeting,
} from "@widgets/recording/MeetingInsights/types/dataset";

/** 화자 번호, 말한 초, 문장 */
type ScriptLine = [number, number, string];

/** 대본을 전사 구간으로 바꿔요. 말 사이에 2초씩 쉬어요 */
const toSegments = (script: ScriptLine[]) => {
  let cursor = 0;

  return script.map(([speakerNumber, seconds, text]) => {
    const startMillis = cursor;
    const endMillis = cursor + seconds * 1000;

    cursor = endMillis + 2000;

    return { startMillis, endMillis, speakerNumber, text };
  });
};

const createDocument = (
  id: number,
  folderName: string,
  title: string,
  content: string,
  createdAt: string,
) => ({
  id,
  topic: title,
  folderName,
  title,
  summary: null,
  content,
  createdAt,
  confirmedCount: id % 3,
  searchEvidenceCount: id % 2,
  viewCount: id + 1,
});

const createMeeting = (
  recordingId: number,
  startedAt: string,
  endedAt: string,
  script: ScriptLine[],
  documents: MeetingInsightsMeeting["documents"],
  meetingSummary: string | null,
): MeetingInsightsMeeting => {
  const segments = toSegments(script);

  return {
    recordingId,
    title: null,
    startedAt,
    endedAt,
    recordingDurationSeconds: Math.ceil(
      (segments[segments.length - 1]?.endMillis ?? 0) / 1000,
    ),
    meetingSummary,
    viewCount: recordingId,
    segments,
    documents,
  };
};

const PLANNING_SUMMARY = [
  "## 한눈에 보기",
  "### 정한 방향",
  "- 첫 화면은 회의 결과 카드로 시작한다",
  "### 아직 결정하지 못한 것",
  "- 발화 비율을 누구에게 보여 줄지",
  "## 1. 첫 화면",
  "- 결과 개수를 맨 위에 둔다",
  "- 회의 구조 지도를 그 아래에 둔다",
  "적용 방향: 다음 시안에 넣는다",
  "## 2. 공개 범위",
  "- 본인에게 보여 주는 안과 모두에게 보여 주는 안을 비교했다",
  "정리: 다음 회의에서 정한다",
  "회의 핵심 결론: 결과 카드부터 만들고 공개 범위는 다음에 정한다",
].join("\n");

/** 실제 데이터 파일이 없을 때 쓰는 가상의 회의예요. 등장하는 내용은 모두 지어낸 것이에요 */
export const exampleMeetingInsightsDataset: MeetingInsightsDataset = {
  isExample: true,
  sourceLabel: "가상 예시 데이터",
  workspaceName: "예시",
  memberCount: 4,
  exportedAt: "2026-11-05T07:00:00Z",
  meetings: [
    createMeeting(
      101,
      "2026-10-26T01:00:00Z",
      "2026-10-26T01:30:00Z",
      [
        [
          1,
          90,
          "이번 주 배포 범위부터 볼게요. 로그인 화면은 금요일까지 끝낼 수 있나요?",
        ],
        [2, 60, "네 금요일까지 3개 화면을 끝낼 수 있어요."],
        [3, 45, "테스트는 누가 맡을까요?"],
        [1, 120, "테스트는 제가 맡고 다음 주 월요일에 공유할게요."],
        [2, 30, "좋습니다."],
      ],
      [
        createDocument(
          1,
          "배포",
          "이번 주 배포 범위",
          "## 핵심 요약\n배포 범위를 정했다\n## 결정\n- 로그인 화면을 금요일에 배포한다\n## 할 일\n- 테스트 결과 공유",
          "2026-10-26T01:35:00Z",
        ),
      ],
      null,
    ),
    createMeeting(
      102,
      "2026-10-29T06:00:00Z",
      "2026-10-29T06:40:00Z",
      [
        [2, 150, "지난주 회고를 시작할게요. 문서가 늦게 나온 원인이 뭘까요?"],
        [1, 40, "서버 대기열이 길었어요."],
        [3, 80, "대기열 길이를 화면에 보여 주면 어떨까요?"],
        [4, 35, "그건 다음 스프린트에서 볼게요."],
        [2, 200, "일단 보류하고 사용자가 100명을 넘으면 다시 볼게요."],
      ],
      [
        createDocument(
          2,
          "회고",
          "문서 생성 지연 회고",
          "## 핵심 요약\n지연 원인을 봤다\n## 보류\n- 대기열 표시는 사용자가 100명을 넘으면 본다\n## 미결정\n- 서버 증설 시기",
          "2026-10-29T06:45:00Z",
        ),
      ],
      null,
    ),
    createMeeting(
      103,
      "2026-11-02T01:00:00Z",
      "2026-11-02T01:50:00Z",
      [
        [1, 180, "회의 뒤 화면을 정할게요. 첫 화면에 무엇을 둘까요?"],
        [2, 70, "결과 카드가 먼저 보여야 해요."],
        [3, 95, "구조 지도도 같이 있으면 좋겠어요."],
        [1, 60, "결과 카드로 정할게요."],
        [4, 110, "발화 비율은 누구에게 보여 줄까요?"],
        [2, 45, "본인에게만 보여 주는 안이 어떨까요?"],
        [3, 75, "모두에게 보여 줘야 회의가 바뀌지 않을까요?"],
        [1, 40, "그건 다음 회의에서 정할게요."],
      ],
      [
        createDocument(
          3,
          "회의 화면",
          "회의 뒤 첫 화면",
          "## 핵심 요약\n첫 화면을 정했다\n## 결정\n- 첫 화면은 결과 카드로 시작한다\n- 구조 지도를 함께 둔다\n## 할 일\n- 시안 만들기",
          "2026-11-02T01:55:00Z",
        ),
        createDocument(
          4,
          "회의 화면",
          "발화 비율 공개 범위",
          "## 핵심 요약\n공개 범위를 비교했다\n## 미결정\n- 발화 비율을 누구에게 보여 줄지",
          "2026-11-02T01:55:00Z",
        ),
      ],
      PLANNING_SUMMARY,
    ),
  ],
};
