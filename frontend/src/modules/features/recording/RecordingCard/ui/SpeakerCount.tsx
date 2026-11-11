import useChangeSpeakerCountMutation from "@api/mutations/useChangeSpeakerCountMutation";
import useRecordingQuery from "@api/queries/useRecordingQuery";
import styled from "@emotion/styled";
import useRecordingState from "@hooks/domain/recording/useRecordingState";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import { getRecordingControlProof } from "@utils/recordingControlProof";

/** 녹음·일시정지 중 서버에 저장한 참가자 수만 표시하는 스테퍼. */
export default function SpeakerCount() {
  const { session, status, isEnding, isConnectionLost } = useRecordingState();
  const params = {
    workspaceId: session?.workspaceId ?? 0,
    recordingId: session?.recordingId ?? 0,
  };
  const { data, error, refetch, isFetching } = useRecordingQuery({
    ...params,
    isEnabled: session !== null,
  });
  const mutation = useChangeSpeakerCountMutation();
  useRedirectToLoginOnUnauthorized({ error: mutation.error ?? error });
  const proof = getRecordingControlProof();
  const count = data?.speakerCount ?? null;
  const isDisabled =
    !session ||
    !proof ||
    status === "idle" ||
    isEnding ||
    isConnectionLost ||
    mutation.isPending ||
    isFetching ||
    !data ||
    error !== null ||
    data.sessionStatus === "ENDED" ||
    data.sessionStatus === "DISCARDED";
  const change = (speakerCount: number | null) => {
    if (isDisabled || !proof) return;
    mutation.mutate({ ...params, ...proof, speakerCount });
  };
  return (
    <Group>
      <Row>
        <Label id="speaker-count-label">참가자 수</Label>
        <Stepper
          role="group"
          aria-labelledby="speaker-count-label"
          aria-busy={mutation.isPending}
        >
          <Step
            type="button"
            aria-label="참가자 수 줄이기"
            disabled={isDisabled || count === null}
            onClick={() => change(count === 1 ? null : (count ?? 1) - 1)}
          >
            −
          </Step>
          <Value aria-live="polite">
            {session && !data
              ? "불러오는 중"
              : count === null
                ? "자동 감지"
                : `${count}명`}
          </Value>
          <Step
            type="button"
            aria-label="참가자 수 늘리기"
            disabled={isDisabled || count === 10}
            onClick={() => change((count ?? 0) + 1)}
          >
            +
          </Step>
        </Stepper>
      </Row>
      {(error || mutation.isError) && (
        <Error role="alert">
          참가자 수를 저장하거나 확인하지 못했어요. 마지막 저장값을 확인한 뒤
          다시 시도해 주세요.{" "}
          <Retry
            type="button"
            onClick={() => {
              mutation.reset();
              void refetch();
            }}
          >
            다시 확인
          </Retry>
        </Error>
      )}
    </Group>
  );
}
const Group = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding-top: 0.5rem;
`;
const Row = styled.div`
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 1.5rem;
`;
const Label = styled.span`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[800]};
`;
const Stepper = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.25rem;
  width: 15rem;
  height: 3.25rem;
  padding: 0 0.625rem;
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 0.875rem;
`;
const Step = styled.button`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.5rem;
  font-size: 1.25rem;
  color: ${({ theme }) => theme.neutral[600]};
  cursor: pointer;
  &:disabled {
    color: ${({ theme }) => theme.neutral[300]};
    cursor: default;
  }
  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
  }
`;
const Value = styled.span`
  width: 7.5rem;
  text-align: center;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;
const Error = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.sub.warning[700]};
`;
const Retry = styled.button`
  text-decoration: underline;
  cursor: pointer;
`;
