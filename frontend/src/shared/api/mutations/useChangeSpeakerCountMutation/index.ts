import {
  PatchSpeakerCountRequestDto,
  type PatchSpeakerCountRequestInput,
} from "@api/dto/recording";
import type { GetRecordingResponseDto } from "@api/dto/recording";
import { changeSpeakerCountApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/speakerCount";
import { recordingKeys } from "@api/queryKey/recording";
import { useMutation, useQueryClient } from "@tanstack/react-query";
/** 저장 성공값만 반영하고 응답 유실 시 GET으로 확인해요. 자동 PATCH 재시도는 하지 않아요. */
export default function useChangeSpeakerCountMutation() {
  const client = useQueryClient();
  return useMutation({
    retry: false,
    networkMode: "always",
    mutationFn: ({
      workspaceId,
      recordingId,
      ...input
    }: {
      workspaceId: number;
      recordingId: number;
    } & PatchSpeakerCountRequestInput) =>
      changeSpeakerCountApi({
        workspaceId,
        recordingId,
        body: new PatchSpeakerCountRequestDto(input),
      }),
    onSuccess: (data, params) => {
      client.setQueryData<GetRecordingResponseDto>(
        recordingKeys.detail(params),
        (old) => (old ? { ...old, speakerCount: data.speakerCount } : old),
      );
    },
    onSettled: (_data, _error, params) =>
      client.invalidateQueries({ queryKey: recordingKeys.detail(params) }),
  });
}
