# Notion Import 상태는 현재 Workspace의 모든 멤버에게 공개하고, 타 Workspace에는 존재를 숨기며, 민감정보 없는 실행 정보만 반환한다.

## 상태

Proposed

## 관련 Issue

- #261 [BE] Notion Import 상태 조회 API 구현

## 한 줄 요약

Notion Import 상태는 현재 Workspace의 모든 멤버에게 공개하고, 타 Workspace에는 존재를 숨기며, 민감정보 없는 실행 정보만 반환한다.

## 왜 이 결정이 필요했나

Notion Import는 공동 Workspace 문서를 갱신하지만 실행을 요청하지 않은 멤버도 완료 여부를 알아야 한다. 동시에 importRunId를 통한 tenant 정보 노출과 실패 정보의 민감정보 노출을 막아야 한다.

Workspace 멤버가 공동 문서의 Import 완료를 기다리고 있고, 다른 Workspace 사용자가 importRunId를 알거나 추측해 조회할 수 있는 상황이다.

결정 동인:

- 공동 Workspace 데이터의 상태 가시성
- 역할 분기 없는 단순한 권한 검사
- tenant 존재 정보 비노출
- 민감정보 없는 오류 공개

## 트레이드 오프

- Import 요청자만 조회: 노출 범위는 가장 작지만 공동 작업자가 진행 상태를 확인할 수 없다.
- Workspace OWNER만 조회: 관리 권한은 명확하지만 MEMBER의 공동 문서 진행 화면을 막는다.
- 현재 Workspace의 모든 멤버 조회: 공동 작업 흐름과 구현 단순성을 얻는 대신 실패 사유를 안전한 일반 문구로 제한해야 한다.

## 무엇을 결정했나

Notion Import 상태는 현재 Workspace의 모든 멤버에게 공개하고, 타 Workspace에는 존재를 숨기며, 민감정보 없는 실행 정보만 반환한다.

Import 결과는 Workspace 공동 데이터이며 역할별 차등의 제품 이점이 작다. 현재 멤버십 하나로 권한을 판정하고 타 tenant에는 동일한 404를 반환하면 구현은 단순하면서 tenant 격리를 유지할 수 있다.

core domain/application은 특정 외부 공급자에 묶이지 않도록 `ContentImportRun`, `ContentImportStatus`, `ContentImportQueryService`, `ContentImportStatusResult` 이름을 사용한다. 기존 Notion API 이름, HTTP 경로, JSON 필드와 오류 메시지는 Presentation mapping boundary에만 남긴다.

## 결과

- OWNER와 MEMBER가 같은 상태 응답으로 공동 문서 준비 여부를 확인한다.
- 타 Workspace 사용자는 Import Run의 존재 여부를 구분할 수 없다.
- MVP에서는 실패 원문 저장 컬럼을 두지 않고, FAILED 상태의 `failureReason`은 Presentation 응답 매핑에서 `Notion 문서를 가져오지 못했습니다`로 계산한다.
- domain/application의 오류 코드는 `ContentImport*` 기준으로 유지하고, 기존 `NOTION_IMPORT_*` API 오류 코드와 메시지는 Presentation 예외 매핑에서만 노출한다.
- 새 역할이나 더 민감한 진단 필드가 추가되면 공개 범위를 다시 검토해야 한다.

## 다시 논의해야 할 조건

- Workspace 역할이 OWNER와 MEMBER보다 세분화될 때
- Import 상태 응답에 문서 제목, URL, 외부 식별자 또는 운영 진단 정보가 추가될 때
- 실패 사유 공개 정책이 제품 기능으로 확장될 때

## 확인

- 예정 경로: `backend/docs/adr/261-notion-import-status-access-policy.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
