import useRecordingTranscriptQuery from "@api/queries/useRecordingTranscriptQuery";
import useChangeSpeakerNameMutation from "@api/mutations/useChangeSpeakerNameMutation";
import Popover from "@composites/Popover";
import styled from "@emotion/styled";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import SpeakerNameForm from "./ui/SpeakerNameForm";

interface RenameSpeakerProps {
  workspaceId: number;
  recordingId: number;
  speakerNumber: number;
  displayName: string;
  isInitiallyOpen?: boolean;
}
/** 이름 선택은 계정 연결 없이 문자열만 복사해요. */
export default function RenameSpeaker({
  workspaceId,
  recordingId,
  speakerNumber,
  displayName,
  isInitiallyOpen = false,
}: RenameSpeakerProps) {
  const mutation = useChangeSpeakerNameMutation();
  const verification = useRecordingTranscriptQuery({
    workspaceId,
    recordingId,
    isEnabled: false,
  });
  useRedirectToLoginOnUnauthorized({ error: mutation.error });
  return (
    <Popover
      placement="bottom-start"
      openOn="click"
      isInitiallyOpen={isInitiallyOpen}
      isDismissible={!mutation.isPending}
      onOpen={() => mutation.reset()}
      content={({ close }) => (
        <SpeakerNameForm
          workspaceId={workspaceId}
          currentName={displayName}
          isSaving={mutation.isPending}
          hasSaveError={mutation.isError}
          isVerificationFailed={mutation.isError && verification.isError}
          isVerifying={mutation.isError && verification.isFetching}
          onVerify={() => void verification.refetch()}
          onClose={close}
          onSave={(name) =>
            mutation.mutate(
              { workspaceId, recordingId, speakerNumber, displayName: name },
              { onSuccess: () => close() },
            )
          }
        />
      )}
    >
      {(props) => (
        <Name
          type="button"
          {...props}
          aria-label={`${displayName} 이름 바꾸기`}
        >
          {displayName}
        </Name>
      )}
    </Popover>
  );
}
const Name = styled.button`
  display: inline;
  padding: 0;
  font: inherit;
  font-weight: 600;
  text-decoration: underline;
  text-decoration-color: ${({ theme }) => theme.neutral[300]};
  text-decoration-thickness: 2px;
  text-underline-offset: 4px;
  cursor: pointer;
  overflow-wrap: anywhere;
  &[aria-expanded="true"] {
    padding: 0 0.25rem;
    border-radius: 0.375rem;
    background: ${({ theme }) => theme.neutral[800]};
    color: ${({ theme }) => theme.neutral[0]};
    text-decoration: none;
  }
  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 3px;
  }
`;
