import { css } from "@emotion/react";
import styled from "@emotion/styled";

import DockColumn, {
  DOCK_BOTTOM,
  DOCK_HEIGHT,
  DOCK_TOAST_GAP,
} from "@primitives/layout/DockColumn";

import type { ShownToast } from "./types/toast";
import ToastItem from "./ui/ToastItem";

export type { ShownToast } from "./types/toast";

export type ToastPlacement = "inline" | "floating";

interface ToastListProps {
  /** 그릴 토스트. 앞에 있을수록 위에 놓이고, 마지막 토스트가 맨 아래(독에 가장 가까이)에 놓여요 */
  toasts: ShownToast[];
  /** 레이아웃 안에 직접 놓거나 화면 하단 가운데에 고정해요 */
  placement: ToastPlacement;
  /** 토스트의 떠 있는 시간이 다 되거나 X를 누르면 불러요 */
  onExpire: (id: number) => void;
  /** 토스트의 사라지는 애니메이션이 끝나면 불러요 */
  onLeaveEnd: (id: number) => void;
}

/**
 * 떠 있는 토스트들을 위아래로 쌓아 그리는 목록.
 *
 * 독이 있는 화면에서는 레이아웃 안에 직접 놓고, 독이 없는 화면에서는 하단 가운데에 고정해요.
 * 어떤 토스트를 띄울지는 정하지 않고, 받은 토스트를 그리기만 해요.
 */
export default function ToastList({
  toasts,
  placement,
  onExpire,
  onLeaveEnd,
}: ToastListProps) {
  // 독 위 자리에서는 토스트 폭만큼만 차지해야 해서 독 폭(DockColumn)은 하단에 띄울 때만 써요
  const Container =
    placement === "floating" ? FloatingContainer : InlineContainer;

  return (
    // 토스트가 없어도 늘 그려 둬야 낭독기가 새로 들어온 토스트를 놓치지 않아요
    <Container data-testid="toast-list">
      {toasts.map((toast) => (
        <ToastItem
          key={toast.id}
          toast={toast}
          onExpire={onExpire}
          onLeaveEnd={onLeaveEnd}
        />
      ))}
    </Container>
  );
}

const listStyle = css`
  z-index: 40; /* 모달 배경 막(30)보다 위 */
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 목록이 덮은 자리의 클릭을 가리지 않아요. 누를 것은 고정 토스트의 X뿐이고, X만 클릭을 받아요 */
  pointer-events: none;
`;

const InlineContainer = styled.div`
  ${listStyle}
  position: relative;
`;

const FloatingContainer = styled(DockColumn)`
  ${listStyle}
  position: fixed;
  /* 양옆을 0에 붙여야 DockColumn의 margin: 0 auto가 화면 가운데로 놓아요 */
  right: 0;
  /* 독이 있는 화면의 토스트 바닥과 같은 높이 */
  bottom: calc(${DOCK_BOTTOM} + ${DOCK_HEIGHT} + ${DOCK_TOAST_GAP});
  left: 0;
`;
