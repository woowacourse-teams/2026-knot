import { recordingTranscriptResponse } from "@api/mock/responses/recordingDocument";
import type { DocumentTranscriptSegment } from "@api/mock/types/document";
const counts = new Map<string, number | null>();
const names = new Map<string, Map<number, string>>();
const key = (workspaceId: number, recordingId: number) =>
  `${workspaceId}:${recordingId}`;
export const resetRecordingSpeakerMockState = () => {
  counts.clear();
  names.clear();
};
export const getSpeakerCount = (workspaceId: number, recordingId: number) =>
  counts.get(key(workspaceId, recordingId)) ?? null;
export const setSpeakerCount = (
  workspaceId: number,
  recordingId: number,
  count: number | null,
) => counts.set(key(workspaceId, recordingId), count);
export const setSpeakerName = (
  workspaceId: number,
  recordingId: number,
  speaker: number,
  name: string,
) => {
  const id = key(workspaceId, recordingId);
  const labels = names.get(id) ?? new Map<number, string>();
  labels.set(speaker, name);
  names.set(id, labels);
};
export const namedSegments = (
  workspaceId: number,
  recordingId: number,
  segments: DocumentTranscriptSegment[],
) =>
  segments.map((segment) => ({
    ...segment,
    speakerDisplayName:
      segment.speakerNumber === null
        ? null
        : (names
            .get(key(workspaceId, recordingId))
            ?.get(segment.speakerNumber) ?? null),
  }));

/** 원본 fixture를 유지하면서 해당 녹음에 저장한 표시명을 반영해요. */
export const getRecordingTranscriptMockResponse = (
  workspaceId: number,
  recordingId: number,
) => ({
  ...recordingTranscriptResponse,
  recordingId,
  segments: namedSegments(
    workspaceId,
    recordingId,
    recordingTranscriptResponse.segments,
  ),
});
