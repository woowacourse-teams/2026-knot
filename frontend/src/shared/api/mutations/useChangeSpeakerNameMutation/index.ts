import {
  PatchSpeakerNameRequestDto,
  type PatchSpeakerNameRequestInput,
} from "@api/dto/recording";
import type { GetRecordingTranscriptResponseDto } from "@api/dto/recordingDocument";
import { changeSpeakerNameApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/speakers/[speakerNumber]";
import { recordingDocumentKeys } from "@api/queryKey/recordingDocument";
import { useMutation, useQueryClient } from "@tanstack/react-query";
/** 같은 전사 ID의 녹음·문서 원문 캐시만 다시 조회해요. 요약·문서 본문은 수정하지 않아요. */
export default function useChangeSpeakerNameMutation() {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    networkMode: "always",
    mutationFn: ({
      workspaceId,
      recordingId,
      speakerNumber,
      ...input
    }: {
      workspaceId: number;
      recordingId: number;
    } & PatchSpeakerNameRequestInput & { speakerNumber: number }) =>
      changeSpeakerNameApi({
        workspaceId,
        recordingId,
        speakerNumber,
        body: new PatchSpeakerNameRequestDto(input),
      }),
    onSuccess: (data, params) => {
      client.setQueryData<GetRecordingTranscriptResponseDto>(
        recordingDocumentKeys.transcript(params),
        (old) =>
          old
            ? {
                ...old,
                segments: old.segments.map((segment) =>
                  segment.speakerNumber === data.speakerNumber
                    ? { ...segment, speakerDisplayName: data.displayName }
                    : segment,
                ),
              }
            : old,
      );
    },
    onSettled: async (_data, _error, params) => {
      const key = recordingDocumentKeys.transcript(params);
      const transcript =
        client.getQueryData<GetRecordingTranscriptResponseDto>(key);
      await Promise.all([
        client.invalidateQueries({ queryKey: key }),
        client.invalidateQueries({
          predicate: (query) =>
            query.queryKey[0] === "documents" &&
            query.queryKey[1] === "transcript" &&
            query.queryKey[2] === params.workspaceId &&
            transcript !== undefined &&
            (query.state.data as { transcriptId?: number } | undefined)
              ?.transcriptId === transcript.transcriptId,
        }),
      ]);
    },
  });
}
