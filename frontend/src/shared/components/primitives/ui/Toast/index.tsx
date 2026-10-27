import type { Theme } from "@emotion/react";
import styled from "@emotion/styled";

import AlertIcon from "@/assets/icons/alert.svg";
import CautionIcon from "@/assets/icons/caution18.svg";
import CheckIcon from "@/assets/icons/check18.svg";

/**
 * 토스트 종류. 피그마 Toast의 정상·주의·오류에 대응해요.
 *
 * - `success` : 방금 한 일의 결과를 알려요
 * - `caution` : 성공도 실패도 아닌 경우예요. 예: 연결이 끊겨 녹음이 중간에 끝남
 * - `error` : 내가 한 동작이 실패했고 다시 하면 될 때예요
 *
 * 테마의 `warning`은 빨강(오류)이라 주의에는 `caution` 색을 써요.
 */
export type ToastVariant = "success" | "caution" | "error";

/** 토스트 아이콘. `src/assets/icons`의 SVG 컴포넌트를 그대로 넘겨요 */
export type ToastIcon = typeof CheckIcon;

interface ToastProps {
  variant: ToastVariant;
  /** 알릴 문구. 놓인 자리의 폭을 넘으면 줄을 바꿔요. */
  message: string;
  /** 종류의 기본 아이콘 대신 그릴 아이콘. 예: 화상 회의를 연결한 뒤 이어폰 안내의 헤드폰 */
  icon?: ToastIcon;
}

const TOAST_ICON = {
  success: CheckIcon,
  caution: CautionIcon,
  error: AlertIcon,
} as const satisfies Record<ToastVariant, ToastIcon>;

const toastBackground = (theme: Theme) => {
  return {
    success: theme.sub.accent[700],
    caution: theme.sub.caution[700],
    error: theme.sub.warning[700],
  } satisfies Record<ToastVariant, string>;
};

/**
 * 잠깐 떠서 일의 결과를 알리는 토스트의 겉모양.
 *
 * 종류에 따라 배경색과 아이콘만 바뀌어요.
 * 낭독기에 읽히게 하는 `role`은 토스트를 쌓는 목록 쪽에서 붙여요.
 */
export default function Toast({ variant, message, icon }: ToastProps) {
  const Icon = icon ?? TOAST_ICON[variant];

  return (
    <Container $variant={variant}>
      <IconWrapper aria-hidden>
        <Icon size={18} />
      </IconWrapper>
      <Message>{message}</Message>
    </Container>
  );
}

const Container = styled.div<{ $variant: ToastVariant }>`
  display: inline-flex;
  align-items: flex-start;
  gap: 0.625rem; /* 10px */
  padding: 0.875rem 1.375rem 0.875rem 1.125rem; /* 14px 22px 14px 18px */
  border-radius: 1rem; /* 16px */
  overflow: hidden;
  background-color: ${({ theme, $variant }) =>
    toastBackground(theme)[$variant]};
  color: ${({ theme }) => theme.neutral[0]};
`;

/** 문구가 여러 줄이어도 아이콘이 첫 줄 가운데에 오도록 한 줄 높이만큼만 차지해요 */
const IconWrapper = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  height: 1.5rem; /* 24px — label01의 한 줄 높이 */
`;

const Message = styled.p`
  ${({ theme }) => theme.text.label01};
  min-width: 0;
  word-break: keep-all; /* 한글 어절 중간에서 줄이 끊기지 않게 해요 */
  overflow-wrap: break-word;
`;
