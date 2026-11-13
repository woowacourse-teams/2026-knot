import useWorkspaceMembersInfiniteQuery from "@api/queries/useWorkspaceMembersInfiniteQuery";
import styled from "@emotion/styled";
import { useId, useRef, useEffect, useState } from "react";
import type { SpeakerNameDraft } from "@features/recording/RenameSpeaker/types/speakerName";
import { validateSpeakerName } from "@features/recording/RenameSpeaker/utils/validateSpeakerName";
interface SpeakerNameFormProps {
  workspaceId: number;
  currentName: string;
  isSaving: boolean;
  hasSaveError: boolean;
  isVerificationFailed: boolean;
  isVerifying: boolean;
  onVerify: () => void;
  onSave: (draft: SpeakerNameDraft) => void;
  onClose: () => void;
}
export default function SpeakerNameForm({
  workspaceId,
  currentName,
  isSaving,
  hasSaveError,
  isVerificationFailed,
  isVerifying,
  onVerify,
  onSave,
  onClose,
}: SpeakerNameFormProps) {
  const titleId = useId();
  const inputId = useId();
  const errorId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [name, setName] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const members = useWorkspaceMembersInfiniteQuery(workspaceId);
  const validation = validateSpeakerName(name);
  useEffect(() => {
    input.current?.focus();
  }, []);
  return (
    <Card
      role="dialog"
      aria-labelledby={titleId}
      onSubmit={(event) => {
        event.preventDefault();
        if (
          !isSaving &&
          !isVerificationFailed &&
          !isVerifying &&
          validation === null
        )
          onSave({
            name: name.trim(),
            nameSource: selectedId === null ? "typed" : "member",
          });
      }}
    >
      <Header>
        <div>
          <Title id={titleId}>이름 바꾸기</Title>
          <Current>지금 이름: {currentName}</Current>
        </div>
        <Close
          type="button"
          aria-label="이름 바꾸기 닫기"
          disabled={isSaving}
          onClick={onClose}
        >
          ×
        </Close>
      </Header>
      <Field>
        <Label htmlFor={inputId}>새 이름</Label>
        <Input
          id={inputId}
          ref={input}
          placeholder="직접 입력"
          value={name}
          disabled={isSaving}
          aria-invalid={name.length > 0 && validation !== null}
          aria-describedby={name.length > 0 && validation ? errorId : undefined}
          onChange={(event) => {
            setName(event.target.value);
            setSelectedId(null);
          }}
        />
        {name.length > 0 && validation && (
          <Error id={errorId}>{validation}</Error>
        )}
      </Field>
      <Field>
        <Label as="p">멤버에서 고르기</Label>
        {members.isPending && (
          <Current role="status">멤버를 불러오고 있어요.</Current>
        )}
        {members.isError && (
          <Error role="alert">
            멤버를 불러오지 못했어요. 이름을 직접 입력할 수 있어요.{" "}
            <button type="button" onClick={() => void members.refetch()}>
              다시 불러오기
            </button>
          </Error>
        )}
        <MemberList aria-label="워크스페이스 멤버">
          {members.data?.pages
            .flatMap((page) => page.items)
            .map((member) => (
              <li key={member.memberId}>
                <Member
                  type="button"
                  $isSelected={selectedId === member.memberId}
                  aria-pressed={selectedId === member.memberId}
                  disabled={isSaving}
                  onClick={() => {
                    setName(member.nickname);
                    setSelectedId(member.memberId);
                  }}
                >
                  <Avatar aria-hidden>
                    {member.profileImageUrl ? (
                      <img src={member.profileImageUrl} alt="" />
                    ) : (
                      [...member.nickname][0]
                    )}
                  </Avatar>
                  <MemberName>{member.nickname}</MemberName>
                  {selectedId === member.memberId && <span aria-hidden>✓</span>}
                </Member>
              </li>
            ))}
        </MemberList>
        {members.hasNextPage && (
          <More
            type="button"
            disabled={members.isFetchingNextPage}
            onClick={() => void members.fetchNextPage()}
          >
            멤버 더 보기
          </More>
        )}
      </Field>
      <Notice>
        이 녹음에서 <strong>{currentName}</strong>로 표시된 발화가 모두 ‘
        {name.trim() || "새 이름"}’으로 바뀌어요.
      </Notice>
      {hasSaveError && (
        <Error role="alert">
          이름 저장을 확인하지 못했어요. 현재 표시된 이름을 확인하고 다시 시도해
          주세요.
        </Error>
      )}
      {isVerificationFailed && (
        <Error role="alert">
          현재 전사를 확인하지 못했어요.{" "}
          <More type="button" disabled={isVerifying} onClick={onVerify}>
            {isVerifying ? "확인 중" : "다시 확인"}
          </More>
        </Error>
      )}
      <Actions>
        <Cancel type="button" disabled={isSaving} onClick={onClose}>
          취소
        </Cancel>
        <Save
          type="submit"
          disabled={
            isSaving ||
            isVerificationFailed ||
            isVerifying ||
            validation !== null
          }
        >
          {isSaving ? "저장 중" : "바꾸기"}
        </Save>
      </Actions>
    </Card>
  );
}
const Card = styled.form`
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  width: 22.5rem;
  max-width: calc(100vw - 2rem);
  max-height: calc(100dvh - 2rem);
  overflow-y: auto;
  padding: 1.5rem;
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1.25rem;
  background: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;
const Header = styled.div`
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem;
`;
const Title = styled.h3`
  ${({ theme }) => theme.text.heading03};
  color: ${({ theme }) => theme.neutral[900]};
