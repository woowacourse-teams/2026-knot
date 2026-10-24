# #402 I04 초대 미리보기 계약

## 범위와 근거

2026-10-03 사용자가 채택한 공통 24시간 만료·취소 없는 복수 초대 계약과
Issue #402의 I04를 검증한다. 선행 #444 병합 뒤 develop을 base로 사용하며 실행 로직은 그대로다.
기존 `WorkspaceInvitationService.preview`와 `isValidAt`이 같은 expiresAt을 검사한다.
OpenAPI에 만료 경계와 FE가 담당하는 자동 확인·참여 확인·오류 이동 계약을 추가했다.
기존 ADR399는 Proposed로 유지하며 새 제품 결정을 추가하지 않는다.

## 요구사항과 증거

| 요구사항 | 직접 검증 | 상태 |
| --- | --- | --- |
| 코드·링크 공통 만료 | 고정 Clock과 실제 DB에서 만료 1µs 전·정각·1µs 후 HTTP 200/404 | 충족 |
| 복수 초대와 Workspace 격리 | 두 Workspace의 세 초대를 반복 조회하고 대상·최소 응답 확인 | 충족 |
| preview 무변경 | 초대·멤버십·Workspace 전체 JSON snapshot 비교 | 충족 |
| 과거 무효화·삭제 거부 | 다른 유효 초대가 있어도 공통 404, 원래 행 보존 | 충족 |
| 발급자 탈퇴 | 실제 Service 발급 뒤 발급자의 참여 이력을 탈퇴로 바꾸고 두 수단 200 확인 | 충족 |
| 숫자 포함 코드 호환 | 소문자·주변 공백 정규화, 만료 직전 성공·정각 실패 | 충족 |
| 링크 exact match | 대소문자 변경·주변 공백·미존재 링크와 형식 오류 코드의 공통 404 | 충족 |
| 요청 제한 | 성공·실패 혼합 30회 뒤 429/Retry-After=60, 링크는 같은 주소에서도 성공 | 충족 |
| 보안·문서 | no-store, 원문 로그·응답 미노출, 공개 OpenAPI와 두 필드 응답 검증 | 충족 |
| FE 자동 확인·입력 전환 | 아래 코드 확인만 수행, FE 인수 테스트 미실행 | 별도 검증 필요 |

직접 테스트는 `WorkspaceInvitationPreviewContractAcceptanceTest` 22건이다.
기존 `WorkspaceInvitationAcceptanceTest`의 코드 미존재·context path·CORS
미리보기 회귀도 전체 suite에 포함한다. DB 전제는 실제 hash·24시간 CHECK를
사용하며 발급자 보존 검증에는 실제 Service 발급 경로를 사용한다.

## FE 확인 결과와 남은 경계

- `WorkspaceCodeCard/model/useWorkspaceCode.ts`는 6자 완성 시 query를 켜고 성공 뒤
  참여 확인 화면으로 이동한다. 대문자 변환·길이 제한은 있으나 A-Z 외 문자 제거는 없다.
- `useInvitationPreviewQuery`는 credential별 query key를 사용한다. 입력 변경 시 오래된
  응답 무시와 같은 완성값 중복 억제의 최종 계약은 FE 인수 증거가 필요하다.
- `WorkspaceInviteLinkGate`와 `WorkspaceJoinErrorNotice`에는 링크 미리보기·오류 후
  코드 입력으로 이동하는 경로가 있다. 이 PR에서 FE를 수정하거나 실행 검증하지 않았다.
- FE 소비자 정리는 별도 범위다. #402 전체의 FE 완료나
  운영 배포 완료를 의미하지 않는다. 이슈 자동 종료 키워드를 사용하지 않는다.

## 컨벤션 적용

STACK/LIB는 기존 Java·Spring·Testcontainers를 사용한다. LAYER/MODEL은 기존 실행
계층과 DTO를 유지하고 API는 공개 응답·오류·FE 책임을 명시한다. DB는 테스트 fixture와
snapshot만 사용하며 migration은 없다. JAVA/TEST는 필드·인자 줄바꿈, 단일 when과
고정 시계·실제 PostgreSQL을 적용한다. AI/DELIVERY는 실제 diff와 테스트로 판단한다.
원문 컨벤션 hash gate를 통과했고 새 의존성·설정·제품 정책은 추가하지 않았다.

## 최종 검증

- 2026-10-04 #444 병합 뒤 develop을 동기화하고
  `spotlessCheck test integrationTest acceptanceTest bootJar --no-parallel --no-daemon` 검증:
  단위 291·통합 119·인수 198건, 총 608건 성공. 실패·오류·건너뜀 0건이다.
- 변경 범위 컨벤션 감사와 PR Governance 검증도 통과했다.
  전체 wrapper는 기존 컨벤션 위반 43건에서 중단했으며 전체 Gradle 검증은 별도로 통과했다.
- FE 테스트와 운영 배포는 실행하지 않았다.
