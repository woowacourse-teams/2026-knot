# 개선안 4 결과: 스냅샷 조회 테스트의 컨텍스트 공유

- 상태: 채택. 전후 측정·격리·전체 회귀 검증 완료. 전체 CI 시간 개선은 미검증.
- 실행일: 2026-10-16.
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715).
- 브랜치: `be/refactor/#715`.
- 기준 HEAD: `7fd075436df67921edc2fd0666eb2534fb302507`.
- 선행 문서: [개선안 4 실험 계획](../implement-plan/715-snapshot-context-sharing.md).

## 1. 실험 배경과 가설

개선안 3에서 서로 다른 컨텍스트 설정 40개를 확인했다. 캐시를 늘려도 이 설정들의 최초 초기화는 남으므로, 이번에는 비용이 크고 함께 공유할 수 있는 설정 그룹을 찾았다.

선정한 대상은 문서·검색 스냅샷 조회 테스트 5개다. 공통 SpringBootTest·dev 프로필·테스트 속성·Testcontainers 설정은 같았지만 각 클래스가 서로 다른 조회 어댑터 하나만 spy로 선언해 컨텍스트가 분리되어 있었다.

> 다섯 테스트 클래스에 같은 5종 spy 구성을 선언하면 실제 PostgreSQL의 스냅샷 일관성 검증을 유지하면서 컨텍스트와 DB 초기화를 줄일 수 있다.

| 클래스 | 기존 테스트 수 | 변경 전 spy 대상 |
| --- | ---: | --- |
| DocumentListSnapshotIntegrationTest | 2 | DocumentListQueryAdapter |
| DocumentConfirmationSnapshotIntegrationTest | 2 | DocumentConfirmationQueryAdapter |
| DocumentTranscriptSnapshotIntegrationTest | 1 | TranscriptSegmentRepositoryAdapter |
| SearchConversationListSnapshotIntegrationTest | 1 | SearchConversationListQueryAdapter |
| SearchMessageListSnapshotIntegrationTest | 2 | SearchMessageListQueryAdapter |
| 합계 | **8** | 서로 다른 5개 설정 |

기존 리포트의 로딩 비용은 대상을 고르는 근거로만 사용했다. 채택 여부를 판단하는 전후 측정은 같은 날 같은 관측 조건으로 새로 실행했다.

## 2. 적용한 변경

다섯 클래스에 다음 선언을 동일하게 추가하고, 기존 spy 필드는 `@Autowired`로 주입받도록 바꿨다.

```java
@MockitoSpyBean(types = {
    DocumentListQueryAdapter.class,
    DocumentConfirmationQueryAdapter.class,
    TranscriptSegmentRepositoryAdapter.class,
    SearchConversationListQueryAdapter.class,
    SearchMessageListQueryAdapter.class
})
```

실제 변경은 테스트 파일 5개의 import·애노테이션에 한정된다. production 코드·DB 스키마·Gradle 설정은 변경하지 않았다. 컴파일 산출물을 대조했을 때도 변경 전후에 다른 파일은 대상 테스트 클래스 5개의 class 파일뿐이었다.

다음 검증 조건은 유지했다.

- 기존 8개 테스트 본문·assertion·fixture 및 TRUNCATE.
- 실제 PostgreSQL·Flyway, 이미지 `pgvector/pgvector:pg18`.
- 서비스의 REPEATABLE_READ, 별도 스레드·별도 트랜잭션의 실제 commit.
- 조회 중 변경을 끼워 넣는 기존 spy callback과 대기 상한 10초.
- executor 종료와 Spring 기본 Mockito AFTER reset.
- 컨텍스트 캐시 최대 8개, 테스트 JVM 최대 힙 1GB.

새 테스트 트랜잭션이나 전역 reset을 추가하지 않았다. 이번 테스트는 자동 rollback 횟수로 격리를 판정하는 구조가 아니므로 rollback을 근거로 사용하지 않는다.

## 3. 실행 환경과 시간 기준

| 항목 | 조건 |
| --- | --- |
| 호스트 | Apple M5 Pro, 메모리 24GiB, macOS 26.6.2, arm64 |
| Java / Gradle | OpenJDK 25.0.3 / Wrapper 9.5.1 |
| Docker | 29.2.1, VM CPU 15개·메모리 약 7.65GiB |
| 주요 런타임 | Spring Boot 4.1.0, Spring Test 7.0.8, JUnit 6.0.3, Mockito 5.23.0 |
| 재실행 | `integrationTest --rerun --no-build-cache` |
| 컴파일·리소스 처리 | 전후 모두 계측 밖에서 준비, 측정 시 4개 작업 모두 UP-TO-DATE |
| 유지한 캐시 | Gradle daemon, 의존성, Docker 이미지 캐시 |
| 관측 | 같은 probe·캐시 TRACE·spy/fixture/스레드 관측 listener를 양쪽 적용 |
| 시간 범위 | Gradle 명령 시작부터 프로세스 종료까지 |

