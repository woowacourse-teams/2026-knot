import {
  TAB_ENTER_EASING,
  TAB_INDICATOR_DURATION_MS,
  TAB_POP_EASING,
  TAB_PRESS_DURATION_MS,
  TAB_PRESS_SCALE,
  TAB_RELEASE_DURATION_MS,
} from "@constants/tabMotion";
import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import useSlidingIndicator from "@hooks/common/useSlidingIndicator";
import useWorkspaceNav from "@hooks/domain/workspace/useWorkspaceNav";

/**
 * GNB 가운데의 내비 필. 홈 · 탐색 · 문서를 오가고 지금 화면을 채워진 모양으로 알려줘요.
 *
 * 채워진 모양은 칸마다 따로 칠하지 않고 진한 표시 하나가 칸 사이를 미끄러져요.
 * 칸마다 따로 바꾸면 바뀌는 동안 두 칸이 함께 반쯤 어두워 보여서예요.
 * 표시는 미끄러지는 동안 진행 방향으로 살짝 늘어났다가, 새 칸에 도착하며 물방울처럼 톡 맺혀요.
 * 누르는 동안 칸이 살짝 작아졌다가 떼면 통 튀어 돌아와요. 지금 탭을 누르면 표시도 함께 눌렸다가 제자리에서 톡 맺히고 화면은 그대로예요.
 * 움직임 줄이기를 켜 두면 눌림, 미끄러짐, 물방울 없이 바로 바뀌어요.
 */
export default function WorkspaceNavPill() {
  const {
    isHomeActive,
    isChatActive,
    isDocumentsActive,
    activeTabIndex,
    navigateToHome,
    navigateToExplore,
    navigateToDocuments,
  } = useWorkspaceNav();
  const { containerRef, indicatorRef } = useSlidingIndicator({
    activeTabIndex,
    activeTabSelector: '[aria-current="page"]',
  });

  return (
    <Container ref={containerRef} aria-label="워크스페이스 화면 이동">
      <Indicator
        ref={indicatorRef}
        aria-hidden="true"
        hidden={activeTabIndex === -1}
      />
      <NavItem
        type="button"
        aria-current={isHomeActive ? "page" : undefined}
        $isActive={isHomeActive}
        onClick={navigateToHome}
      >
        홈
      </NavItem>
      <NavItem
        type="button"
        aria-current={isChatActive ? "page" : undefined}
        $isActive={isChatActive}
        onClick={navigateToExplore}
      >
        탐색
      </NavItem>
      <NavItem
        type="button"
        aria-current={isDocumentsActive ? "page" : undefined}
        $isActive={isDocumentsActive}
        onClick={navigateToDocuments}
      >
        문서
      </NavItem>
    </Container>
  );
}

const Container = styled.nav`
  position: relative; /* 표시가 이 자리를 기준으로 칸 아래에 놓여요 */
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.125rem; /* 2px */
  height: 2.75rem; /* 44px */
  padding: 0.375rem; /* 6px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

/**
 * 지금 탭 칸 아래에 깔리는 진한 표시. 자리와 크기는 `useSlidingIndicator`가 칸을 재서 정해요.
 *
 * 탭이 바뀔 때만(`data-sliding="true"`) 미끄러지고, 처음 놓이거나 칸 크기가 바뀌면 바로 그 자리에 놓여요.
 * 모양(`scale`)은 `useIndicatorDroplet`이 바꿔서 여기서는 전환을 걸지 않아요. CSS 전환은 모양 애니메이션보다 앞서 그려져 둘을 함께 걸면 물방울이 끊겨요.
 */
const Indicator = styled.span`
  position: absolute;
  top: 0;
  left: 0;
  border-radius: 62.4375rem;
  background-color: ${({ theme }) => theme.neutral[700]};
  pointer-events: none;

  @media (prefers-reduced-motion: no-preference) {
    transition-property: translate, width;
    transition-duration: 0s;
    transition-timing-function: ${TAB_ENTER_EASING};

    &[data-sliding="true"] {
      transition-duration: ${TAB_INDICATOR_DURATION_MS}ms;
    }
  }
`;

const NavItem = styled.button<{ $isActive: boolean }>`
  position: relative; /* 먼저 놓인 표시보다 위에 글자를 그려요 */
  display: flex;
  align-items: center;
  height: 2rem; /* 32px */
  padding: 0 1.125rem; /* 18px */
  border-radius: 62.4375rem;

  /* 360px 화면에서 양옆 버튼 두 개씩과 함께 한 줄에 들어가도록 칸 여백을 줄여요(RESP-R1) */
  ${MEDIA_QUERY.mobile} {
    padding: 0 0.75rem; /* 12px */
  }

  color: ${({ theme, $isActive }) =>
    $isActive ? theme.neutral[0] : theme.neutral[600]};
  white-space: nowrap;
  ${({ theme }) => theme.text.caption02};

  /* 글자색은 표시가 지나가는 동안 같은 시간에 바꿔요 */
  @media (prefers-reduced-motion: no-preference) {
    transition-property: color, transform;
    transition-duration:
      ${TAB_INDICATOR_DURATION_MS}ms, ${TAB_RELEASE_DURATION_MS}ms;
    transition-timing-function: ${TAB_ENTER_EASING}, ${TAB_POP_EASING};

    &:active {
      transform: scale(${TAB_PRESS_SCALE});
      transition-duration:
        ${TAB_INDICATOR_DURATION_MS}ms, ${TAB_PRESS_DURATION_MS}ms;
      transition-timing-function: ${TAB_ENTER_EASING}, ${TAB_ENTER_EASING};
    }
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
