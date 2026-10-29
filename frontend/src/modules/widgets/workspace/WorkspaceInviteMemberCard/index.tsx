import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import InlineError from "@primitives/ui/InlineError";
import Input from "@primitives/ui/Input";

import CheckIcon from "@/assets/icons/check.svg";
import TeammateIcon from "@/assets/icons/teammate.svg";

import { useWorkspaceInviteMemberCard } from "./model/useWorkspaceInviteMemberCard";

/**
 * 홈의 팀원 초대 카드. 현재 워크스페이스의 초대 링크와 초대 코드를 복사해요.
 *
 * 초대를 불러오지 못하면 제목은 그대로 두고, 링크 칸과 초대 코드 복사 자리에 실패 안내(ERR-09)를 둬요.
 * `다시 시도`는 이 카드의 초대만 다시 받아요.
 */
export default function WorkspaceInviteMemberCard() {
  const {
    inviteCode,
    displayInviteLink,
    isLoading,
    isLoadFailed,
    isLinkCopied,
    isCodeCopied,
    handleCopyLink,
    handleCopyCode,
    handleRetry,
  } = useWorkspaceInviteMemberCard();

  const isInviteReady = inviteCode !== undefined;

  return (
    <Container>
      <Head>
        <IconChip>
          <TeammateIcon size={24} />
        </IconChip>
        <Title>팀원 초대</Title>
      </Head>

      {isLoadFailed ? (
        <InlineErrorWrapper>
          <InlineError
            message="초대 링크를 불러오지 못했어요. 다시 시도해 주세요."
            onRetry={handleRetry}
            align="center"
          />
        </InlineErrorWrapper>
      ) : (
        <Content>
          <LinkField>
            <LinkInput
              variant="copy"
              status="filled"
              value={displayInviteLink ?? ""}
              readOnly
              aria-label="초대 링크"
            />
            <CopyButton
              size="sm"
              variant={isLinkCopied ? "accent" : "filled"}
              isLoading={isLoading}
              disabled={!isInviteReady}
              onClick={handleCopyLink}
            >
              {isLinkCopied ? (
                <>
                  <CheckIcon />
                  복사됨
                </>
              ) : (
                "복사"
              )}
            </CopyButton>
          </LinkField>

          <CopyCodeButton
            type="button"
            disabled={!isInviteReady}
            onClick={handleCopyCode}
          >
            {isCodeCopied ? "복사됨" : "초대 코드 복사"}
          </CopyCodeButton>
        </Content>
      )}
    </Container>
  );
}

const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 2rem; /* 32px */
  width: 25rem; /* 400px */
  max-width: 100%;
  min-height: 12.5rem; /* 200px */
  padding: 1.25rem; /* 20px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

const Head = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

const IconChip = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.5rem; /* 40px */
  height: 2.5rem;
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[200]};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

const Content = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

/** 피그마 「홈/팀원 초대 불러오기 실패」: 머리 아래 남은 영역을 채우고 그 가운데에 안내를 놓아요 */
const InlineErrorWrapper = styled.div`
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
`;

const LinkField = styled.div`
  position: relative;
  display: flex;
  width: 100%;
`;

const LinkInput = styled(Input)`
  flex: 1;
  min-width: 0;
  padding-right: 7.5rem; /* 120px */
  text-overflow: ellipsis;
`;

const CopyButton = styled(Button)`
  position: absolute;
  top: 50%;
  right: 0.75rem; /* 12px */
  transform: translateY(-50%);
`;

const CopyCodeButton = styled.button`
  width: 100%;
  border-radius: 0.5rem;
  color: ${({ theme }) => theme.neutral[600]};
  text-align: center;
  ${({ theme }) => theme.text.body01};

  &:disabled {
    color: ${({ theme }) => theme.neutral[400]};
    cursor: default;
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
