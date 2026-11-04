import styled from "@emotion/styled";
import LinkTo from "@primitives/ui/LinkTo";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";

import { focusRing, interactive } from "./SceneLayer";

interface StartLinkProps {
  isOn: boolean;
}

/**
 * 랜딩의 시작 자리에 두는 「시작하기」 버튼. 누르면 로그인 화면(`/login`)으로 가요(AUTH-R11).
 *
 * 로그인 수단(GitHub, Google, 카카오)과 「처음 가입한 방법으로 로그인해야 같은 계정이에요」 안내는 로그인 화면에서만 보여 줘요.
 * 랜딩에 세 버튼을 두면 가로로는 어색하고 세로로는 첫 장면이 로그인 화면처럼 보여, 랜딩에는 이동만 남겨요.
 * 페이지를 옮기는 동작이라 버튼이 아니라 링크로 두고, 모양만 `Shared/Button`의 lg filled와 맞춰요.
 */
export default function StartLink({ isOn }: StartLinkProps) {
  return (
    <Root href={PATH_ROUTE.LOGIN} $isOn={isOn}>
      시작하기
    </Root>
  );
}

// LinkTo는 받은 속성을 a 요소에 그대로 넘겨, 스타일용 속성은 걸러요
const Root = styled(LinkTo, {
  shouldForwardProp: (prop) => prop !== "$isOn",
})<{ $isOn: boolean }>`
  ${({ $isOn }) => interactive({ isOn: $isOn })};
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 20rem; /* 320px */
  margin-top: 0.875rem; /* 14px */
  padding: 1rem 1.25rem; /* 16px 20px */
  border-radius: 0.875rem; /* 14px */
  ${({ theme }) => theme.text.label01};
  background-color: ${({ theme }) => theme.primary};
  color: ${({ theme }) => theme.neutral[0]};
  text-decoration: none;
  ${({ theme }) => focusRing(theme)};
`;
