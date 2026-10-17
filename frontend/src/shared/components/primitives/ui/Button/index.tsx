import { css, type SerializedStyles, type Theme } from "@emotion/react";
import styled from "@emotion/styled";
import type { ButtonHTMLAttributes } from "react";

import Spinner from "@/shared/components/primitives/ui/Spinner";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  size?: ButtonSize;
  variant?: ButtonVariant;
  isLoading?: boolean;
  isFullWidth?: boolean;
}

/** 버튼 크기. 피그마 Button/CTA의 L·M·S에 대응해요. */
export type ButtonSize = "lg" | "md" | "sm";
type ButtonSizeToken =
  "paddingX" | "paddingY" | "borderRadius" | "gap" | "iconSize" | "spinnerSize";

/** 버튼의 겉모양. */
export type ButtonVariant = "filled" | "outline" | "accent" | "danger";

/**
 * 버튼이 그려야 할 상태. prop이 아니라 `isLoading`·`disabled`로 계산해요.
 *
 * - `active` : 누를 수 있음
 * - `loading` : 처리 중. 스피너가 뜨고 누를 수 없음
 * - `inactive` : 비활성
 *
 * 둘 다 참이면 `loading`이 이깁니다.
 */
type ButtonStatus = "active" | "loading" | "inactive";

/**
 * 사이즈별 치수. 주석의 px이 피그마 원본 값이에요.
 *
 * height를 지정하지 않고 paddingY로 높이를 만듭니다.
 * 사용자가 글꼴 크기를 키워도 버튼이 함께 커져서 글자가 잘리지 않아요.
 */
const BUTTON_SIZE = {
  lg: {
    paddingX: "1.25rem" /* 20px */,
    paddingY: "1rem" /* 16px */,
    borderRadius: "0.875rem" /* 14px */,
    gap: "0.5rem" /* 8px */,
    iconSize: "1.5rem" /* 24px */,
    spinnerSize: "1.5rem" /* 24px */,
  },
  md: {
    paddingX: "1.125rem" /* 18px */,
    paddingY: "0.75rem" /* 12px */,
    borderRadius: "0.8125rem" /* 13px */,
    gap: "0.4375rem" /* 7px */,
    iconSize: "1.125rem" /* 18px */,
    spinnerSize: "1.375rem" /* 22px */,
  },
  sm: {
    paddingX: "1rem" /* 16px */,
    paddingY: "0.5rem" /* 8px */,
    borderRadius: "0.75rem" /* 12px */,
    gap: "0.375rem" /* 6px */,
    iconSize: "1rem" /* 16px */,
    spinnerSize: "1.25rem" /* 20px */,
  },
} as const satisfies Record<ButtonSize, Record<ButtonSizeToken, string>>;

/**
 * variant × status 조합별 색상.
 *
 * `loading`은 `active`와 같은 색입니다.
 */
const buttonAppearance = (theme: Theme) => {
  return {
    filled: {
      active: css`
        background-color: ${theme.primary};
        color: ${theme.neutral[0]};
      `,
      loading: css`
        background-color: ${theme.primary};
        color: ${theme.neutral[0]};
      `,
      inactive: css`
        background-color: ${theme.neutral[200]};
        color: ${theme.neutral[400]};
      `,
    },
    outline: {
      active: css`
        background-color: ${theme.neutral[0]};
        color: ${theme.neutral[700]};
        box-shadow: inset 0 0 0 1px ${theme.neutral[200]};
      `,
      loading: css`
        background-color: ${theme.neutral[0]};
        color: ${theme.neutral[700]};
        box-shadow: inset 0 0 0 1px ${theme.neutral[200]};
      `,
      inactive: css`
        background-color: ${theme.neutral[0]};
        color: ${theme.neutral[400]};
        box-shadow: inset 0 0 0 1px ${theme.neutral[200]};
      `,
    },
    accent: {
      active: css`
        background-color: ${theme.sub.accent[100]};
        color: ${theme.sub.accent[500]};
      `,
      loading: css`
        background-color: ${theme.sub.accent[100]};
        color: ${theme.sub.accent[500]};
      `,
      inactive: css`
        background-color: ${theme.neutral[200]};
        color: ${theme.neutral[400]};
      `,
    },
    danger: {
      active: css`
        background-color: ${theme.sub.warning[600]};
        color: ${theme.neutral[0]};
      `,
      loading: css`
        background-color: ${theme.sub.warning[600]};
        color: ${theme.neutral[0]};
      `,
      inactive: css`
        background-color: ${theme.neutral[200]};
        color: ${theme.neutral[400]};
      `,
    },
  } satisfies Record<ButtonVariant, Record<ButtonStatus, SerializedStyles>>;
};

/**
 * 액션을 실행하는 버튼.
 */
export default function Button({
  size = "md",
  variant = "filled",
  isLoading = false,
  isFullWidth = false,
  disabled = false,
  type = "button",
  children,
  ...props
}: ButtonProps) {
  const status: ButtonStatus = isLoading
    ? "loading"
    : disabled
      ? "inactive"
      : "active";

  return (
    <Root
      type={type}
      disabled={disabled || isLoading}
      aria-busy={isLoading}
      $size={size}
      $variant={variant}
      $status={status}
      $isFullWidth={isFullWidth}
      {...props}
    >
      <Content $isHidden={isLoading} $size={size}>
        {children}
      </Content>

      {isLoading && (
        <SpinnerWrapper>
          <Spinner size={BUTTON_SIZE[size].spinnerSize} />
        </SpinnerWrapper>
      )}
    </Root>
  );
}

const Root = styled.button<{
  $size: ButtonSize;
  $variant: ButtonVariant;
  $status: ButtonStatus;
  $isFullWidth: boolean;
}>`
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
  transition:
    background-color 0.3s ease-in,
    color 0.3s ease-in,
    box-shadow 0.3s ease-in;

  ${({ theme }) => theme.text.label01};

  width: ${({ $isFullWidth }) => ($isFullWidth ? "100%" : "auto")};
  padding: ${({ $size }) =>
    `${BUTTON_SIZE[$size].paddingY} ${BUTTON_SIZE[$size].paddingX}`};
  border-radius: ${({ $size }) => BUTTON_SIZE[$size].borderRadius};

  ${({ theme, $variant, $status }) =>
    buttonAppearance(theme)[$variant][$status]};

  /* 포커스 링 아웃라인 논의 필요 */
  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

const Content = styled.span<{ $isHidden: boolean; $size: ButtonSize }>`
  display: inline-flex;
  align-items: center;
  gap: ${({ $size }) => BUTTON_SIZE[$size].gap};
  opacity: ${({ $isHidden }) => ($isHidden ? 0 : 1)};

  & > svg {
    flex-shrink: 0;
    width: ${({ $size }) => BUTTON_SIZE[$size].iconSize};
    height: ${({ $size }) => BUTTON_SIZE[$size].iconSize};
  }
`;

const SpinnerWrapper = styled.span`
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
`;
