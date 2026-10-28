import styled from "@emotion/styled";

/** 피그마 Chip/새 소식 · 종류=NEW. 안 읽은 새 소식이 있는 메뉴 항목과 안 읽은 노트의 항목에 붙여요(NEWS-R3, NEWS-R4) */
export default function NewChip() {
  return <Root>NEW</Root>;
}

const Root = styled.span`
  flex-shrink: 0;
  padding: 0.125rem 0.5rem; /* 2px 8px */
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.sub.accent[100]};
  color: ${({ theme }) => theme.sub.accent[700]};
  ${({ theme }) => theme.text.caption01};
`;
