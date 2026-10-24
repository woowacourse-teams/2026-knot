# 문서 목록·상세의 내 확인 상태를 PENDING·CONFIRMED·NOT_REQUIRED로 통일한다

## 상태

Proposed

## 관련 Issue

- #496 [BE] 문서 상세 정보와 본문 조회 API 구현

## 한 줄 요약

문서 목록·상세의 내 확인 상태를 PENDING·CONFIRMED·NOT_REQUIRED로 통일한다

## 왜 이 결정이 필요했나

생성 이후 가입한 현재 Workspace 멤버도 문서를 읽지만 확인 대상은 아니다. Boolean false는 대상 미확인과 비대상을 구분하지 못한다.

사용자가 제공한 초기 상세 API의 confirmedByMe Boolean 계약을 최신 myConfirmationState 3상태 계약으로 변경했다. #496에 그 결정과 예정 ADR 경로가 기록되어 있다. 대안은 이 대화의 두 실제 계약을 재확인한 것이며 과거 팀 회의 이력을 주장하지 않는다.

결정 동인:

- 문서 생성 시 확인 대상 고정
- 신규 가입자의 조회 권한과 확인 의무 구분
- 목록·상세 판정 일치

## 트레이드 오프

- confirmedByMe Boolean 유지: 기존 초안이지만 false만으로 미확인 대상과 비대상을 구분할 수 없다
- myConfirmationState 3상태: 최신 API에서 선택한 계약이며 상태별 의미와 클라이언트 분기가 명확하다

## 무엇을 결정했나

문서 목록·상세의 내 확인 상태를 PENDING·CONFIRMED·NOT_REQUIRED로 통일한다

생성 당시 대상 기록과 confirmedAt으로 의무와 완료 여부를 함께 표현한다. 최신 사용자 API와 Issue #496의 선택을 구현한다.

## 결과

- 목록·상세는 같은 enum과 판정 규칙을 사용한다
- GET은 확인 대상 생성·확인 시각·문서 보관 상태를 변경하지 않는다
- 이후 합류한 멤버는 NOT_REQUIRED로 읽는다

## 다시 논의해야 할 조건

- 문서 확인 대상을 동적으로 추가하는 정책으로 바뀔 때

## 확인

- 예정 경로: `backend/docs/adr/496-document-my-confirmation-state.md`
- 결정 주체: 2026-10-06 사용자 최신 API 및 Issue #496; 팀 승인은 PR 리뷰 전
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
