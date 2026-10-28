import styled from "@emotion/styled";
import OAuthLoginButtonList from "@features/auth/OAuthLoginButtonList";
import OAuthErrorNotice from "@features/auth/OAuthErrorNotice";
import useSessionExpiredToast from "@hooks/domain/auth/useSessionExpiredToast";
import Spacing from "@primitives/layout/Spacing";
import Stack from "@primitives/layout/Stack";

/**
 * 로그인 & 회원가입 화면 (`/login`)
 *
 * 서비스 진입점이다. 신규 사용자는 로그인 후 온보딩(`/onboarding`)으로 이어진다.
 * 로그인했던 탭의 토큰 재발급이 거절돼 이 화면에 왔으면 로그인 만료 토스트를 띄운다(ERR-07).
 * 배경과 로고, 가로 가운데 정렬과 위쪽 28% 배치는 `CenteredLayout`이 담당한다.
 *
 * @see {@link https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4727 로그인/기본}
 */
export default function LoginPage() {
  useSessionExpiredToast();

  return (
    <Container align="center">
      <Title>
        {/* 줄을 나눠도 글로는 띄어 쓴 한 문장이 되도록 줄바꿈 앞에 빈칸을 둬요 */}
        팀의 결정을 <br />
        모두의 기억으로
      </Title>
      <Spacing size={4.5} />

      <Actions>
        <OAuthErrorNotice />
        <OAuthLoginButtonList />
        <Spacing size={1} />
        <Terms>
          로그인하면 서비스 이용약관과 개인정보 처리방침에 동의하게 됩니다.
        </Terms>
      </Actions>
    </Container>
  );
}

/**
 * 자식이 `width: 100%`로 너비를 정할 수 있도록 레이아웃의 가로를 그대로 물려받아요.
 * 이게 없으면 부모 너비가 내용에 따라 정해져서 자식의 `100%`가 기준을 잃습니다.
 */
const Container = styled(Stack)`
  width: 100%;
`;

/**
 * 버튼과 약관 문구의 너비를 함께 정합니다.
 *
 * 피그마는 360px이지만 고정하지 않아요. 화면이 좁아지면 따라 줄어들어야
 * 레이아웃이 정한 좌우 여백(24px)을 침범하지 않습니다.
 */
const Actions = styled(Stack)`
  width: 100%;
  max-width: 22.5rem; /* 360px */
`;

const Title = styled.h1`
  ${({ theme }) => theme.text.title02};
  color: ${({ theme }) => theme.neutral[800]};
  text-align: center;
`;

const Terms = styled.p`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[400]};
  text-align: center;
`;