`;
const Current = styled.p`
  margin-top: 0.25rem;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
  overflow-wrap: anywhere;
`;
const Close = styled.button`
  flex-shrink: 0;
  width: 2rem;
  height: 2rem;
  font-size: 1.5rem;
  cursor: pointer;
  color: ${({ theme }) => theme.neutral[600]};
`;
const Field = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
`;
const Label = styled.label`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[600]};
`;
const Input = styled.input`
  width: 100%;
  height: 3.25rem;
  padding: 0 1rem;
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 0.875rem;
  background: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.caption02};
  &:focus {
    outline: 2px solid ${({ theme }) => theme.neutral[800]};
    outline-offset: -1px;
  }
`;
const MemberList = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 15rem;
  overflow-y: auto;
`;
const Member = styled.button<{ $isSelected: boolean }>`
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  min-height: 3rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.75rem;
  ${({ theme }) => theme.text.body01};
  cursor: pointer;
  background: ${({ theme, $isSelected }) =>
    $isSelected ? theme.neutral[100] : theme.neutral[0]};
  font-weight: ${({ $isSelected }) => ($isSelected ? 600 : 400)};
  color: ${({ theme }) => theme.neutral[900]};
`;
const Avatar = styled.span`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 2rem;
  height: 2rem;
  border-radius: 50%;
  overflow: hidden;
  background: ${({ theme }) => theme.neutral[200]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  & img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
`;
const MemberName = styled.span`
  flex: 1;
  text-align: left;
  overflow-wrap: anywhere;
`;
const Notice = styled.p`
  padding: 0.75rem 1rem;
  border-radius: 0.75rem;
  background: ${({ theme }) => theme.neutral[50]};
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
  overflow-wrap: anywhere;
`;
const Error = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.sub.warning[700]};
`;
const More = styled.button`
  ${({ theme }) => theme.text.caption02};
  text-decoration: underline;
  cursor: pointer;
`;
const Actions = styled.div`
  display: flex;
  gap: 0.5rem;
`;
const Cancel = styled.button`
  flex: 1;
  height: 3rem;
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 0.8125rem;
  ${({ theme }) => theme.text.body01};
  cursor: pointer;
  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;
const Save = styled(Cancel)`
  border-color: ${({ theme }) => theme.neutral[800]};
  background: ${({ theme }) => theme.neutral[800]};
  color: ${({ theme }) => theme.neutral[0]};
  font-weight: 600;
`;
