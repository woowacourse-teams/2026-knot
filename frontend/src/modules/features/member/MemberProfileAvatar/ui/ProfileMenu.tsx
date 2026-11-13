import styled from "@emotion/styled";
import Spinner from "@primitives/ui/Spinner";

import LogoutIcon from "@/assets/icons/logout.svg";

interface ProfileMenuProps {
  /** 로그아웃을 처리하는 중인지. 켜지면 아이콘 자리에 스피너가 돌고 다시 누를 수 없어요(AUTH-R19) */
  isLoggingOut: boolean;
  /** 「로그아웃」을 누르면 불러요 */
  onLogoutSelect: () => void;
}

/**
 * 프로필 사진이 여는 메뉴. 「로그아웃」 하나만 있어요(AUTH-R17).
 *
 * 메뉴를 열면 항목으로 포커스를 옮겨, 키보드로 연 사람이 바로 고를 수 있어요.
 * 처리 중에도 `disabled`로 두지 않아요. 포커스가 항목에 남아야 실패 뒤 바로 다시 누르고, 묻는 창을 닫은 뒤 포커스가 돌아와요.
 */
export default function ProfileMenu({
  isLoggingOut,
  onLogoutSelect,
}: ProfileMenuProps) {
  return (
    <Container aria-label="프로필 메뉴">
      <MenuItem
        type="button"
        onClick={onLogoutSelect}
        aria-disabled={isLoggingOut}
        autoFocus
      >
        {isLoggingOut ? <Spinner size="1.125rem" /> : <LogoutIcon size={18} />}
        로그아웃
      </MenuItem>
    </Container>
  );
}

/** 피그마 Menu/프로필: 폭 240px · 안쪽 6px */
const Container = styled.nav`
  display: flex;
  flex-direction: column;
  width: 15rem; /* 240px */
  padding: 0.375rem; /* 6px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;

const MenuItem = styled.button`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
  padding: 0.625rem 0.75rem; /* 10px 12px */
  border-radius: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  line-height: 1.125rem; /* 18px, 아이콘과 같은 높이라 두 상태의 메뉴 높이가 같아요 */
  text-align: left;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[600]};
  }

  &[aria-disabled="true"] {
    color: ${({ theme }) => theme.neutral[500]};
    cursor: not-allowed;
  }

  &:hover:not([aria-disabled="true"]) {
    background-color: ${({ theme }) => theme.neutral[100]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;
