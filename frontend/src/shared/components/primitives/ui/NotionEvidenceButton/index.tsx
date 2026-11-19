import styled from "@emotion/styled";
import LinkTo from "@primitives/ui/LinkTo";

import OpenExternal from "@/assets/icons/openExternal.svg";
import NotionLogo from "@/assets/logos/notion.svg";

interface NotionEvidenceButtonProps {
  /** 노션 페이지 제목 */
  title: string;
  /** 노션 페이지 주소(절대 URL) */
  href: string;
}

/**
 * 누르면 답변에 쓴 노션 페이지가 새 탭으로 열리는 링크.
 *
 * 회의 문서 근거 버튼(「기록 N개에서 찾았어요」)과 같은 높이와 테두리로 그 옆에 놓여요.
 * 제목이 답변 폭보다 길면 말줄임으로 줄여요.
 */
export default function NotionEvidenceButton({
  title,
  href,
}: NotionEvidenceButtonProps) {
  return (
    <Container href={href}>
      <LogoIcon size={16} />
      <Title>{title}</Title>
      <OpenExternalIcon size={12} />
    </Container>
  );
}

const Container = styled(LinkTo)`
  display: inline-flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  max-width: 100%;
  height: 2rem; /* 32px */
  padding: 0 0.5rem; /* 8px */
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[600]};
  text-decoration: none;

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

const LogoIcon = styled(NotionLogo)`
  flex-shrink: 0;
`;

const Title = styled.span`
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  ${({ theme }) => theme.text.caption02};
`;

const OpenExternalIcon = styled(OpenExternal)`
  flex-shrink: 0;
  color: ${({ theme }) => theme.neutral[700]};
`;
