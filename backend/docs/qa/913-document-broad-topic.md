# #913 큰 주제 배정 로컬 QA

날짜: 2026-10-22 KST. 작업 브랜치 `be/feature/#913`, 기준 `67254974ef349f8d053c490b766c0e8a305e42ce`.
원본 사용자 체크아웃과 기존 검색 QA Docker는 변경하지 않았다. 아래 검증은 commit·push·PR 전에 수행했으며 운영 배포는 포함하지 않는다.

## API 호환성

- 기존 Controller·Request/Response DTO·frontend에 변경이 없다.
- 기존 목록 최상위 `topics/items/nextCursor`, 카드 10개 필드를 그대로 반환한다. 추가 필드는 없다.
- 목록의 두 topic 필드는 새 문서의 큰 주제 이름, legacy 문서의 원래 topic을 사용한다.
- 표시용 이름이 같은 legacy/새 문서는 한 문자열 그룹으로 집계된다. DB 안건 값은 유지된다.
- 기존 확인 상태·녹음 필터·전체 개수·cursor·401/403을 목록 통합·인수 테스트로 확인했다.

처음에는 기존 응답을 확장하는 방식을 구현했지만 사용자의 API 유지 지시에 따라 추가 DTO·필드를 제거했다.
큰 주제 표시를 기대하는 테스트 두 개가 기존 query에서 실패한 뒤, 집계와 카드 query를 함께 수정해 통과시켰다.

## 자동 검증

| 실행 | 관측 결과 |
| --- | --- |
| `./gradlew spotlessApply check bootJar --console=plain` | 최초 전체 검사 PASS: 총 3,516건 중 성공 3,512·환경 조건 건너뜀 4·실패·오류 0 |
| 순수 규칙 예외 회귀 테스트 | 구현 전 FAILED(완료되지 않고 QUEUED), 기타 완료 처리 후 PASS |
| Category 실행 통합 + V61→V62 migration 통합 | 보완 후 15건 PASS. 공개 경계·복구·동시성·DB 제약·보존·롤백 지표 검증 |
| `./gradlew check --console=plain` | 최종 BUILD SUCCESSFUL (6분 17초). 단위 2,029 + 통합 897 + 인수 591 = 총 3,517건 중 성공 3,513·건너뜀 4·실패·오류 0. Spotless 통과 |
| Python AST·Compose config 검사 | 구문·Compose 설정 검사 PASS |
| `git diff --check` | 공백 오류 없음 |

Gradle test task는 같은 worktree에서 동시에 실행하지 않는다. 초기 검증 중 겹쳐 실행한 두 task의 결과 파일 충돌은
`in-progress-results-generic.bin`의 NoSuchFileException이었다. 이후 순차 실행한 전체 검사는 통과했다.
IDE LSP는 응답 timeout으로 진단을 확보하지 못했으며 컴파일·테스트·Spotless 결과로 검증했다.
건너뛴 4건은 변경하지 않은 기존 실제 외부 연결 테스트다. `KNOT_REAL_AI_OBSERVABILITY=true` 조건의 3건과
`KNOT_RECORDING_REAL_S3=true` 조건의 1건이며, 이번 검증에서 실제 AI·S3 요청은 수행하지 않았다.

Persona 작업·검토 보고서는 worktree의 ignored `.persona/workflow/`에 작성했다.
`npx ph workflow finish implement`는 `plan-not-accepted`로 종료 코드 1을 반환했다. 이 worktree에는 승인된 Persona plan이 없으므로
Persona gate 통과로 보고하지 않는다. Gradle·Docker의 실제 검증 결과와 구분하며, 구현 후 승인 상태를 만들어 통과시키지 않았다.

## Docker 실제 실행

전용 프로젝트 `knot-category-913`, PostgreSQL 18 + 실제 Spring bootJar + Python HTTP LLM fixture를 사용했다.
기존 `knot-search-local` 프로젝트의 앱·DB·volume과 분리했다. 테스트는 저장 전사·등록된 안건 Job에서 시작한다.
실제 STT 공급자 또는 실제 모델에 요청한 테스트가 아니다.