변경 전후를 1회씩 번갈아 3회 측정했다. 각 실행은 새 테스트 JVM에서 수행했으며, JVM 안에서 컨텍스트를 공유하는지를 확인했다. 프로세스가 다른 실행 사이의 숫자 ID 일치는 공유 근거로 사용하지 않는다.

전후 소스·build 디렉터리·Gradle 작업 이력·실행별 binary/XML/HTML 경로를 분리했다. 최종 측정은 컴파일된 클래스와 리소스를 별도 runtime 경로에 고정하고 실제 테스트 classpath가 그 경로를 사용하는지 확인했다. 매 실행 전후 SHA256을 대조했고, 변경 전후의 의존성 JAR 목록도 같았다.

이 수치는 로컬 계측 결과다. 다른 작업과 호스트·Docker를 공유했고 기존 QA 컨테이너나 다른 사용자의 빌드를 종료하지 않았다. 따라서 전용 벤치마크 환경의 정밀 추정이나 GitHub Actions 개선율로 해석하지 않는다.

## 4. 대상 5개 클래스의 측정 결과

### 반복 초기화 횟수

전후 각 3회에서 아래 결과가 모두 같았다.

| 지표 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| 실제 캐시 키 수 | 5 | **1** |
| 실제 생성·등록된 컨텍스트 수 | 5 | **1** |
| Spring 시작 완료 | 5회 | **1회** |
| PostgreSQL 시작 완료 | 5회 | **1회** |
| Flyway 적용 완료 | 5회 | **1회** |
| 기존 테스트 성공 | 8개 | **8개** |
| 실패·오류·스킵 | 0개 | **0개** |

변경 후 다섯 클래스가 같은 실제 캐시 키와 컨텍스트 ID를 사용했다. 단순히 Started 로그가 줄어든 것과 실제 공유 여부를 구분해 확인했다.

### 실행 시간

| 실행 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| 1회 | 26.919초 | 13.815초 |
| 2회 | 25.270초 | 11.799초 |
| 3회 | 22.244초 | 12.454초 |
| **중앙값** | **25.270초** | **12.454초** |
| 범위 | 22.244–26.919초 | 11.799–13.815초 |

대상 8개 테스트를 함께 실행한 시간의 중앙값은 **12.816초, 약 50.7% 감소**했다.

확실히 확인한 구조적 효과는 컨텍스트·PostgreSQL·Flyway 초기화를 각각 4회 줄인 것이다. Spring 초기화 안에 DB 준비가 포함되므로 각 로그 시간을 더해 별도의 총 절감 시간으로 계산하지 않았다. 대상 실험의 감소율을 전체 통합 테스트나 전체 CI 감소율로 사용하지 않는다.

## 5. 기존 검증과 격리 확인

### 단독 실행과 실제 클래스 순서 변경

| 검증 | 테스트 수 | 결과 |
| --- | ---: | --- |
| DocumentList 단독 | 2 | 성공 |
| DocumentConfirmation 단독 | 2 | 성공 |
| DocumentTranscript 단독 | 1 | 성공 |
| SearchConversationList 단독 | 1 | 성공 |
| SearchMessageList 단독 | 2 | 성공 |
| seed 1 결합 | 8 | 성공, 컨텍스트·PG·Flyway 각 1회 |
| seed 4096 결합 | 8 | 성공, 컨텍스트·PG·Flyway 각 1회 |
| seed 27 결합 | 8 | 성공, 컨텍스트·PG·Flyway 각 1회 |

실제 진입 로그로 확인한 순서는 다음과 같다. 아래 이름은 표의 클래스명에서 SnapshotIntegrationTest를 생략한 것이다.

- seed 1: DocumentTranscript → SearchConversationList → DocumentList → SearchMessageList → DocumentConfirmation.
- seed 4096: DocumentList → DocumentTranscript → DocumentConfirmation → SearchConversationList → SearchMessageList.
- seed 27: SearchConversationList → SearchMessageList → DocumentTranscript → DocumentList → DocumentConfirmation.

