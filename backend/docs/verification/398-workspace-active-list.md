# #398 활성 워크스페이스 목록 조회 검증

## 범위와 기반

- 기준: 2026-10-03 Issue #398과 생성 제한 폐기·참여 이력 정책.
- 기반: #411의 `be/feature/#397`, `93d02290`. 기본 브랜치는 `develop`이지만
  선행 PR이 아직 열려 있어 #398은 #397을 base로 검토한다.
- 활성 참여·비삭제 필터와 정렬은 기반에 이미 있다. 이번 변경은 HTTP/PostgreSQL
  회귀 검증과 OpenAPI 설명을 보강하며 조회 로직·DDL·응답 필드는 바꾸지 않는다.
- #398의 상위·하위 이슈는 없다. 이번 PR은 서버 계약을 유지하며 화면 이동 정책과의
  경계를 문서에 명시한다. 신규 ADR은 포함하지 않는다.

## 요구사항과 근거

| 요구사항 | 검증 근거 |
| --- | --- |
| 본인 활성 OWNER/MEMBER, 비삭제 Workspace만 노출 | 기존 Repository 필터 테스트와 HTTP 목록·탈퇴/삭제 4개 조합 |
| 재가입은 새 joinedAt 순서로 한 번만 노출 | 과거 OWNER 행과 신규 MEMBER 행을 둔 HTTP 테스트, 다른 Workspace와 순서 비교 |
| joinedAt DESC, workspaceId DESC | 기존 동일 시각 OWNER/MEMBER 목록 테스트 유지 |
| lastViewed는 null 또는 목록 안의 ID | 기존 유효 포인터 테스트 및 탈퇴/삭제 × 다른 활성 목록 유무 4개 조합 |
| 서버 GET이 마지막 조회 ID를 대체하거나 DB 상태를 복구하지 않음 | 조회 전후 Workspace·참여 테이블 전체 행의 모든 컬럼 JSON snapshot 비교 |
| 기존 wrapper, id/name만 제공 | 실제 HTTP의 최상위·항목 키 개수 및 OpenAPI schema 검사 |
| createdWorkspaceCount 미추가 | 실제 JSON과 OpenAPI에 해당 필드 없음 검사 |
| 빈 목록200·미인증401 | 기존 인수 테스트 유지 |

조회 테스트의 SQL fixture는 이력·삭제 상태를 직접 준비한다. 실제 탈퇴/재가입 API의
전이 검증은 기반 #411의 테스트가 맡는다. 이 테스트를 실제 OWNER 승계 API 구현이나
녹음 폐기 검증으로 해석하지 않는다.

탈퇴 fixture는 V15 CHECK에 맞춰 left_at과 last_viewed=false를 함께 설정한다.
삭제 Workspace에는 DB가 허용하는 last_viewed=true를 남겨, GET이 응답에서만 포인터를
제외하고 저장 상태는 수정하지 않는 경계를 검증한다.

## FE 소비자 확인

- `frontend/src/shared/api/dto/workspace.ts`는 기존 wrapper와 nullable lastViewed를 소비한다.
- `frontend/src/shared/api/fetch/api/v1/workspaces/index.ts`는 목록 응답을 그대로 DTO로 변환한다.
- FE 전체에서 createdWorkspaceCount·생성 최대 3개 제한을 검색했으며 해당 잔재는 없었다.
  기반과 최신 origin/develop의 FE diff도 없었다.
- `frontend/src/shared/utils/getEntryWorkspaceId/index.ts`는 null/stale ID일 때 첫 항목으로
  이동한다. 이는 FE 라우팅 정책이며 서버가 DB의 마지막 선택을 갱신한다는 뜻은 아니다.
  이번 PR은 해당 정책을 바꾸지 않고 FE 테스트를 실행한 것으로 보고하지 않는다.

## 화면 이동 정책과 서버 조회의 경계

2026-10-03 사용자는 로그인·첫 진입과 삭제·탈퇴 후의 이동을 다음 규칙으로 통일하는
안을 적용하도록 요청했다. 서버 GET의 null 응답과 DB 무변경 계약은 유지한다.

