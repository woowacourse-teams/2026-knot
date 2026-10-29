import { css, type Theme } from "@emotion/react";
import styled from "@emotion/styled";
import type { HTMLAttributes, ReactNode, Ref } from "react";

interface SceneLayerProps extends HTMLAttributes<HTMLDivElement> {
  /** 지금 장면에 속해 보이는지 */
  isOn: boolean;
  ref?: Ref<HTMLDivElement>;
  children?: ReactNode;
}

/**
 * 장면에 맞춰 나타나고 사라지는 층.
 *
 * 숨은 층은 화면 낭독기와 키보드가 지나가도록 `aria-hidden`과 `inert`를 함께 걸어요.
 * 겹쳐 있는 다른 장면의 버튼으로 초점이 넘어가지 않게 하기 위해서예요.
 */
export default function SceneLayer({ isOn, children, ...props }: SceneLayerProps) {
  return (
    <Root $isOn={isOn} aria-hidden={!isOn} inert={!isOn} {...props}>
      {children}
    </Root>
  );
}

/** 장면 전환 때 함께 쓰는 나타남·사라짐 효과 */
export const sceneFade = ({ isOn }: { isOn: boolean }) => css`
  opacity: ${isOn ? 1 : 0};
  pointer-events: none;
  transition:
    opacity 0.7s ease,
    transform 0.7s cubic-bezier(0.2, 0.8, 0.2, 1);

  @media (prefers-reduced-motion: reduce) {
    transition: opacity 0.2s;
  }
`;

const Root = styled.div<{ $isOn: boolean }>`
  ${({ $isOn }) => sceneFade({ isOn: $isOn })};
`;

/** 숨은 장면에서는 눌리지 않고, 보이는 장면에서만 눌리게 해요. */
export const interactive = ({ isOn }: { isOn: boolean }) => css`
  pointer-events: ${isOn ? "auto" : "none"};
`;

/** 모바일 배치로 바뀌는 너비 */
export const mobile = "@media (max-width: 759px)";

export const focusRing = (theme: Theme) => css`
  &:focus-visible {
    outline: 2px solid ${theme.sub.accent[500]};
    outline-offset: 3px;
  }
`;
