# FE 로컬 AI 리뷰는 구독 소유자의 claude setup-token을 팀에 배포하고 pnpm review 스크립트가 claude -p로 기존 /review 스킬을 실행하는 방식으로 운영한다

## 상태

Proposed

## 관련 Issue

- #232 [FE] 개발 환경에서 코드 리뷰 스크립트 추가

## 한 줄 요약

FE 로컬 AI 리뷰는 구독 소유자의 claude setup-token을 팀에 배포하고 pnpm review 스크립트가 claude -p로 기존 /review 스킬을 실행하는 방식으로 운영한다

## 왜 이 결정이 필요했나

FE 팀은 `/review` 스킬(파일별 병렬 서브 에이전트)로 PR 전 AI 리뷰를 하지만, 실행에는 각자의 Claude Code 세션과 개인 구독 토큰이 필요하다. FE CI/CD(#161)는 아직 없고 저장소에 Claude GitHub 앱을 추가할 권한도 없다.

`/review`의 토큰 소모가 커서 팀원 각자의 구독 한도가 빠르게 소진되고, 개발 중 리뷰를 돌리는 비용이 부담이 되었다. 상위 플랜을 가진 팀원 한 명의 토큰으로 팀 리뷰를 돌려 비용을 줄이려 한다.

결정 동인:

- 추가 비용 없이 기존 구독 안에서 해결
- 기존 /review 스킬과 checklist.md를 수정 없이 재사용
- PR 전 로컬에서 즉시 실행 가능
- 설정 부담 최소(Console 조직·결제, CI 워크플로우 불필요)
- GitHub 앱 추가 권한 부재

## 트레이드 오프

- Anthropic Console API 키 공유 — 팀 공유·회수가 정식 경로이고 약관 위험이 없으나 사용량 과금으로 추가 비용이 발생하고 Console 조직·결제 설정이 필요
- GitHub Actions PR AI 리뷰 — 로컬 토큰 배포가 필요 없으나 저장소에 Claude 앱을 추가할 권한이 없고 FE CI/CD(#161)가 미구축이며 PR 이후에만 리뷰를 받음

## 무엇을 결정했나

FE 로컬 AI 리뷰는 구독 소유자의 claude setup-token을 팀에 배포하고 pnpm review 스크립트가 claude -p로 기존 /review 스킬을 실행하는 방식으로 운영한다

추가 비용 없이 기존 스킬을 그대로 쓰면서 PR 전에 로컬에서 바로 리뷰를 받을 수 있고 설정 부담이 가장 적다. GitHub Actions 안은 현재 권한상 불가능하다.

## 결과

- 긍정: 팀원이 `pnpm review` 하나로 같은 기준의 리뷰 md를 개인 비용 없이 받는다
- 긍정: /review 스킬·체크리스트 단일 기준이 유지된다
- 부정: 팀 전체 사용량이 소유자 구독 한도에 잡혀 피크 시 소유자 작업까지 막힐 수 있다
- 부정: 개인 구독 토큰 공유는 Anthropic 계정 공유 약관에 저촉될 가능성이 있다
- 부정: 토큰 전달·회수가 저장소 밖 수동 절차에 의존한다
- 부정: 팀원 로컬에 Claude Code CLI 설치가 전제된다

## 다시 논의해야 할 조건

- 팀 리뷰로 소유자 구독 한도 초과가 반복될 때
- 토큰 유출 또는 Anthropic 약관·정책 문제가 확인될 때
- FE CI/CD(#161)가 갖춰져 GitHub Actions 기반 PR 리뷰를 붙일 수 있을 때
- 팀 구독 플랜이 바뀌어 각자 실행이 가능해질 때

## 확인

- 예정 경로: `backend/docs/adr/232-shared-subscription-token-local-ai-review.md`
- 결정 주체: Knot FE 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
