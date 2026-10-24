# 스케줄러 테스트 초기화 deadlock 해결 결과

- 상태: 구현 및 로컬 검증 완료. 최초 TRUNCATE 제거를 채택하며, 원래 상대 SQL·정확한 잠금 순서는 미확정으로 남긴다.
- 확인일: 2026-10-16
- 브랜치: `be/refactor/#715`
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 사전 계획: [스케줄러 테스트 deadlock 원인 분석과 해결 계획](../implement-plan/715-scheduler-test-deadlock.md)

## 1. 실험 배경

개선안 5의 전체 통합 테스트 `verified-full-after-3`에서 `DocumentGenerationSchedulerIntegrationTest`가 실패했다. 전체 649개 중 실패는 1개였고, 발생 위치는 문서 생성 전 테스트 첫 작업이었다.

```sql
TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE;
```

당시 로그에는 Process 81이 relation 17412의 `AccessExclusiveLock`을, Process 82가 relation 17186의 `AccessShareLock`을 기다리며 서로를 차단하는 잠금 순환이 남아 있다. 원본 실패 XML과 개선안 5의 측정 결과는 보존했다.

## 2. 원인 판단

이 테스트는 생성·복구 스케줄러를 각각 100ms 주기로 실제 실행한다. 두 후보 조회는 jobs·batches·recording_sessions를 JOIN한다. 테스트의 TRUNCATE는 members/workspaces와 FK로 연결된 테이블까지 정리 대상을 확장한다.

컨텍스트 시작 시점의 스케줄러 조회와 광범위한 TRUNCATE가 경합했을 가능성이 가장 높다. 다만 기존 실패의 상대 SQL과 OID 매핑은 보존되지 않았고, 이번 변경 전 진단 3회에서도 deadlock이 재현되지 않았다. 특정 스케줄러 메서드와 정확한 테이블별 잠금 순서를 확정한 결과로 표현하지 않는다.

진단 실행 중 문서 처리 단계에서 workspace 행의 짧은 잠금 대기가 관측됐으나 테스트는 정상 완료했다. 이 정상 처리 중 대기를 기존 최초 TRUNCATE deadlock의 재현으로 계산하지 않는다.

## 3. 적용한 변경

`DocumentGenerationSchedulerIntegrationTest` 한 파일의 초기화만 변경했다.

변경 전:

```java
jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
        .update();
```

변경 후:

```java
assertFreshDatabase();
```

새 private helper는 아래 8개 테이블을 `SELECT count(*)`로 조회하고 모두 0건인지 확인한다.

`members`, `workspaces`, `recording_sessions`, `transcripts`, `document_generation_batches`, `document_generation_jobs`, `documents`, `document_confirmations`.

전용 컨테이너·단일 테스트·클래스 종료 후 컨텍스트 폐기 조건에서 불필요한 초기 정리를 제거했다. 잔여 데이터가 있으면 삭제하지 않고 fixture 생성 전에 실패한다.

기존 annotation·테스트 본문·assertion은 초기 정리 부분을 제외하면 변경 전 소스와 동일하다. 실제 PostgreSQL·Flyway, 스케줄러 100ms, 완료 한도 10초, polling 50ms, classifier/generator 호출의 트랜잭션 비활성 검증과 클래스 종료 시 `DirtiesContext`를 유지했다. 운영 코드·공통 DB 설정·Gradle 설정·스키마 변경은 없다.

## 4. 검증 방법과 대상 결과

관측 코드는 `backend/output/ci-715/scheduler-deadlock-20261016/`에 격리했다. 스케줄러 테스트에만 진단 listener를 적용해 초기·완료 행 수, 컨텍스트·컨테이너 ID, PostgreSQL 서버 로그 및 종료 상태를 기록했다. 변경 전 진단은 추가 잠금 표본 수집도 사용했다. 일반 회귀에서는 진단 listener를 제외한다.

