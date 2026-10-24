# 개발용 Discord 챗봇은 Mac의 Codex OAuth로 답하고 n8n 실패 시 Knot 범위의 읽기 전용 Notion MCP로 복구한다

## 상태

Proposed

## 관련 Issue

- #450 [BE] 개발 생산성용 Discord Notion 챗봇과 반복 품질 검증 구성

## 한 줄 요약

개발용 Discord 챗봇은 Mac의 Codex OAuth로 답하고 n8n 실패 시 Knot 범위의 읽기 전용 Notion MCP로 복구한다

## 왜 이 결정이 필요했나

제품 기능과 분리한 개발 생산성 챗봇을 개인 Codex 로그인 환경에서 운영하려 한다.

n8n 조회가 HTTP 503으로 실패하고 문서 누락·복구 경고 노출이 발생해 직접 조회와 답변 검증이 필요했다.

결정 동인:

- 개인 Codex OAuth와 Luna 강도 선택 유지
- Knot 하위 읽기 전용 범위와 인증값 외부 저장
- 외부 조회 장애 복구와 실제 오답 재현

## 트레이드 오프

- 24시간 클라우드에서 봇 실행: Mac 가동 의존성을 줄이지만 개인 로그인 환경과 별도 상시 운영 구성이 필요하여 이번 범위에서 선택하지 않음
- Mac에서 봇·Codex CLI 실행: 개인 로그인 환경을 유지하고 기존 n8n에 조회를 맡길 수 있으나 잠자기·재부팅 시 중단; 사용자가 선택함

## 무엇을 결정했나

개발용 Discord 챗봇은 Mac의 Codex OAuth로 답하고 n8n 실패 시 Knot 범위의 읽기 전용 Notion MCP로 복구한다

사용자가 Mac Codex OAuth 실행과 Notion MCP 직접 접근을 선택했다. 기존 n8n을 유지하면서 조회 실패를 복구하고 별도 반복 평가로 실제 답변 품질을 확인한다.

## 결과

- 개인 로그인·비밀값은 Mac Keychain과 ignored .env에 유지
- Notion 본문은 Knot 루트 범위에 한정하며 쓰기 도구를 제공하지 않음
- 복구 성공은 간결하게 답하고 전체 실패만 단계·원인을 안내
- Mac 전원·로그인·네트워크와 구독 사용량에 의존
- 최대 5개 문서와 문맥 제한, 모델 평가 오류가 남음

## 다시 논의해야 할 조건

- Mac 가동 의존성 때문에 24시간 운영이 필요해질 때
- n8n 안정화 후 조회 경로를 단순화하려 할 때
- 검색 누락·응답 지연·구독 제한이 반복될 때
- 조회 범위·인증 주체·문서 보존 정책을 변경할 때

## 확인

- 예정 경로: `backend/docs/adr/450-discord-assistant-local-codex-notion.md`
- 결정 주체: 요청자 결정, 팀 리뷰 전 Proposed
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
