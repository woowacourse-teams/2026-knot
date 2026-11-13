import LandingStage from "@widgets/landing/LandingStage";

/**
 * 공개 랜딩 화면 (`/`)
 *
 * 서비스 주소로 처음 들어온 사람이 로그인하지 않아도 서비스 소개와 사용법을 보고 바로 시작할 수 있어요.
 * 로그인한 사람은 `LandingGuard`가 이 화면을 거치지 않고 워크스페이스로 보냅니다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4308-42634 Design · 랜딩 7장면
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4227-10515 User Flow · 공개 진입
 */
export default function LandingPage() {
  return <LandingStage />;
}