- 마지막 조회 대상이 유효하면 해당 Workspace 홈으로 이동한다.
- 마지막 대상이 없거나 접근할 수 없으면 현재 목록 첫 항목으로 이동한다.
  목록은 joinedAt DESC, workspaceId DESC이므로 가장 최근 참여한 Workspace다.
  최근 방문 이력을 찾아 대체하는 동작은 아니다.
- 목록이 비어 있으면 Workspace 생성·참여 선택 화면으로 이동한다.
- 로그인·첫 진입에는 별도 오류 안내 없이 이동한다. 삭제된 Workspace에 접근한
  경우에는 접근 불가 오류 토스트를 표시하고, 본인 탈퇴·삭제는 정상 완료 후 이동한다.
- FE가 실제 Workspace 화면 진입에 성공한 뒤 별도 PUT으로 마지막 조회 값을 저장한다.
  GET이 반환하는 lastViewedWorkspaceId를 첫 항목 ID로 바꾸거나 DB에 저장하지 않는다.

기존 BE 위키 결정 `마지막 조회 워크스페이스 동기화와 진입 규칙`의
“자동 대체 금지·소속이 없으면 빈 메인”을 이번 사용자 결정으로 대체한다.
V2 WS-R14의 남은 Workspace 홈·없으면 생성/참여 이동을 첫 진입에도 적용하고
대체 대상의 선택 순서를 명시한 것이다. 팀 Notion 원문은 이번 PR에서 수정하지 않았다.

현재 FE 첫 진입 코드와의 일치는 소스로 확인했다. 삭제·탈퇴 직후 이동, 오류 토스트,
진입 성공 후 PUT까지의 FE 인수 검증은 별도 FE 작업이며 #437의 완료 범위가 아니다.

## 검증 명령

backend 디렉터리에서 실행한다.

```bash
./gradlew spotlessApply acceptanceTest --tests '*WorkspaceQueryAcceptanceTest' --tests '*ApiDocumentationAcceptanceTest' --no-parallel
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar --no-parallel
python3 -m unittest discover ../.github/scripts -p 'test_*.py' -v
```

- 집중 인수 22건, Governance 단위 8건 통과.
- 전체 단위 208건·통합 83건·인수 133건, 합계 424건 통과. 실패·오류·건너뜀 0건.
  Spotless 검사와 bootJar 빌드도 통과했다.
- 프로젝트 로컬 backend 스킬의 원문 hash gate·변경 범위 감사·기본 검증 wrapper 통과.
- 전체 감사와 full wrapper는 기존 5건에서 실패한다: GithubOAuth2User/Service의
  JAVA-FIELD-ONE 2건, OAuth2AuthenticationSuccessHandler 및 그 테스트의
  LAYER-PRESENTATION-DEPENDENCY 2건, 기존 V1 migration의 DB-FK-DELETE 1건.
  모두 이번 diff 밖이다. 전체 Gradle task는 감사 wrapper와 별도로 실행해 통과했다.

## 컨벤션 적용 검토

| 규칙군 | 적용 결과 |
| --- | --- |
| STACK | 기존 Java25·Spring Boot·PostgreSQL18 Testcontainers 사용 |
| LIB | 의존성 추가 없음 |
| LAYER | 기존 readOnly Service·Repository 경계 유지 |
| MODEL | Entity·상태 전이·DTO 필드 변경 없음 |
| API | nullable wrapper 유지, OpenAPI 설명·응답 키 검증 보강 |
| DB | migration 변경 없음, PostgreSQL 전체 Flyway·기존 CHECK를 적용한 fixture |
| JAVA | 기존 formatter·120자·인자 줄바꿈 적용 |
| TEST | Given/When/Then, 실제 HTTP와 DB 결과·이력 보존 검증 |
| AI | 독립 diff 검토, fixture CHECK 실패 교정 후 재실행; 설명 문구는 소스 대조로 확인 |
| DELIVERY | #398 한 의도, 선행 #411 스택 base, 테스트·문서 포함 1000줄 이내 |

사용자 결정과 Issue를 제품 기준으로, 로컬 Notion 백엔드 00~08 및 승인된 API 컨벤션을
작성 기준으로 적용했다. 팀 Notion 실시간 동기화는 검증하지 않았다. 새로운 Spring
설계 기본값·예외·formatter 충돌은 도입하지 않았다.
