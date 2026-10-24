# PostHog 요청은 프론트 Cloudflare Worker가 우리 주소의 경로 하나로 받아 PostHog로 중계한다

## 상태

Proposed

## 관련 Issue

- #838 [FE] PostHog 요청을 우리 주소로 중계해 차단 줄이기
- #853 [FE] Sentry 요청도 우리 주소로 중계해 차단 줄이기

## 한 줄 요약

PostHog 요청은 프론트 Cloudflare Worker가 우리 주소의 경로 하나로 받아 PostHog로 중계한다

## 왜 이 결정이 필요했나

PostHog를 2026-10-16 연결했고 프론트는 Cloudflare Workers에 정적 파일만 올린다. 개발 · 운영이 같은 PostHog 키와 같은 Cloudflare 계정(Workers 무료 한도 하루 10만, 계정 단위)을 쓴다

2026-10-19 이스타 Chrome의 HAR에서 PostHog 이벤트 전송 11건이 모두 net::ERR_BLOCKED_BY_CLIENT로 막혀 그 브라우저의 기록이 남지 않았다

결정 동인:

- 차단을 피하는 범위
- 지금 요청 수와 무료 한도
- 변경이 저장소 PR로 리뷰되는지
- 사이트 배포에 주는 위험
- 관리 부담과 문제 해결 지원

## 트레이드 오프

- A. 프론트 Cloudflare Worker로 직접 중계: 브라우저 확장과 DNS 단계 차단을 모두 피하고 변경이 저장소에 남지만, 팀 공용 배포 설정을 바꾸고 Workers 요청 한도를 쓰며 PostHog가 문제 해결을 돕지 않는다
- B. PostHog Managed reverse proxy: 무료이고 한도 언급이 없으며 PostHog가 관리하지만, DNS 레코드와 PostHog 조직 설정을 저장소 밖에서 바꾸고 CNAME을 따라가는 DNS 단계 차단에는 걸릴 수 있다

## 무엇을 결정했나

PostHog 요청은 프론트 Cloudflare Worker가 우리 주소의 경로 하나로 받아 PostHog로 중계한다

측정한 요청 수가 무료 한도의 약 5~12%라 지금 규모에서 비용 문제가 없고, A가 차단을 더 넓게 피하며 모든 변경을 PR로 리뷰할 수 있다. 한도가 문제 되면 api_host만 바꿔 B로 옮길 수 있다

중계 경로는 `/relay`로 한다(2026-10-19 이스타). Worker는 `/relay` 아래를 목적지마다 하위 경로로 나눠 받고, PostHog는 `/relay/p`를 쓴다. 이렇게 두면 광고 차단에 똑같이 막히는 Sentry(공식 해결책이 tunnel 하나뿐이고 PostHog 같은 관리형 중계가 없음)도 같은 Worker에 하위 경로 하나를 더해 붙일 수 있다. Sentry 중계는 지금 하지 않고 문제가 생기면 그때 붙인다(2026-10-19 이스타).

같은 날 Sentry 요청도 광고 차단에 막히는 것을 확인해 #853으로 `/relay/s`를 붙였다(2026-10-19 이스타). Sentry SDK는 `tunnel: "/relay/s"`로 Sentry 주소 대신 우리 주소에 보내고, Worker는 봉투 첫 줄의 DSN이 우리 Sentry 프로젝트(수집 주소 · 프로젝트 id)일 때만 그 프로젝트의 envelope 주소로 넘긴다. 다른 프로젝트의 봉투는 넘기지 않고 거절해, 누구나 아무 Sentry 프로젝트로 보내는 공개 중계가 되지 않게 한다. Sentry 설정이 사용자 정보를 모으지 않으므로 방문자 IP는 넘기지 않는다.

2026-10-19 팀 Cloudflare 계정(Worker 기본 주소 `knoted.workers.dev`)에서 확인한 사실: Workers 무료 요금제이고, `knot-frontend-dev`(`dev.knoted.kr`)와 `knot-frontend`(`knoted.kr`)가 같은 계정에 있으며, 정적 파일만 나가는 지금은 Workers 요청이 0건이다. `knoted.kr` DNS도 같은 계정에 있어 B로 옮길 때도 이 계정에서 한다.

## 결과

- 긍정: 차단 확장 사용자 기록이 모이고, 우리 주소 경로라 DNS 단계 차단도 피한다
- 긍정: 중계 동작이 저장소 코드와 테스트로 남는다
- 부정: 프론트 배포에 Worker 코드가 생겨 팀이 관리해야 하고, 개발 · 운영이 Workers 무료 한도를 함께 쓴다
- 부정: PostHog 지원 없이 중계 문제를 팀이 직접 찾아야 한다
- 부정: Sentry도 중계하면서 Workers 요청이 늘어난다. 운영 밖(개발 서버)은 성능 측정을 모두 보내서(운영은 20%) 화면 이동마다 Sentry 요청이 생긴다

## 다시 논의해야 할 조건

- Workers 하루 요청이 약 7만에 닿을 때
- 운영 출시 뒤 사용자가 크게 늘 때
- 중계 경로가 차단 목록에 올라 기록이 다시 빠질 때

## 확인

- 예정 경로: `backend/docs/adr/838-posthog-first-party-proxy.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
