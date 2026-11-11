import type { DocumentTranscriptSegmentDto } from "@api/dto/document";
import { formatRecordingTime } from "@utils/formatRecordingTime";

const MILLIS_PER_SECOND = 1000;

type TranscriptSegment = Pick<
  DocumentTranscriptSegmentDto,
  "startMillis" | "speakerNumber" | "speakerDisplayName" | "text"
>;

/**
 * 원문 패널에 보이는 발화 줄을 그대로 한 줄씩 이어 복사할 글로 만들어요.
 *
 * 줄마다 말하기 시작한 시각, 「참여자 N:」, 문장 순서예요. 화자 번호가 없으면 시각 뒤에 문장만 둬요(DOC-R7).
 *
 * @example
 * formatTranscriptText([{ startMillis: 160000, speakerNumber: 1, text: "시작할게요." }]); // "02:40 참여자 1: 시작할게요."
 */
export const formatTranscriptText = (segments: TranscriptSegment[]) =>
  segments
    .map(({ startMillis, speakerNumber, speakerDisplayName, text }) => {
      const time = formatRecordingTime(startMillis / MILLIS_PER_SECOND);
      const speaker = `${speakerDisplayName ?? (speakerNumber !== null ? `참여자 ${speakerNumber}` : "미상")}: `;

      return `${time} ${speaker}${text}`;
    })
    .join("\n");