| 검증 | 결과 |
| --- | --- |
| 변경 전 진단 3회 | 3/3 통과, deadlock 미재현 |
| 수정 후 독립 실행 20회 | 20/20 통과, 실패·오류·스킵 0 |
| 수정 후 초기 데이터 | 20회 모두 8개 테이블 0건 |
| 실행 독립성 | 수정 후 20회 모두 서로 다른 테스트 JVM PID·컨테이너 |
| 종료 상태 | 수정 후 20회 모두 컨텍스트 종료·컨테이너 정지·executor 종료·예약 작업 0개 |
| 잔여 member 1건 주입 | 초기 상태 assertion에서 의도대로 실패 |

독립 실행은 Gradle 명령을 각각 실행해 새 테스트 JVM·컨테이너를 사용했다. 20회 통과는 이 조건의 관측 결과이며 미래의 모든 동시 실행에서 deadlock이 없다는 증명은 아니다.

20회 Gradle 명령 전체 시간의 중앙값은 12.079초, 범위는 8.953~20.062초였다. 변경 전 진단 3회에는 추가 잠금 표본 수집을 사용했고 표본 수도 다르므로 이를 전후 성능 개선율로 비교하지 않는다.

초기 준비 3회는 관측 JAR이 실제 테스트 classpath에서 누락된 상태로 테스트만 통과했다. 이 결과는 준비 실행으로 남겼고 진단 표본에서 제외했다. 이후 실제 classpath·관측 파일 확인을 추가하고 진단 3회를 별도로 수행했다. 준비 과정의 Spotless 줄바꿈 실패도 수정 후 검증과 구분한다.

## 5. 기존 검증과 데이터 격리

정상 독립 실행 20회에서 완료 후 데이터는 모두 다음과 같았다.

| 테이블 | 완료 후 행 수 |
| --- | ---: |
| members | 1 |
| workspaces | 1 |
| recording_sessions | 1 |
| transcripts | 1 |
| document_generation_batches | 1 |
| document_generation_jobs | 3 |
| documents | 2 |
| document_confirmations | 2 |

기존 성공 주제 2건·문서 2건·확인 2건·시도 수 합계 3과 외부 처리 중 트랜잭션 비활성 assertion이 유지됐다. 테스트는 전체 롤백으로 격리하지 않으며, 실제 커밋된 데이터를 다른 스레드가 처리한 뒤 컨텍스트·전용 컨테이너 폐기로 격리한다.

오염 부정 사례에서는 fixture 전에 member 1건을 넣었다. 새 초기 상태 assertion이 `expected: 0L but was: 1L`로 실패했고, member는 1건으로 남았으며 나머지 7개 테이블은 0건이었다. 자동 정리나 fixture 생성으로 오염을 감추지 않았고 실패 후에도 전용 자원은 종료됐다.

## 6. 전체 회귀

현재 개선안 5의 import 정렬 후보를 유지한 채 전체 통합 테스트를 3회 실행했다.

| 실행 | 테스트 수 | 실패·오류·스킵 | Gradle 명령 전체 시간 |
| --- | ---: | --- | ---: |
| 1회 | 649 | 모두 0 | 146.387초 |
| 2회 | 649 | 모두 0 | 182.329초 |
| 3회 | 649 | 모두 0 | 185.476초 |

이 세 실행은 동일 수정본의 회귀 표본이며 성능 전후 비교가 아니다. 명령 시간에는 JVM·컨테이너 준비 및 관측 코드 비용이 포함된다.

이후 진단 listener·잠금 sampling 없이 일반 전체 회귀를 실행했다. 빌드·리포트 경로 분리와 클래스명 출력만 적용하고 기존 테스트 설정을 사용했다. 실제 테스트 classpath에서 관측 JAR이 빠진 것도 확인했다.

| 일반 회귀 항목 | 결과 |
| --- | --- |
| Spotless | 통과 |
| 단위 테스트 | 1,425개 통과 |
| 통합 테스트 | 649개 통과 |
| 인수 테스트 | 561개 통과 |
| 실패·오류·스킵 | 모두 0 |
| JAR 빌드 | 새 JAR 생성 확인, 통과 |
| Gradle 종료 코드 | 0 |
| Gradle 명령 전체 시간 | 264.868초 |

