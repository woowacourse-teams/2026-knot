import styled from "@emotion/styled";
import { useSearchParams } from "react-router";

import {
  OAUTH_ERROR_MESSAGE,
  OAUTH_ERROR_PARAM,
  OAUTH_ERROR_VALUE,
} from "./constants/oauthError";

/**
 * GitHub, Google, 카카오 로그인이 실패해 돌아왔을 때 그 사실을 알립니다.
 *
 * 그 수단의 승인 화면에서 취소하고 돌아오면(`?error=oauth2_cancelled`) 알리지 않아요(AUTH-R16).
 */
export default function OAuthErrorNotice() {
  const [searchParams] = useSearchParams();

  if (searchParams.get(OAUTH_ERROR_PARAM) !== OAUTH_ERROR_VALUE) return null;

  return <Root role="alert">{OAUTH_ERROR_MESSAGE}</Root>;
}

/**
 * 실패했을 때만 자리를 차지하므로 아래 간격도 함께 가집니다.
 * 쓰는 쪽에서 `Spacing`으로 띄우면 알릴 것이 없을 때도 빈 자리가 남아요.
 */
const Root = styled.p`
  ${({ theme }) => theme.text.body02};
  width: 100%;
  margin-bottom: 0.75rem; /* 12px */
  color: ${({ theme }) => theme.sub.warning[800]};
  text-align: center;
  overflow-wrap: break-word;
`;
