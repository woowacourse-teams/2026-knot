# 최종 B 대상 테스트와 격리 검증 결과

> 후속 [일반 전체 회귀](715-ci-final-regression.md) 1회 완료: Spotless·단위1543·통합676·인수567·bootJar 통과, 총2786건 성공. 아래는 앞선 대상·격리 실행 당시 기록이며 Actions는 계속 미실행이다.

## 1. 결과

**최종 B의 인증·문서 조회·snapshot 대상 테스트와 스케줄러 검증을 완료했다. 정상 실행 20회에서 171건이 모두 통과했고, 오염 부정 사례 1회는 의도한 초기 상태 assertion에서 실패했다.** 171건은 반복 실행을 포함한 수이며, 서로 다른 기존 테스트는 스케줄러를 포함해 57개다.

전체 단위·전체 통합·인수 테스트와 GitHub Actions는 실행하지 않았다. 이번 결과는 최종 B의 대상 검증과 격리 근거이며 전체 회귀나 CI 성능 결과가 아니다.

| 대상 | 클래스별 단독 실행 | 결합 정순 | 결합 역순 | 결과 |
| --- | --- | ---: | ---: | --- |
| 인증 저장소 2클래스 | 6개 + 4개 | 10개 | 10개 | 모두 통과 |
| 문서 조회 JPA 4클래스 | 10개 + 9개 + 12개 + 7개 | 38개 | 38개 | 모두 통과 |
| snapshot 5클래스 | 2개 + 2개 + 1개 + 1개 + 2개 | 8개 | 8개 | 모두 통과 |
| 스케줄러 정상 | 독립 실행 3회, 매회 1개 | — | — | 3/3회 통과 |
| 스케줄러 오염 부정 사례 | member 1건 주입, 1회 | — | — | 초기 assertion에서 예상한 실패 |

정상 실행의 실패·오류·스킵은 모두 0이다. 예상한 부정 사례의 실패 1건은 정상 실행의 실패를 교체하거나 재시도한 결과가 아니다. 전체 실행은 계획한 21회이며 추가 제품 테스트 재실행은 없었다.

## 2. 실행 기준과 보존 조건

| 항목 | 이번 조건 |
| --- | --- |
| 실행일 | 2026-10-17 |
| 작업 브랜치 | `be/refactor/#715` |
| 고정 제품 기준선 D | `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd` |
| 최종 B tree | `683b6d90249b60d3d60362af507b767971badfc7` |
| B 구성 | D + 스케줄러 수정 S + 리포트 보존 W + 개선안 1·2·4 |
| 보류 | 개선안 3·5 미적용 |
| 호스트 | Apple M5 Pro, 메모리 24GiB, macOS |
| 전원 | 실행 전후 AC Power 확인, 전체 실행에 caffeinate 적용 |
| Java / Gradle | OpenJDK 25.0.3 / Wrapper 9.5.1 |
| 공통 테스트 설정 | 컨텍스트 캐시 최대 8, JVM 최대 힙 1GB |
| DB | 실제 Testcontainers PostgreSQL·Flyway, `pgvector/pgvector:pg18` |
| 재실행 | `integrationTest --rerun --no-build-cache`, 매회 명시적 `--tests` 필터 |

실제 JPA 실행에서 관측한 이미지 ID는 `sha256:2ba9ca5f2e7daa0f0e7723cba1ee9167bab54efd3640516a44ac1a928dd67e7a`다. 로컬 이미지 inspect의 ID와 RepoDigest도 이 값과 일치했다.

기존 `B.tar.gz`의 SHA-256을 확인한 뒤 별도 경로에 추출하고 컴파일했다. 원래 작업 폴더의 build·HTML·XML을 덮어쓰지 않았다. 각 실행의 실제 classpath와 컴파일 산출물 hash를 확인했고, 실행 전후 소스와 산출물이 동일했다. 현재 작업 트리의 backend 소스·빌드 설정·workflow도 고정 B와 일치한다.

`git pull --ff-only`는 현재 브랜치 기준 Already up to date였다. fetch 시 origin/develop은 `f7857fa68e4facad981ccd8b9f1423e00bc9c224`로 이동했으나, 이번 요청은 이미 고정한 B의 검증이므로 새 develop 변경을 혼합하지 않았다. 이후 D를 갱신하면 A/B를 같은 D에서 다시 구성해야 한다.