seed 값만 바꿨다고 다른 순서로 가정하지 않았으며 실제 세 순서가 다름을 대조했다. 각 실행의 실패·오류·스킵은 모두 0이다.

### spy reset·데이터 준비·executor

각 테스트에서 기존 BeforeEach와 stub 설정 전에 spy 상태를 관측했고, 테스트 후에는 Spring의 기본 Mockito reset이 끝난 뒤 다시 확인했다. 관측 listener가 직접 reset하거나 fixture를 정리하지는 않는다.

| 확인 항목 | 관측 결과 |
| --- | --- |
| 변경 전 결합 실행의 spy 검사 | 8개 테스트 × 기존 spy 1개 = 시작 전 8건·reset 후 8건, 남은 stub 0 |
| 변경 후 결합 실행의 spy 검사 | 8개 테스트 × spy 5개 = 시작 전 40건·reset 후 40건, 남은 stub 0 |
| fixture 준비 후 DB | 테스트별 8개 테이블 행 수가 변경 전과 일치 |
| 단독·세 순서의 DB 준비 | 같은 테스트의 테이블별 행 수가 기준과 일치 |
| 별도 executor | 테스트별 종료 시 새로 생긴 pool 스레드의 잔존 0 |

확인한 테이블은 members, workspaces, documents, document_confirmations, transcripts, transcript_segments, search_conversations, search_messages다. 테이블 행 수만으로 모든 데이터 상태를 증명하지는 않는다. 기존 assertion이 실제 commit 후 최초 응답의 스냅샷과 해당 사례의 다음 조회 결과까지 검증하는 것과 함께 판단했다.

이 결과는 **이번 5개 클래스와 확인한 순서에서 기존 검증과 격리가 유지됐다는 근거**다. 전체 순열이나 향후 추가될 모든 공유 조합을 증명한 것은 아니다.

## 6. 전체 통합 테스트·회귀 결과

### 전체 통합 테스트 전후 각 3회

전체 80개 클래스·649개 테스트를 전후 각 3회 교차 실행했다. **6회 모두 실패·오류·스킵 없이 성공**했다. 전후의 테스트 식별자 649개와 실제 클래스 진입 순서도 같았다.

| 전체 초기화 지표 | 변경 전 3회 공통 | 변경 후 3회 공통 |
| --- | ---: | ---: |
| 서로 다른 실제 캐시 키 | 40 | **36** |
| 생성·등록된 컨텍스트 | 46 | **43** |
| Spring 시작 완료 | 46회 | **43회** |
| PostgreSQL 시작 완료 | 54회 | **51회** |
| Flyway 적용 완료 | 124회 | **121회** |
| 용량 퇴출 뒤 같은 키 재생성 | 3회 | **4회** |
| 명시적 컨텍스트 종료 뒤 재생성 | 3회 | **3회** |

명시적 종료 호출 총횟수와 그 뒤 실제 재생성된 횟수는 다른 지표다. 위 마지막 행은 전체 생성 수에서 최초 키 수와 용량 재생성을 뺀 재생성 3건을 이벤트의 제거 사유와 대조한 값이다.

전체 실행에서는 대상 다섯 클래스가 한 설정으로 묶여도 컨텍스트가 한 번만 생성되지는 않았다. 문서 3개 클래스가 공유한 뒤 다른 테스트들로 인해 캐시에서 퇴출됐고, 검색 2개 클래스에서 같은 키가 다시 생성됐다. 첫 변경 후 실행의 실제 키는 5였으며 문서 구간 ID 1495180250, 검색 구간 ID 236715197이었다. 이 ID는 해당 실행의 JVM 안에서만 비교한 값이다.

따라서 설정 수는 4개 줄지만 전체 컨텍스트 생성은 3회 줄어드는 구조다. 캐시 용량에 따른 재생성도 기존 3회에서 4회로 늘었다. 집중 실행의 5→1과 전체 실행의 감소량을 같은 수치로 표현하지 않는다.

| 실행 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| 1회 | 183.091초 | 233.493초 |
| 2회 | 194.977초 | 171.657초 |
| 3회 | 173.608초 | 156.392초 |
| **중앙값** | **183.091초** | **171.657초** |
| 평균 | 183.892초 | 187.181초 |
| 범위 | 173.608–194.977초 | 156.392–233.493초 |

계획한 지표인 중앙값은 **11.434초, 6.24% 감소**했다. 그러나 평균은 약 3.289초 증가했고 범위도 겹친다. 따라서 **전체 통합 실행 시간이 안정적으로 개선됐다고 결론 내리지 않는다.**

