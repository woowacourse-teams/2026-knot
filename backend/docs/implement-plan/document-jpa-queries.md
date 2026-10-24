# 문서 조회 JPA 정합성 작업

기준일: 2026-10-07. 사용자 승인 범위는 열린 문서 PR #502·#504·#506·#508의 조회 구현과 의존 브랜치 최신화다.

## 범위와 브랜치

Workspace의 Spring Data JPA Repository → 조회 projection → QueryAdapter 패턴에 맞춘다. Workspace는 native SQL도 사용하지만 문서 쿼리는 먼저 JPQL로 표현한다. Entity 연관관계·schema·외부 API 계약은 변경하지 않는다.

브랜치는 `develop → #496 → #495`, `#496 → #497 → #498` 관계다. #496에 최신 develop을 정상 머지한 뒤 부모 변경을 자식에 순서대로 정상 머지한다. 독립 목록 브랜치를 확인 브랜치에 합치거나 이력을 재작성하지 않는다.

## 구현 흐름과 메서드

| 작업 | 흐름 | 읽기 메서드 |
| --- | --- | --- |
| #496 상세 | DetailQueryAdapter → DocumentReadJpaRepository → DocumentDetailRow → Snapshot | findDetail |
| #495 목록 | ListQueryAdapter → 목록 JPA Repository → 주제/카드 Row → Result | findTopics, findPage |
| #497 확인 현황 | ConfirmationQueryAdapter → 확인 JPA Repository → 집계/대상 Row → Result | findSummary, findPage |
| #498 확인 처리 | 기존 JPA 저장·잠금 → 변경된 집계 조회 → 보관 전환 | 기존 명령과 탈퇴 이벤트 경로 유지 |

## 저장과 동시성

상세의 본문과 확인 집계는 하나의 JPQL aggregate 결과로 읽는다. 활성 멤버십 행만 조인하며 현재 활성 멤버십의 유일성 제약으로 재가입 이력에 따른 집계 중복을 막는다. 대상이 없는 문서는 집계 0을 반환한다.

목록과 확인 현황의 여러 조회는 기존 REPEATABLE_READ 경계를 유지한다. size+1 조회, 정렬 동률과 커서, 전체 조건 기준 주제 집계를 그대로 검증한다. 확인 처리와 탈퇴의 기존 잠금 순서·트랜잭션·flush를 유지한다.

## TDD와 검증

1. 상세: JPA persist 후 즉시 조회의 집계 반영 테스트를 먼저 실행하고 Jdbc 경로의 실패를 관측한다.
2. 상세: JPQL 조회와 Row 변환으로 통과시키고 전체 check/bootJar를 실행한다.
3. 목록: 부모 머지 후 JPA 저장 반영과 기존 필터·페이지·snapshot 테스트로 검증한다.
4. 확인 현황: 부모 머지 후 JPA 저장 반영과 대상 정렬·페이지·snapshot 테스트로 검증한다.
5. 확인 처리: 부모 머지 후 명령·반복 확인·동시 확인·탈퇴 경합·rollback·HTTP 보안 회귀를 실행한다.
6. 각 브랜치의 목적별 커밋을 push하고 원격 head·부모 ancestry·PR diff 범위를 확인한다.

## 검토와 제한

별도 STT 모델 통합, 자동 생성 실행기, 신규 API와 migration은 이 작업에 포함하지 않는다. 사용자 소유 Notion 정합성 문서는 staged 대상에서 제외한다. PR 본문에는 사용자의 요청대로 검증 내용을 추가하지 않는다. 실제 공급자·운영 환경의 성능 개선을 주장하지 않는다.
