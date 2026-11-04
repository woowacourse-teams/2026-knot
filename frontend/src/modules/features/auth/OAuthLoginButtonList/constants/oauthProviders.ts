import GithubIcon from "@/assets/icons/github.svg";
import GoogleIcon from "@/assets/icons/google.svg";
import KakaoIcon from "@/assets/icons/kakao.svg";

/** 로그인 수단. 백엔드 로그인 시작 주소 `/oauth2/authorization/{수단}`의 마지막 자리와 같아요 */
export type OAuthProvider = "github" | "google" | "kakao";

/**
 * 화면에 놓는 순서대로 적은 로그인 수단(AUTH-R11). GitHub이 맨 위예요.
 *
 * 문구는 세 버튼 모두 「○○로 시작하기」로 맞춰요. GitHub 버튼 문구는 PostHog 가입 퍼널이 이 글자로 세고 있어 바꾸지 않아요.
 */
export const OAUTH_PROVIDERS = [
  { provider: "github", label: "GitHub으로 시작하기", Icon: GithubIcon },
  { provider: "google", label: "Google로 시작하기", Icon: GoogleIcon },
  { provider: "kakao", label: "카카오로 시작하기", Icon: KakaoIcon },
] as const satisfies readonly {
  provider: OAuthProvider;
  label: string;
  Icon: typeof GithubIcon;
}[];
