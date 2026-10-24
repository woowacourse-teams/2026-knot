# 요청 ID 기반 로그 연결을 먼저 도입하고 Java Agent·Tempo는 후속 Dev PoC로 분리한다

## 상태

Proposed

## 관련 Issue

- #672 [BE] Nginx와 Spring 요청 ID 기반 단건 로그 연결

## 한 줄 요약

요청 ID 기반 로그 연결을 먼저 도입하고 Java Agent·Tempo는 후속 Dev PoC로 분리한다

## 왜 이 결정이 필요했나

기존 Nginx·Spring·Alloy·Loki 관측을 재사용하면서 서버별 단건 오류와 지연 원인을 연결해야 한다.

여러 서버의 개별 로그와 지표는 보이지만 한 요청의 처리 서버와 오류를 함께 찾기 어렵다.

결정 동인:

- 단건 오류 추적을 먼저 제공
- 비동기 DB 큐 문맥의 한계 인정
- 단계별 검증·복구 가능

## 트레이드 오프

- A: 요청 ID 로그 연결만 도입. 기존 수집 재사용과 낮은 비용, 세부 병목 수동 계측 필요
- D: Java Agent·Tempo만 도입. 구간 자동 계측, DB 큐 작업 연결과 기존 로그 상관관계는 별도 설계 필요
- A+D 단계 도입: 로그 연결 우선·후속 Dev 트레이싱. 즉시 가치와 검증 분리, 단계별 구현·운영 비용

## 무엇을 결정했나

요청 ID 기반 로그 연결을 먼저 도입하고 Java Agent·Tempo는 후속 Dev PoC로 분리한다

Nginx·Spring 간 로그 연결을 공통 기반으로 먼저 고정하면 후속 트레이싱 및 업무 ID 연결에서도 재사용 가능하다.

## 결과

- 단건 로그 검색과 처리 서버 확인 가능
- 요청 ID만으로 DB 큐 Worker나 AI 세부 지연을 증명하지 못함
- 외부 입력 검증·async 문맥 정리·ID 라벨 제한을 지속 유지

## 다시 논의해야 할 조건

- 트레이싱 PoC 완료로 Trace ID 중심 조회를 도입할 때
- 게이트웨이 또는 비동기 실행 구조 변경 시

## 확인

- 예정 경로: `backend/docs/adr/672-request-id-log-correlation-first.md`
- 결정 주체: 사용자 승인, 팀 리뷰 예정
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
