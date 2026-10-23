import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import DocumentTree from "@features/document/DocumentTree";
import WorkspaceSwitcher from "@features/workspace/WorkspaceSwitcher";
import { useDialog } from "@provider/context/dialogContext";

import FeedbackIcon from "@/assets/icons/feedback.svg";

import FeedbackSheet from "./ui/FeedbackSheet";

/**
 * 워크스페이스 사이드바 드로어. 워크스페이스 이름과 문서 목록(`DocumentTree`)을 보여줘요.
 *
 * 맨 위의 워크스페이스 이름(`WorkspaceSwitcher`)을 누르면 다른 워크스페이스로 옮겨 가거나 새로 만들 수 있어요.
 *
 * 휴대폰에서는 GNB에 피드백 메뉴가 없어서, 맨 아래 「피드백」으로 피드백 패널을 아래 시트로 열어요(FEEDBACK-R2).
 */
export default function WorkspaceSidebar() {
  const { open } = useDialog();

  return (
    <Container aria-label="워크스페이스 사이드바">
      <WorkspaceSwitcher />

      <DocumentTree />

      <FeedbackButton
        type="button"
        onClick={() => open(({ close }) => <FeedbackSheet onClose={close} />)}
      >
        <FeedbackIcon size={18} />
        피드백
      </FeedbackButton>
    </Container>
  );
}

const Container = styled.aside`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  width: 17.5rem; /* 280px */
  height: 100%;
  padding: 1rem; /* 16px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
  overflow-y: auto;

  /* 모바일 덮개는 화면 폭에서 오른쪽 70px을 남기고 최대 320px까지 넓혀요(RESP-R2) */
  ${MEDIA_QUERY.mobile} {
    width: min(20rem, 100vw - 4.375rem); /* 320px */
  }
`;

/** 휴대폰에서만 보여요. 문서 목록이 짧아도 사이드바 맨 아래에 붙어요 */
const FeedbackButton = styled.button`
  display: none;
  flex-shrink: 0;
  align-items: center;
  gap: 0.625rem; /* 10px */
  margin-top: auto;
  padding: 0.625rem 0.75rem; /* 10px 12px */
  border-radius: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[600]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }

  ${MEDIA_QUERY.mobile} {
    display: flex;
  }
`;
