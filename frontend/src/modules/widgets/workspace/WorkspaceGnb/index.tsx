import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import MemberProfileAvatar from "@features/member/MemberProfileAvatar";
import type { ReactNode } from "react";

import WorkspaceLocation from "./ui/WorkspaceLocation";
import WorkspaceNavPill from "./ui/WorkspaceNavPill";

interface WorkspaceGnbProps {
  /**
   * 왼쪽 패널을 여닫는 버튼 자리. Figma GNB의 좌우 대칭 구조를 따라요.
   *
   * 어떤 패널을 열 수 있는지는 화면마다 다르므로(목록은 탐색 화면에만 있어요)
   * GNB가 정하지 않고 레이아웃에서 받아요.
   */
  left?: ReactNode;
  /**
   * 오른쪽 패널을 여닫는 버튼 자리. 아바타 앞에 놓여요.
   *
   * 왼쪽과 마찬가지로 화면마다 다르므로(찾은 기록은 탐색 화면에만 있어요) 레이아웃에서 받아요.
   */
  right?: ReactNode;
}

/**
 * 워크스페이스 전역 상단바(GNB).
 *
 * 왼쪽 패널 버튼 옆에 지금 있는 워크스페이스와 탭을 알려 주는 위치 표시를 둬요(WS-R15).
 */
export default function WorkspaceGnb({ left, right }: WorkspaceGnbProps) {
  return (
    <Container>
      <Side>
        {left}
        <WorkspaceLocation />
      </Side>

      <WorkspaceNavPill />

      <Side $isTrailing>
        {right}
        <MemberProfileAvatar />
      </Side>
    </Container>
  );
}

const Container = styled.header`
  display: flex;
  align-items: center;
  height: 2.75rem; /* 44px */
  padding: 0 1.5rem; /* 24px */

  ${MEDIA_QUERY.mobile} {
    padding: 0 1rem; /* 16px */
  }
`;

const Side = styled.div<{ $isTrailing?: boolean }>`
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: ${({ $isTrailing }) =>
    $isTrailing ? "flex-end" : "flex-start"};
  gap: 0.5rem; /* 8px */
  min-width: 0;

  ${MEDIA_QUERY.mobile} {
    gap: 0.375rem; /* 6px */
  }
`;
