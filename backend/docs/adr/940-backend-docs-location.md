# 루트 docs에서 harness를 제외한 모든 문서를 backend/docs로 이전한다.

## 상태

Proposed

## 관련 Issue

- #940 [BE] 루트 문서를 backend/docs로 이전하고 경로 계약 정합화

## 한 줄 요약

루트 docs에서 harness를 제외한 모든 문서를 backend/docs로 이전한다.

## 왜 이 결정이 필요했나

백엔드 문서가 루트 docs와 backend/docs에 나뉘어 있으며, 공통 Issue·ADR 하네스는 docs/adr 경로를 전제로 한다.

문서 작성자와 후속 구현자가 문서 종류에 따라 저장 위치를 달리 판단해야 하고, 하네스의 ADR 경로 계약도 이전 대상 위치와 맞지 않는다.

결정 동인:

- 문서 저장 위치를 backend/docs로 통일한다.
- 사용자가 지정한 docs/harness 예외를 유지한다.
- 기존 공통 Issue·ADR 하네스와 모든 문서 참조를 새 위치와 일치시킨다.

## 트레이드 오프

- 백엔드 문서만 선택적으로 옮기고 공통 ADR·제품 문서는 루트에 유지한다. 경로 변경 범위는 작지만 루트 docs의 소유 경계가 계속 섞인다.
- harness만 루트에 남기고 나머지 문서를 모두 backend/docs로 옮긴다. 사용자가 지정한 위치를 통일하지만 공통 ADR·제품 참조와 하네스 계약을 함께 갱신해야 한다.

## 무엇을 결정했나

루트 docs에서 harness를 제외한 모든 문서를 backend/docs로 이전한다.

선택적 이동은 앞선 논의에서 검토됐고, 사용자가 harness 외 전체를 이전하도록 범위를 확정했다. 경로를 소비하는 공통 하네스와 지침도 같은 변경에서 갱신한다.

## 결과

- backend/docs가 기존 루트 docs의 문서와 자산을 포함하는 정본이 된다.
- 공통 AGENTS, Issue/ADR 하네스, 워크플로와 문서 링크를 함께 갱신해야 한다.
- docs/harness는 루트의 공통 하네스로 남는다.

## 다시 논의해야 할 조건

- 다른 모듈의 독립 문서 트리가 생겨 backend/docs가 더 이상 모든 비-harness 문서의 적절한 소유 위치가 아닐 때 재검토한다.

## 확인

- 예정 경로: `backend/docs/adr/940-backend-docs-location.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
