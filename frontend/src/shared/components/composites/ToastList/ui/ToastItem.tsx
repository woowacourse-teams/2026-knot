import styled from "@emotion/styled";
import { useEffect } from "react";

import useTimeout from "@hooks/common/useTimeout";
import FadeCollapse, {
  FADE_COLLAPSE_LEAVE_MS,
} from "@primitives/animation/FadeCollapse";
import Toast, { type ToastVariant } from "@primitives/ui/Toast";

import type { ShownToast } from "../types/toast";

/** 주의·오류는 읽고 대처할 시간이 더 필요해 길게 둬요 */
const TOAST_DURATION_MS = {
  success: 5000,
  caution: 8000,
  error: 8000,
} as const satisfies Record<ToastVariant, number>;

interface ToastItemProps {
  toast: ShownToast;
  /** 떠 있는 시간이 다 되면 불러요 */
  onExpire: (id: number) => void;
  /** 사라지는 애니메이션이 끝나면 불러요 */
  onLeaveEnd: (id: number) => void;
}

/** 토스트 하나가 나타나고, 떠 있는 시간을 세고, 사라지기까지를 맡는 컴포넌트 */
export default function ToastItem({
  toast,
  onExpire,
  onLeaveEnd,
}: ToastItemProps) {
  const { id, variant, message, icon, shownAt, isLeaving } = toast;

  const { start: startShowing } = useTimeout({
    timeout: TOAST_DURATION_MS[variant],
    callback: () => onExpire(id),
  });

  const { start: startLeaving } = useTimeout({
    timeout: FADE_COLLAPSE_LEAVE_MS,
    callback: () => onLeaveEnd(id),
  });

  useEffect(() => {
    startShowing();
  }, [startShowing, shownAt]);

  useEffect(() => {
    if (isLeaving) startLeaving();
  }, [isLeaving, startLeaving]);

  return (
    // 사라지는 중인 토스트는 이미 끝난 알림이라 낭독기가 다시 읽지 않게 숨겨요
    <div aria-hidden={isLeaving}>
      <FadeCollapse isLeaving={isLeaving}>
        <ToastGapWrapper>
          <Toast variant={variant} message={message} icon={icon} />
        </ToastGapWrapper>
      </FadeCollapse>
    </div>
  );
}

// 간격을 목록의 gap이 아니라 칸 안에 둬야 칸이 접힐 때 간격도 함께 사라져요
const ToastGapWrapper = styled.div`
  padding-top: 0.5rem; /* 8px */
`;
