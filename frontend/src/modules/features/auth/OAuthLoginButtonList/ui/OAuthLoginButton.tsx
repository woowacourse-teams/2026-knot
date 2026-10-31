import { css, type Theme } from "@emotion/react";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import type { ComponentProps } from "react";

import type { OAuthProvider } from "../constants/oauthProviders";

interface OAuthLoginButtonProps
  extends Omit<ComponentProps<typeof Button>, "size" | "variant" | "isFullWidth"> {
  provider: OAuthProvider;
}

/**
 * 로그인 수단 하나의 시작 버튼.
 *
 * 크기와 모양은 GitHub 버튼(`Button` lg, 가로 꽉 채움)을 그대로 쓰고, Google과 카카오는 색만 각 사 가이드대로 덮어요.
 * 비활성일 때는 덮지 않아 `Button`의 비활성 모양을 그대로 따라요.
 */
export default function OAuthLoginButton({ provider, ...props }: OAuthLoginButtonProps) {
  return (
    <Root
      size="lg"
      variant={BASE_VARIANT[provider]}
      isFullWidth
      $provider={provider}
      {...props}
    />
  );
}

/** 덮지 않는 상태(비활성)에서 따를 `Button`의 겉모양. Google은 흰 바탕이라 테두리가 있는 outline을 따라요 */
const BASE_VARIANT = {
  github: "filled",
  google: "outline",
  kakao: "filled",
} as const satisfies Record<OAuthProvider, ComponentProps<typeof Button>["variant"]>;

const brandAppearance = (theme: Theme) => ({
  github: null,
  google: css`
    background-color: ${theme.brand.google.container};
    color: ${theme.brand.google.label};
    box-shadow: inset 0 0 0 1px ${theme.brand.google.stroke};
  `,
  kakao: css`
    background-color: ${theme.brand.kakao.container};
    color: ${theme.brand.kakao.label};
  `,
});

// `Button`은 누를 수 있을 때와 처리 중(aria-busy)에 같은 색을 써요. 그 두 상태에만 수단의 색을 덮어요
const Root = styled(Button)<{ $provider: OAuthProvider }>`
  &:enabled,
  &[aria-busy="true"] {
    ${({ theme, $provider }) => brandAppearance(theme)[$provider]};
  }
`;
