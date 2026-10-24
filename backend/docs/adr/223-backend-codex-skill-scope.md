# 백엔드 commit·PR Codex 스킬은 backend/.agents/skills에 두고 자연어 implicit invocation과 명시적 호출 fallback을 함께 지원한다.

## 상태

Proposed

## 관련 Issue

- #223 [BE] 백엔드 Codex 스킬 검색 경로와 자연어 호출 정비

## 한 줄 요약

백엔드 commit·PR Codex 스킬은 backend/.agents/skills에 두고 자연어 implicit invocation과 명시적 호출 fallback을 함께 지원한다.

## 왜 이 결정이 필요했나

백엔드 전용 스킬이 비공식 backend/.codex/skills 경로에 있어 Codex의 자동 발견과 자연어 선택을 보장할 수 없었다.

백엔드 팀원이 backend에서 commit·PR 작업을 요청할 때 공통 Issue 스킬과 백엔드 전용 전달 스킬을 함께 사용하되 프론트엔드 범위에는 백엔드 스킬을 노출하지 않아야 했다.

결정 동인:

- Codex의 공식 .agents/skills 검색 규칙을 따른다.
- 공통 워크플로우와 백엔드 전용 워크플로우의 범위를 구분한다.
- 팀원이 자연어 작업 요청과 명시적 스킬 호출을 모두 사용할 수 있게 한다.
- 초안 요청과 원격 쓰기 권한의 경계를 유지한다.

## 트레이드 오프

- 모든 스킬을 루트 .agents/skills에 배치: 저장소 루트에서도 발견되지만 프론트엔드 작업에 백엔드 commit·PR 스킬까지 노출된다.
- 백엔드 전용 스킬을 backend/.agents/skills에 배치: backend 작업 디렉터리가 필요하지만 모듈 범위를 유지하면서 루트 공통 스킬도 함께 발견한다.

## 무엇을 결정했나

백엔드 commit·PR Codex 스킬은 backend/.agents/skills에 두고 자연어 implicit invocation과 명시적 호출 fallback을 함께 지원한다.

백엔드 작업을 backend에서 시작하는 규칙으로 공통·백엔드 스킬을 함께 발견하고, 모듈별 책임과 프론트엔드 분리를 유지할 수 있다.

## 결과

- 백엔드 팀원은 Codex를 backend에서 시작해야 두 스킬 범위를 모두 자동 발견한다.
- 한국어 대표 요청은 SKILL.md description을 통해 implicit invocation 후보가 된다.
- 명시적인 $knot-commit과 $knot-pr 호출은 선택 실패 시 fallback으로 남는다.
- 저장소 루트에서 시작한 Codex는 하위 백엔드 스킬을 자동 발견하지 않는다.

## 다시 논의해야 할 조건

- Codex의 저장소 스킬 검색 규칙이 하위 디렉터리 탐색을 지원하도록 바뀔 때
- 프론트엔드와 백엔드가 동일한 commit·PR 스킬 계약을 공유하기로 결정할 때
- 모듈별 스킬 이름 중복 또는 자연어 라우팅 충돌이 발생할 때

## 확인

- 예정 경로: `backend/docs/adr/223-backend-codex-skill-scope.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
