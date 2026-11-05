import LandingStage from "@widgets/landing/LandingStage";
import { LANDING_TECHNOLOGY_SCENES } from "@widgets/landing/LandingStage/constants/landing";

/**
 * 기술 소개 랜딩 화면 (`/landing/technology`)
 *
 * 공개 랜딩의 7장면에 녹음 기술, 탐색 기술 소개 두 장면을 더한 9장면이에요. 공개 랜딩(`/`)에서는 기술 소개를 뺐지만,
 * 팀이 가장 자신 있는 기술로 어떤 문제를 어떻게 풀었고 결과가 어땠는지 보여 줘야 할 때 이 주소를 열어요.
 * 로그인한 사람도 그대로 볼 수 있게 `LandingGuard`를 거치지 않아요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5781-591 Design · 녹음 기술
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5781-592 Design · 탐색 기술
 */
export default function LandingTechnologyPage() {
  return <LandingStage scenes={LANDING_TECHNOLOGY_SCENES} />;
}