`spotlessCheck test integrationTest acceptanceTest bootJar --rerun-tasks --no-build-cache`를 실제 실행했다. 새 XML·실행 코드 hash·실제 classpath와 JAR 생성 시각을 확인했다. 일반 회귀까지 포함해 수정 후 전체 통합 649개는 총 4회 성공했다.

별도 `audit.py`의 기록 대조 305개 항목도 모두 통과했다. 기존 649개 테스트 목록, 초기·완료 행 수, JVM·컨테이너 독립성, 종료 상태, 원래 실패 XML 보존, 기존 본문·annotation·assertion 동일성, 일반 회귀의 관측 JAR 제외 등을 확인했다. 이는 산출물 일관성 점검이며 별도의 제품 테스트 305개나 영구적인 안정성 인증을 뜻하지 않는다.

## 7. 판단과 한계

이번 변경은 기존 실패 지점인 시작 시점 TRUNCATE를 제거하고, 전용 새 DB라는 전제를 읽기 assertion으로 명시한다. 스케줄러를 끄거나 timeout을 늘리거나 실패를 재시도하지 않는다.

독립 20회·전체 통합 3회·일반 전체 회귀에서 기존 검증과 종료 격리를 확인했으므로 이 테스트 초기화 변경을 채택한다. 의도적으로 주입한 오염 실패는 부정 사례의 예상 결과이며, 정상 회귀 실패를 성공으로 바꾸기 위한 재시도가 아니다. 수정 후 정상 검증에서 대체한 실패 표본은 없다.

원래 실패의 상대 SQL을 특정하지 못했으므로 정확한 잠금 순서를 재현해 근본 원인을 완전히 입증했다고 주장하지 않는다. 독립 실행 시간은 관측·JVM·컨테이너 준비 비용을 포함하며, 변경 전후 표본 수와 잠금 관측 조건도 달라 성능 개선율을 계산하지 않는다.

개선안 5의 JPA import 정렬 후보는 그대로 유지했다. 이번 안정화 검증만으로 개선안 5의 전체 실행 시간 개선이나 최종 채택을 확정하지 않는다. 전체 성능 재비교 시 스케줄러 수정은 양쪽에 동일하게 적용해야 한다.

Persona 구현·검토 보고서 등록은 각각 통과했다. `npx ph workflow finish implement`는 종료 코드1, `source-read-runtime-unavailable`로 종료됐다. 소스 읽기 증거 등록도 `Evidence read unavailable`이었다. 따라서 Persona 최종 인증은 미완료이며 해당 보고서는 도구 지침에 따라 diagnostic-only로 남긴다. 이는 위 실제 Gradle 실행·DB 관측의 성공/실패와 구분한다. 기존 환경·이력을 초기화하거나 무관한 workflow를 재실행하지 않았다.

## 8. 재현 자료

원시 자료: `backend/output/ci-715/scheduler-deadlock-20261016/`.

- `baseline-1/2/3`: 관측 classpath가 누락된 초기 준비 실행.
- `diagnostic-before-1/2/3`: 실제 listener를 포함한 변경 전 진단.
- `dirty-initial-state-1`: 잔여 데이터 주입 부정 사례와 의도된 실패 XML.
- `fixed-independent-1`부터 `20`: 수정 후 독립 실행.
- `fixed-full-1/2/3`: 수정 후 전체 통합 회귀.
- `plain-regression-1`: 진단 listener 없는 Spotless·단위·통합·인수·JAR 빌드 회귀.
- `audit.py`, `audit.json`: 305개 기록 대조 항목과 실행별 시간.
- 각 디렉터리의 `summary.json`, `console.log`, `integrationTest/xml`, `integrationTest/html`, 관측 실행의 `postgres.log`, `observation.txt`.

원래 deadlock 실패는 `backend/output/ci-715/document-persistence-context-20261016/verified-full-after-3/`에 보존되어 있다.