첫 7건의 HTTP 장애 주입과 별도 SIGKILL/재시작 시험은 PASS했다. 마지막 보완 이미지로 빈 전용 DB에서 전체 검증기를
재실행한 뒤, 최종 DB·HTTP·호출 횟수를 읽기 전용으로 재확인해 8건 전체 PASS를 확인했다.

| 안건·장애 | 큰 주제 | 출처 | 배정 호출 | 작성 호출 |
| --- | --- | --- | --- | --- |
| 녹음 안정성 | 녹음·전사 | AI_NEW | 1 | 1 |
| 전사 품질 | 녹음·전사 | AI_REUSE | 1 | 1 |
| 비정상 녹음·잘못된 JSON | 녹음·전사 | RULE_REUSE | 2 | 1 |
| 중단 녹음·HTTP 503 | 녹음·전사 | RULE_REUSE | 1 | 1 |
| 제한 녹음·최초 429 | 녹음·전사 | AI_REUSE | 2 | 1 |
| 지연 녹음·timeout | 녹음·전사 | RULE_REUSE | 2 | 1 |
| 철학 논의·503·규칙 불일치 | 기타 | FALLBACK | 1 | 1 |
| 재시작 녹음·배정 중 SIGKILL | 녹음·전사 | RULE_REUSE | 1 | 1 |

8건 모두 공개 후 사용자·본문 자동 재시도 횟수는 0, 임시 본문은 0개였다. 같은 큰 주제는 한 행으로 수렴했고 확인 대상은 문서마다 저장됐다.
재시작 시점에는 공개 문서가 없고 임시 본문 1개가 남아 있었다. 최초 deadline을 변경하지 않은 채 만료 후 복구했으며 phase_resume_count=3이었다.
기존 목록 API에서 큰 주제별 개수·기존 필드만 반환되는 동작과 비로그인 401·타 Workspace 403을 확인했다.

초기 Docker 기동에서는 HTTP loopback 정책, 연결/요청 timeout 관계, 초대 해시 키 필수 설정을 확인하고 테스트 전용 환경을 보정했다.
운영 검증을 완화하지 않고 loopback namespace 공유·유효한 dummy 설정을 사용했다.

Docker 실행 자료는 사용자 요청으로 `backend/ops/document-category-local/`에 로컬 보관하고 `.gitignore`로 추적에서 제외한다.
이 문서에는 검증 조건과 관측 결과만 유지하며 저장소에서 실행 자료를 내려받아 재현할 수 있다고 주장하지 않는다.
검증 후 `knot-category-913`의 컨테이너 세 개만 종료했으며 전용 DB volume은 보존했다.

## 증명 범위와 남은 운영 확인

- 단위 테스트는 strict 응답·규칙 분기, PostgreSQL은 락·FK·unique·원자성·보존·이행, Docker는 실제 프로세스·HTTP·Security·재시작을 증명한다.
- 실제 모델의 큰 주제 재사용 정확도·동의어 중복률·긴 문서 토큰 한도·지연을 검증한 것은 아니다. 정규화 이름 unique도 의미가 비슷한 서로 다른 문자열의 중복까지 막지는 않는다.
- 기본 feature flag는 OFF다. V62와 새 worker 배포 후 실제 모델 평가를 거쳐 `DOCUMENT_CATEGORY_ENABLED=true`로 활성화한다. FE 변경은 없다.
- 본문 보관 이후 배정 AI/규칙 장애는 대체 경로로 완료한다. 본문 생성 전 AI 장애, 삭제·인가 거절, DB·백엔드가 꺼진 동안의 즉시 성공을 보장하지 않는다.
- 로그·지표는 완료 commit 이후 출처·시도·시간만 기록한다. 원문·본문·프롬프트·인증값을 넣지 않는다. 운영 알림을 배포하거나 실제 실패 Job을 변경하지 않았다.
