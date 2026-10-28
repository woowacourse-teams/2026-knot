import styled from "@emotion/styled";
import Stack from "@primitives/layout/Stack";
import { useEffect, useState } from "react";

import { type OAuthProvider, OAUTH_PROVIDERS } from "./constants/oauthProviders";
import OAuthLoginButton from "./ui/OAuthLoginButton";

/**
 * OAuth 진입점. 뒤에 로그인 수단을 붙여 `/oauth2/authorization/github`처럼 써요.
 *
 * 백엔드가 이 주소를 받으면 그 수단의 로그인 페이지로 다시 보내고,
 * 로그인이 끝나면 callback까지 처리한 뒤 쿠키를 심어 프론트로 돌려보내요.
 * `/login/oauth2/code/{수단}`은 그 과정에서 백엔드가 쓰는 주소라 프론트가 부르지 않습니다.
 */
const OAUTH_AUTHORIZATION_URL = `${process.env.API_BASE_URL}/oauth2/authorization`;

/** 넘어가지 못하고 화면이 남았을 때, 처리 중을 푸는 데까지 기다리는 시간 */
const REDIRECT_TIMEOUT_MS = 10_000;

/**
 * GitHub, Google, 카카오 계정으로 로그인을 시작하는 버튼 묶음.
 *
 * 하나를 누르면 그 수단으로 넘어갈 때까지 누른 버튼은 처리 중이 되고, 세 버튼 모두 다시 누를 수 없어요(AUTH-R12).
 * 넘어가지 못하고 이 화면이 남으면 처리 중을 풀어 다시 누를 수 있게 해요.
 * 수단마다 다른 계정이라 버튼 아래에 처음 가입한 방법으로 로그인하라고 알려 줘요(AUTH-R14).
 */
export default function OAuthLoginButtonList() {
  const [redirectingProvider, setRedirectingProvider] =
    useState<OAuthProvider | null>(null);

  // 처리 중인데 이 화면이 남는 경우는 두 가지예요. 둘 다 처리 중을 풀지 않으면 새로고침하기 전까지 버튼을 누를 수 없어요
  useEffect(() => {
    if (redirectingProvider === null) return;

    const stopRedirecting = () => setRedirectingProvider(null);

    // 뒤로 가기로 돌아오면 브라우저가 떠날 때의 화면을 그대로 되살리고 pageshow를 알려요
    window.addEventListener("pageshow", stopRedirecting);
    // 브라우저의 중지 버튼이나 Esc로 이동이 멈추면 브라우저가 알려 주지 않아, 시간이 지나면 풀어요
    const timerId = window.setTimeout(stopRedirecting, REDIRECT_TIMEOUT_MS);

    return () => {
      window.removeEventListener("pageshow", stopRedirecting);
      window.clearTimeout(timerId);
    };
  }, [redirectingProvider]);

  const handleClick = (provider: OAuthProvider) => {
    setRedirectingProvider(provider);
    window.location.href = `${OAUTH_AUTHORIZATION_URL}/${provider}`;
  };

  return (
    <Container gap={1}>
      <ButtonGroup>
        {OAUTH_PROVIDERS.map(({ provider, label, Icon }) => (
          <OAuthLoginButton
            key={provider}
            provider={provider}
            isLoading={redirectingProvider === provider}
            disabled={redirectingProvider !== null}
            onClick={() => handleClick(provider)}
          >
            <Icon />
            {label}
          </OAuthLoginButton>
        ))}
      </ButtonGroup>
      <Guide>처음 가입한 방법으로 로그인해야 같은 계정이에요</Guide>
    </Container>
  );
}

const Container = styled(Stack)`
  width: 100%;
`;

/** 로그인 화면(360px)과 모바일 모두 세로로 쌓아요. 랜딩에는 이 묶음 대신 로그인 화면으로 가는 링크만 둬요. */
const ButtonGroup = styled.div`
  display: grid;
  gap: 0.75rem; /* 12px */
`;

const Guide = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
  text-align: center;
`;
