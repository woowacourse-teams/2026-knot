import styled from "@emotion/styled";
import Spacing from "@primitives/layout/Spacing";
import Stack from "@primitives/layout/Stack";
import Button from "@primitives/ui/Button";
import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import { clearAuthReturnPath, readAuthReturnPath } from "@utils/authReturnPath";
import { Navigate, useLocation, useNavigate } from "react-router";

import ClapIllustration from "@/assets/illustrations/clap.svg";

/**
 * 온보딩 - 가입 완료 화면 (`/onboarding/complete`)
 *
 * 닉네임 등록이 끝난 뒤 보여주는 환영 화면이다.
 * CTA를 누르면 워크스페이스 생성 및 참여(`/workspace`)로 이동한다.
 * 로그인 전 초대 참여가 401로 끝나며 기억해 둔 복귀 주소(`/invite/<credential>`)가 있으면
 * 그 주소로 대신 이동하고 저장값을 지운다. 백엔드가 가입 뒤 고정 주소로만 보내기 때문이다.
 *
 * 인사말에 쓸 닉네임은 `NicknameCard`가 라우터 state로 넘겨준다.
 * 새로고침하거나 주소를 직접 열면 state가 비어 있는데, 등록을 거치지 않고
 * 볼 이유가 없는 화면이므로 온보딩으로 되돌린다.
 *
 * 로고와 배경은 `CenteredLayout`이 담당한다.
 * 404·네트워크·서버 에러 화면이 이 화면과 같은 구조(일러스트 + 제목 + 설명 + 버튼)라,
 * 에러 화면을 만들 때 공통 컴포넌트로 묶을 수 있다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=443-325 온보딩/가입 완료
 */
export default function OnboardingCompletePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const nickname: string | undefined = location.state?.nickname;

  const handleStart = () => {
    const returnPath = readAuthReturnPath();

    clearAuthReturnPath();
    navigate(returnPath ?? PATH_ROUTE.WORKSPACE);
  };

  if (nickname === undefined) {
    return <Navigate to={PATH_ROUTE.ONBOARDING} replace />;
  }

  return (
    <Container align="center">
      <title>가입 완료 - knot</title>
      <Clap />
      <Spacing size={1.25} />

      <Title>knot에 오신 걸 환영해요, {nickname}님</Title>
      <Spacing size={1} />

      <Description>
        가입이 완료되었어요.
        <br />
        이제 함께 기록할 공간으로 이동할 차례예요.
      </Description>
      <Spacing size={4} />

      <StartButton size="lg" isFullWidth onClick={handleStart}>
        시작하기
      </StartButton>
    </Container>
  );
}

/** 버튼이 `width: 100%`로 너비를 정할 수 있도록 레이아웃의 가로를 물려받아요. */
const Container = styled(Stack)`
  width: 100%;
`;

const Clap = styled(ClapIllustration)`
  width: 3rem; /* 48px */
  height: 3rem;
`;

const Title = styled.h1`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[800]};
  text-align: center;
`;

/** 피그마의 318px은 두 줄이 지금 문구에서 나뉘는 지점이라 상한으로만 둡니다. */
const Description = styled.p`
  ${({ theme }) => theme.text.body02};
  max-width: 19.875rem; /* 318px */
  color: ${({ theme }) => theme.neutral[700]};
  text-align: center;
`;

/** 화면이 좁아지면 따라 줄어들도록 상한만 둡니다. */
const StartButton = styled(Button)`
  max-width: 22.5rem; /* 360px */
`;
