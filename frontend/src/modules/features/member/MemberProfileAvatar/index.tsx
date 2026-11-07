import useMeQuery from "@api/queries/useMeQuery";
import Popover from "@composites/Popover";
import styled from "@emotion/styled";
import useLogout from "@hooks/domain/auth/useLogout";
import Avatar from "@primitives/ui/Avatar";
import { useHasDialog } from "@provider/context/dialogContext";

import ProfileMenu from "./ui/ProfileMenu";

/**
 * 로그인한 회원의 프로필 아바타. 누르면 아래에 프로필 메뉴를 오른쪽 끝을 맞춰 열어요(AUTH-R17).
 *
 * 사진이 없는 회원은 `Avatar`가 닉네임 첫 글자로 대신 그려요. 이것이 기본 프로필 이미지예요(AUTH-R15).
 * 다시 누르거나 바깥을 누르거나 ESC를 누르면 메뉴를 닫아요. 로그아웃을 처리하는 동안이나 모달이 떠 있는 동안에는 닫지 않아요.
 * 처리가 오래 걸려도 진행 중인지 볼 곳이 메뉴뿐이고, 모달을 닫아도 메뉴는 뒤에 남아 있어야 하기 때문이에요.
 */
export default function MemberProfileAvatar() {
  const { data: me } = useMeQuery();
  const { logout, isLoggingOut } = useLogout();
  const hasDialog = useHasDialog();

  return (
    <Popover
      openOn="click"
      placement="bottom-end"
      isDismissible={!isLoggingOut && !hasDialog}
      content={
        <ProfileMenu isLoggingOut={isLoggingOut} onLogoutSelect={logout} />
      }
    >
      {(triggerProps) => (
        <Trigger type="button" {...triggerProps}>
          <Avatar
            label="내 프로필"
            src={me?.profileImageUrl ?? undefined}
            name={me?.nickname}
            size={32}
          />
        </Trigger>
      )}
    </Popover>
  );
}

const Trigger = styled.button`
  position: relative;
  display: flex;
  border-radius: 50%;

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }

  /* 손가락으로 누를 때는 보이는 크기(32px)는 그대로 두고 누르는 영역만 44px로 넓혀요(RESP-R9) */
  @media (pointer: coarse) {
    &::after {
      position: absolute;
      inset: -0.375rem; /* 6px */
      content: "";
    }
  }
`;