관련 이슈: [#715](https://github.com/woowacourse-teams/2026-knot/issues/715). 선행 문서: [최종 코드와 A/B 기준선](715-ci-final-baseline.md), [승인 계획](../implement-plan/715-final-ci-baseline.md).

## 3. 인증·문서 조회: 컨텍스트 공유와 롤백

인증과 문서 조회는 JPA 테스트의 기존 트랜잭션·롤백을 유지했다. 관측 listener는 테스트 트랜잭션이 시작되기 전과 롤백이 끝난 뒤 public 테이블의 행 수를 읽었다. 데이터를 지우거나 별도 rollback을 실행하지 않았다.

| 확인 항목 | 인증 | 문서 조회 |
| --- | ---: | ---: |
| 정상 테스트 실행 합계 | 30건 | 114건 |
| 실제 트랜잭션 rollback 로그 | 30건 | 114건 |
| 테스트 전 상태 관측 | 30건 | 114건 |
| rollback 후 상태 관측 | 30건 | 114건 |
| 관측 시 활성 테스트 트랜잭션 | 모두 false | 모두 false |
| 관측 대상 | Flyway 이력 제외 public 테이블 23개 | 동일 |
| 테스트 전·rollback 후 행 수 | 매회 모두 0 | 매회 모두 0 |

각 그룹의 결합 정순·역순 실행에서 **Spring 컨텍스트·PostgreSQL 컨테이너·Flyway 초기화가 각각 1회**였고, 클래스들이 같은 컨텍스트 식별자와 DB를 사용했다. 식별자는 같은 실행의 JVM 안에서만 비교했다.

단독 실행의 테스트 식별자를 합친 목록과 결합 정순·역순의 식별자가 정확히 같았다. 테스트 선택·스킵으로 결과 수를 줄이지 않았다. 기존 저장·조회·제약 실패와 조회 scope·cursor 등의 assertion을 변경하지 않고 통과했다.

## 4. snapshot: fixture·spy reset·executor

snapshot 테스트는 실제 별도 트랜잭션의 commit과 동시 변경을 검증한다. 이 그룹의 격리를 JPA 자동 rollback으로 판정하지 않았다.

| 확인 항목 | 관측 결과 |
| --- | --- |
| 기존 테스트 본문 | 8개 모두 단독·결합 정순·역순 통과 |
| 결합 실행의 공유 | 정순·역순 각각 컨텍스트·PG·Flyway 초기화 1회 |
| 테스트 시작 전 spy 검사 | 총 120건, 모두 spy=true·남은 stub 0 |
| 기본 reset 이후 spy 검사 | 총 120건, 모두 남은 stub 0 |
| fixture 준비 후 행 수 | 테스트별 8개 테이블의 값이 단독·결합·역순에서 동일 |
| executor 종료 검사 | 24건 모두 새 pool 스레드 잔존 0 |

한 테스트에서 5개 spy를 검사했으며, 8개 테스트를 세 방식으로 실행해 시작 전 120건·reset 후 120건이다. 관측 listener가 직접 Mockito reset이나 fixture 정리를 실행하지 않았다.

fixture 관측 대상은 members, workspaces, documents, document_confirmations, transcripts, transcript_segments, search_conversations, search_messages다. 행 수 일치는 상태 격리의 보조 근거이며 데이터 값 전체의 동일성을 증명하지 않는다. 실제 동시 commit과 응답의 snapshot 일관성은 기존 테스트 assertion의 통과와 함께 판단했다.

### 실제 클래스 순서

JUnit ClassOrderer로 클래스명 정순·역순을 지정하고, 실제 suite 진입 로그가 그 순서와 같은지 확인했다. `--tests` 인자 순서를 실행 순서의 증거로 사용하지 않았다.

- 인증 정순: AuthSessionRepositoryTest → ConsumedRefreshTokenRepositoryTest.
- 문서 조회 정순: DocumentConfirmationQuery → DocumentDetailQuery → DocumentGenerationJobListQuery → DocumentListQuery.
- snapshot 정순: DocumentConfirmation → DocumentList → DocumentTranscript → SearchConversationList → SearchMessageList.

문서 조회는 IntegrationTest, snapshot은 SnapshotIntegrationTest 접미사를 생략했다. 각 역순 실행은 위 순서를 정확히 뒤집은 순서였다. 모든 순열이나 서로 다른 세 그룹을 한 JVM에 섞는 조합까지 검증한 것은 아니다.

## 5. 스케줄러 정상 3회

기존 100ms 스케줄링·복구 주기, 완료 한도 10초, polling 50ms, 기존 classifier/generator의 트랜잭션 비활성 assertion을 유지했다. 실행마다 서로 다른 테스트 JVM과 전용 DB 컨테이너를 사용했다.

| 실행 | 기존 테스트 | 결과 | Gradle 명령 전체 시간 |
| --- | ---: | --- | ---: |
| 정상 1회 | 1개 | 통과 | 9.817초 |
| 정상 2회 | 1개 | 통과 | 9.012초 |
| 정상 3회 | 1개 | 통과 | 9.749초 |

매회 테스트 시작 시 8개 테이블이 비어 있었다. 실제 예약 작업은 2개였으며, 완료 후 상태는 세 실행에서 같았다.

| 테이블 | 완료 후 행 수 |
| --- | ---: |
| members / workspaces / recording_sessions / transcripts | 각각 1 |
| document_generation_batches | 1 |
| document_generation_jobs | 3 |
| documents / document_confirmations | 각각 2 |

기존 성공 주제 2건·문서 2건·확인 2건·시도 수 합계 3의 assertion이 통과했다. 세 실행의 PostgreSQL 서버 로그에서 deadlock은 관측되지 않았다.

클래스 종료 후 세 실행 모두 `active=false`, `runningContainer=false`, `executorTerminated=true`, `scheduledTasks=0`을 확인했다. 이 테스트는 전체 rollback으로 격리하는 구조가 아니며, 실제 commit 후 전용 컨텍스트·컨테이너 폐기로 격리했다.

위 시간은 JVM·컨테이너 준비와 관측을 포함한 명령 전체 시간이다. 전후 성능 비교나 실제 스케줄러 처리 시간으로 해석하지 않는다.

## 6. 스케줄러 오염 부정 사례

별도 실행에서 fixture 생성 전에 member 1건을 넣었다.

| 확인 항목 | 실제 결과 |
| --- | --- |
| 오염 전 | 8개 테이블 모두 0건 |
| 주입 | member 1건 |
| 실패 위치 | 기존 assertFreshDatabase()의 members 검증 |
| assertion | `expected: 0L` / 실제 `1L` |
| 테스트 결과 | 1개 실행·실패 1개·오류/스킵 0, Gradle exit 1 |
| 실패 후 데이터 | members 1건 유지, 나머지 7개 테이블 0건 |
| 실패 후 종료 | 컨텍스트·컨테이너 종료, executor 종료, 예약 작업 0개 |

초기 오염을 자동으로 삭제하지 않았고 본래 fixture도 생성되지 않았다. 실패 XML·HTML·console·DB 관측·서버 로그를 그대로 보존했다. 예상한 실패이므로 정상 3회 통과와 분리해 기록한다.

## 7. 보존·기록 대조와 한계

원시 자료는 `backend/output/ci-715/final-isolation/`에 저장했다. 실행별 XML·HTML·console·summary·classpath와 스케줄러의 observation·postgres 로그를 보존했다. `source-hashes.json`, `runtime-hashes.json`, `audit.json`에 소스·산출물 불변과 검증 근거를 기록했다.

최종 기록 대조 **401/401개가 통과**했다. 이는 추가 제품 테스트 401개가 아니라 실제 실행·선택 목록·격리 관측·자원 종료·소스 불변의 산출물 대조 항목이다.

초기 집계는 이전 실행의 Ryuk 정리 컨테이너가 자연 종료되는 것도 기존 자원 손실로 분류해 15개 항목을 실패로 표시했다. 초기 결과를 `audit-initial.json`에 보존했다. 로그의 생성 ID로 이번 실험이 만든 컨테이너 42개를 구분한 뒤, 원래 QA 컨테이너 3개와 실험 외 자원 보존을 다시 대조했다. 사라진 ID는 모두 이번 실험의 Ryuk이었고 기존 QA 3개는 전후 동일했다. **제품 테스트를 다시 실행하거나 실패 결과를 교체하지 않았다.**

이번 DB 컨테이너는 각 실행 종료 후 남지 않았고, 최종 Docker 상태는 원래 QA 3개만 유지했다. 실행기·테스트 JVM·caffeinate 잔존도 없었다. 사용자 변경 4파일은 기존 보존 hash와 동일하며 이전 실험·실패 원본을 수정하지 않았다.

준비 컴파일은 성공했다. 기존 deprecated/unchecked 알림과 관측 도우미의 API 상태 애노테이션 경고 4건은 prepare 로그에 남겼다. Java LSP 시간 초과와 실제 javac 성공은 구분한다. 이번에는 Spotless나 전체 회귀를 실행하지 않았다.

Persona 시작 rail과 implementation/review report-filled는 통과했다. `npx ph workflow finish implement`는 기존 `source-read-runtime-unavailable`로 exit 1이었고, evidence 등록도 unavailable이었다. 공식 종료 인증 미완료와 이번 실제 Gradle·DB 관측의 성공을 구분한다. 환경·이력을 재생성하거나 무관한 전체 테스트를 실행하지 않았다. 결과·진행표·선행 문서의 로컬 링크 24개와 `git diff --check`도 통과했다.

이번 대상 성공은 전체 테스트·실제 CI 성능·간헐 DB timeout 해결의 증거가 아니다.

## 8. 판단과 다음 단계

**고정 B의 대상 검증과 이번 범위의 격리는 통과했다.** 개선안 1·2·4와 스케줄러 수정을 유지한 상태로 다음 최종 회귀 단계로 진행할 수 있다.

남은 실행은 최종 B의 일반 전체 회귀 1회, 이후 같은 D·S·W를 사용하는 실제 Actions A/B 각 3회다. 비교용 커밋·checkout SHA 확정은 원격 실행 전에 필요하다. 이번 작업에서는 commit·push·PR·Actions를 수행하지 않았다.

현재까지 전체 CI 개선율은 미확정이다. 이번 검증을 이유로 연결·시계 진단이나 개선안 5 재채택 실험을 추가하지 않는다.
