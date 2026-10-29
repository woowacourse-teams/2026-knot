import styled from "@emotion/styled";
import Stack from "@primitives/layout/Stack";
import NicknameCard from "@widgets/member/NicknameCard";

/**
 * 온보딩 - 닉네임 입력 화면 (`/onboarding`)
 *
 * 로그인 직후 사용자 정보를 등록하는 화면이다.
 * 입력 전 / 입력 중 / 입력 에러 세 가지 상태는 `NicknameCard`가 스스로 다룬다.
 * 등록에 성공하면 가입 완료(`/onboarding/complete`)로 이동한다.
 *
 * 로고와 배경은 `CenteredLayout`이 담당한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=422-390 온보딩/닉네임 입력
 */
export default function OnboardingPage() {
  return (
    <Root align="center">
      <title>닉네임 정하기 - knot</title>
      <NicknameCard />
    </Root>
  );
}

/**
 * 카드가 `width: 100%`로 너비를 정할 수 있도록 레이아웃의 가로를 물려받아요.
 * 입력창과 버튼의 360px은 카드가 정합니다(456 − 좌우 padding 48).
 */
const Root = styled(Stack)`
  width: 100%;
`;