첫 변경 후 실행을 임의로 제외하지 않았다. 이 실행에서는 변경하지 않은 RequestIdNginxIntegrationTest가 3.099→18.478초, MemberServiceIntegrationTest가 1.862→14.620초, TranscriptionMigrationHistoryIntegrationTest가 4.065→12.959초였다. 느려진 구간이 대상 밖에도 있다는 관측이며, 그 원인이 호스트 부하인지 이번 변경의 간접 영향인지까지 확정한 것은 아니다.

전체 반복 실행에서도 대상 8개의 fixture 행 수와 spy·executor 관측은 집중 실행 기준과 일치했다. 최종 집계에서는 inventory·순서·classpath·초기화·격리 등을 106개 조건으로 대조해 모두 통과했다. 이는 기존 테스트 실행의 산출물을 검사한 것이며 별도의 제품 테스트 106개를 추가했다는 뜻이 아니다.

### 관측 도구를 추가하지 않은 회귀

최종 변경 소스를 새로 컴파일하고 `spotlessCheck test integrationTest acceptanceTest bootJar --rerun-tasks --no-build-cache`를 실행했다. 빌드·리포트 출력 경로만 분리했으며 계측 agent·관측 listener·캐시 TRACE·랜덤 순서는 추가하지 않았다.

| 검사 | 결과 |
| --- | --- |
| Spotless | 성공 |
| 전체 단위 테스트 | **1,425개 성공** |
| 전체 통합 테스트 | **649개 성공** |
| 전체 인수 테스트 | **561개 성공** |
| bootJar | 성공, 실행 JAR 생성 |

합계 **2,635개 테스트가 실패·오류·스킵 없이 성공**했다. 종료 코드 0과 새로 생성된 XML의 갱신 시각을 확인했다. 이 회귀 실행의 283.113초는 새 컴파일과 여러 검사 단계를 포함하므로 앞의 집중·전체 통합 성능 표에 섞지 않는다.

개선안 3에서 관측했던 MCP 테스트 실패는 이번 최종 계측 6회와 기본 회귀에서는 재발하지 않았다. 이번 설정 정렬로 그 실패의 근본 원인을 해결했다고 주장하지 않는다.

## 7. 자원 비용과 한계

전체 통합 전후 각 3회에서 관측한 표본의 최댓값은 다음과 같다.

| 지표 | 변경 전 표본 최대 | 변경 후 표본 최대 |
| --- | ---: | ---: |
| 실행에 속한 PostgreSQL 동시 개수 | 9개 | 9개 |
| 해당 PostgreSQL 메모리 합계 | 505.3MiB | 494.1MiB |
| 테스트 JVM RSS | 1,253.5MiB | 1,129.2MiB |
| 테스트 JVM 사용 heap | 952.0MiB | 921.0MiB |

heap은 약 2초 간격, RSS·Docker는 약 10초에 조회 시간을 더한 간격으로 수집했다. 종료된 컨테이너를 놓칠 수 있고 GC 시점도 다르므로 **순간 최대치나 메모리 감소율로 해석하지 않는다.** Docker 메모리는 실행 로그의 컨테이너 ID로 해당 실행의 PostgreSQL만 골랐으며 기존 QA 컨테이너는 합산하지 않았다.

대상 8개만 실행한 변경 후 측정은 짧아서 PostgreSQL이 자원 표본에서 누락됐다. 그 값 0을 DB나 DB 메모리가 없다는 근거로 사용하지 않았다. 캐시·힙 상한을 늘리지 않고 전체 반복 실행에 성공했으며, 메모리 최적화가 입증됐다고 주장하지 않는다.

## 8. 측정 준비 중 제외한 실행

최종 시간 비교에는 `verified-focus-*` 및 `verified-full-*` 실행만 사용한다. 이전 실행은 삭제하지 않고 원본을 보존했다.

- 처음에는 build 디렉터리만 분리해 전후 교차 시 테스트 컴파일이 다시 실행됐다. 해당 실행 전체를 시간 비교에서 제외하고 Gradle 프로젝트 캐시도 분리했다.
- 다음 후보 실행에서는 SearchFixtures·SearchMessageFixtures를 찾지 못해 초기화 2개가 실패했다. 당시 변경 후 테스트 class 출력이 사라졌고 같은 폴더의 다른 Gradle 빌드가 겹친 기록이 있었으나 직접 원인은 확정하지 못했다. 실패 XML과 로그를 보존하고 그 프로토콜의 전후 결과를 시간 비교에서 제외했다.
- 고정 runtime을 도입하는 과정에서도 실제 classpath가 원래 build 경로로 덮어써지는 것을 발견했다. 해당 성공 실행과 중단 실행도 제외하고 설정 시점을 수정했다. 최종 프로토콜은 실제 classpath와 runtime 해시까지 검증했다.

성공할 때까지 같은 실패를 자동 재시도하거나 테스트·timeout·assertion을 바꾸지 않았다. 계측 조건을 수정한 경우 전후 모두 같은 새 조건에서 다시 측정했다. 준비 과정의 실패를 테스트 설계의 회귀나 무관한 환경 문제로 단정하지 않는다.

## 9. 근거 파일

원시 산출물은 `backend/output/ci-715/snapshot-context-20261016/`에 보존했다.

| 파일·디렉터리 | 용도 |
| --- | --- |
| original-reports/ | 실험 전 기존 리포트 보존 |
| snapshot-before.json / snapshot-after.json | HEAD·소스·리소스 해시 |
| sources-before/ / sources-after/ | 전후 테스트 소스 보존 |
| runtime-before/ / runtime-after/ | 최종 계측에 사용한 고정 클래스·리소스 |
| verified-focus-before-1..3 / verified-focus-after-1..3 | 대상 전후 각 3회 |
| solo-1..5 / order-1,4096,27 | 단독·순서 검증 |
| verified-full-before-1..3 / verified-full-after-1..3 | 전체 통합 전후 각 3회 |
| regression/ | 관측 도구를 추가하지 않은 전체 회귀 |
| analysis.json / audit.json | 집계와 inventory·순서·격리 대조 |
| journal.md | 프로토콜 변경과 제외 사유 |

각 실행의 console.log, summary.json, XML·HTML, events.tsv, runtime-classpath.txt, resources.jsonl을 함께 보존한다. 관측용 probe·listener·init script는 output에만 두며 제품 코드나 CI 설정에 추가하지 않는다.

## 10. 판정과 다음 단계

**이번 변경을 채택한다.** 같은 설정을 사용하도록 정렬한 뒤 기존 스냅샷 일관성 검증과 격리를 유지했고, 집중 실행의 반복 초기화를 4회 줄였다. 전체 실행에서도 설정은 40→36개, 실제 컨텍스트·DB·Flyway 초기화는 각각 3회 줄었다. 대상 8개 실행 시간 감소는 전후 3회 모두 관측됐다.

다만 이 결과를 “전체 CI 50.7% 개선”이나 “전체 통합 테스트가 안정적으로 6.24% 빨라졌다”로 표현하지 않는다. 전체 통합 중앙값은 감소했지만 평균은 증가했고 소표본·공유 호스트의 변동이 남았다. 구조적 비용 감소와 전체 시간 개선의 확실성을 구분한다.

후속 검증은 같은 GitHub Actions 실행 조건에서 전체 단계 시간과 초기화 횟수를 수집하는 것이다. 추가 로컬 개선 후보는 남은 36개 설정을 다시 비용·설정 호환성으로 분류해 선정한다. 36개가 모두 합칠 수 있는 설정이라는 뜻은 아니다. 마이그레이션 검증, 필요한 상태 격리·DirtiesContext, 실제 동시 commit은 유지할 비용으로 남긴다.

이번 요청 범위의 구현·실험·별도 결과 문서 작성을 완료했다. 원격 Issue 수정·commit·push는 수행하지 않았다.

후속 전달: 2026-10-16 사용자의 추가 커밋·푸시 요청으로 검증한 테스트 변경을 `16ac9dfe0`에 기록했다. 실험 당시 원시 수치와 제외 기준은 유지하며, 원격 Issue는 변경하지 않는다.

## 11. 프로젝트 도구 종료 기록

구현·리뷰 보고서를 작성한 뒤 `npx ph workflow finish implement`를 실행했으며 `source-read-runtime-unavailable`로 종료 코드 1을 반환했다. 도구 안내에 따라 기존 이력·환경을 초기화하거나 다른 workflow를 다시 실행하지 않았다. Persona 종료 인증을 받았다고 주장하지 않는다.

LSP Java timeout·Python 서버 미설치와 근거 읽기 런타임 unavailable은 실제 Gradle 컴파일·2,635개 테스트·Spotless·bootJar 성공과 구분한다. 이번 실험의 관측과 회귀 검증은 완료됐으며, 프로젝트 도구의 종료 인증만 미완료다.
