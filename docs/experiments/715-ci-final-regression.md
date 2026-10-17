# 최종 B 일반 전체 회귀 결과

> 후속: 검증한 실행 코드와 동일한 개선안5 제외·W를 `f1c653d75`·`29bf52bad`로 커밋하고 작업 브랜치에 push했다. 아래는 전체 회귀 실행 당시 기록이다. [Actions A/B 계획](../implement-plan/715-actions-ab-comparison.md)을 작성했으며 Actions·PR은 미실행이다.

## 1. 결론

**최종 B에서 일반 전체 회귀를 1회 실행했고, Spotless·단위·통합·인수 테스트·bootJar가 모두 통과했다.** 단위 1,543건, 통합 676건, 인수 567건으로 총 2,786건이다. 실패·오류·스킵은 모두 0이며 Gradle 종료 코드는 0이다.

요청한 전체 실행은 정확히 1회다. 실패를 교체하는 재실행이나 추가 전체 테스트는 없었고, GitHub Actions·commit·push·PR도 실행하지 않았다. 제품 Java·테스트·Gradle·workflow는 이번 작업에서 변경하지 않았다.

관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715). 승인 계획: [최종 변경 범위와 비교 기준선](../implement-plan/715-final-ci-baseline.md). 선행 근거: [최종 B 대상·격리 검증](715-ci-final-isolation.md).

## 2. 실행 기준

| 항목 | 조건 |
| --- | --- |
| 실행일 | 2026-10-17, 19:00:28~19:05:37 KST |
| 작업 브랜치 | `be/refactor/#715` |
| 공통 제품 기준선 D | `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd` |
| 최종 B tree | `683b6d90249b60d3d60362af507b767971badfc7` |
| B 구성 | D + 스케줄러 수정 S + 리포트 보존 W + 개선안 1·2·4 |
| 보류 | 개선안 3·5 미적용, 컨텍스트 캐시 최대 8 유지 |
| 실행 소스 | 고정 B archive를 새 디렉터리에 추출. 기존 작업 소스와 B의 실행 관련 경로가 일치함을 확인 |
| 환경 | Apple M5 Pro·메모리 24GiB·macOS, OpenJDK 25.0.3, Gradle Wrapper 9.5.1 |
| 전원 | AC Power·배터리 100% 확인, 실행 동안 caffeinate 적용 |
| DB | 실제 Testcontainers PostgreSQL·Flyway, `pgvector/pgvector:pg18` |
| 테스트 JVM | 기존 최대 힙 1GB·기존 태그 분리 유지 |
| 재실행 조건 | `--rerun-tasks --no-build-cache`, 별도 project cache |
| 유지한 캐시 | Gradle daemon·의존성·Docker 이미지 캐시 |
| 관측 조건 | 진단용 init script·agent·listener·클래스 정렬·테스트 필터 추가 없음 |

B archive SHA-256은 `8236fc32d0c15f3a1b38577222a74e1fd3c25d4ab39a7e067529584b3af20eeb`이다. 추출 전 archive hash와 실행 전후 소스 hash를 대조했다.

현재 HEAD `10b207a70`만 checkout하면 최종 B와 다르다. 개선안 5 제외와 W는 아직 로컬 변경이므로 이번에는 검증한 archive를 실행했다. fetch된 새 `origin/develop`(`46918f4f3`)을 B에 혼합하지 않았다. 실제 Actions 비교 전에도 이 소스와 일치하는 비교용 커밋·checkout SHA를 확정해야 한다.

실제 실행 명령은 다음과 같다. 위치는 추출한 `source-B/backend`이며 절대 project cache 경로만 줄여 표기했다.

```bash
./gradlew spotlessCheck test integrationTest acceptanceTest bootJar \
  --rerun-tasks --no-build-cache --console=plain --stacktrace \
  --project-cache-dir /Users/yongtae/Desktop/knot/backend/output/ci-715/final-regression/gradle
```

## 3. 검증 결과

| 단계 | 실행 결과 | 테스트 수 | 실패·오류·스킵 |
| --- | --- | ---: | --- |
| Spotless | 통과 | — | — |
| 단위 테스트 `test` | 통과 | 1,543 | 모두 0 |
| 통합 테스트 `integrationTest` | 통과 | 676 | 모두 0 |
| 인수 테스트 `acceptanceTest` | 통과 | 567 | 모두 0 |
| bootJar | 통과 | — | — |
| **테스트 합계** | **모두 통과** | **2,786** | **모두 0** |

각 태스크의 XML은 이번 실행에서 새로 생성됐다. XML suite는 각각 185·81·45개이고, 선언된 테스트 수와 실제 testcase 행 수를 대조했다. 과거 649개 통합 리포트나 이전 전체 2,635건을 현재 검증 수로 재사용하지 않았다.

실행 파일 `backend-0.0.1-SNAPSHOT.jar`는 85,850,399bytes다. `BOOT-INF/classes`와 manifest의 `JarLauncher`·`Start-Class: com.knot.backend.KnotApplication`을 확인했다. SHA-256은 `533f46cbfe33cf29082850ab025e19cc53d5be1f32220e8f225fbd7999f35aed`이다. 이는 패키징 검증이며 JAR를 기동하거나 배포한 결과는 아니다.

**명령 전체 소요 시간은 308.778초, 약 5분 9초**다. Gradle 표시는 `BUILD SUCCESSFUL in 5m 8s`다. 컴파일·포맷 검사·세 테스트 태스크·패키징과 Gradle 준비/종료 비용을 포함한다.

참고로 XML suite 시간의 단순 합은 단위 10.177초·통합 180.500초·인수 90.660초다. 이 값은 GitHub Actions 단계 시간이나 각 Gradle 태스크의 실제 경과 시간과 같지 않다. 콘솔의 task 표시 시각 역시 정밀 단계 계측값으로 사용하지 않는다.

이번은 로컬 B 단일 실행이므로 **기존 CI 평균 7분 13초와 비교한 개선율이나 A/B 성능 개선율을 계산하지 않는다.** 실행 환경·제품 기준선·테스트 수·캐시 조건과 측정 범위가 다르다.

## 4. 로그 보존과 사후 대조

새 산출물은 모두 `backend/output/ci-715/final-regression/`에 보존했다. 기존 `backend/build` 리포트와 과거 실패 자료를 덮어쓰지 않았다.

| 보존 자료 | 경로 |
| --- | --- |
| 전체 표준 출력·오류·경고 | [console.log](../../backend/output/ci-715/final-regression/console.log) |
| 명령·전원·소스 기준·시작 시각 | [execution.json](../../backend/output/ci-715/final-regression/execution.json) |
| 종료 코드·실행 시간·테스트별 실패 상세 배열·JAR hash | [summary.json](../../backend/output/ci-715/final-regression/summary.json) |
| 단위 HTML | [test/index.html](../../backend/output/ci-715/final-regression/source-B/backend/build/reports/tests/test/index.html) |
| 통합 HTML | [integrationTest/index.html](../../backend/output/ci-715/final-regression/source-B/backend/build/reports/tests/integrationTest/index.html) |
| 인수 HTML | [acceptanceTest/index.html](../../backend/output/ci-715/final-regression/source-B/backend/build/reports/tests/acceptanceTest/index.html) |
| XML·캡처된 테스트 출력 | `source-B/backend/build/test-results/{test,integrationTest,acceptanceTest}/` |
| bootJar 원본 | [backend-0.0.1-SNAPSHOT.jar](../../backend/output/ci-715/final-regression/source-B/backend/build/libs/backend-0.0.1-SNAPSHOT.jar) |
| 선택 산출물 854개 hash·크기 | [artifacts.json](../../backend/output/ci-715/final-regression/artifacts.json) |
| 소스·실행·리포트·사용자 파일·자원 대조 | [audit.json](../../backend/output/ci-715/final-regression/audit.json) |

이번 실행에는 테스트 실패가 없어 실패 상세 배열은 비어 있다. 성공 실행도 전체 콘솔과 XML의 system-out/system-err를 보존했다. 기존 deprecated/unchecked 컴파일 경고와 JVM CDS 경고도 삭제하거나 숨기지 않았다.

사후 대조는 최종 **26/26개 통과**다. 처음 집계한 23/26 결과는 [audit-initial.json](../../backend/output/ci-715/final-regression/audit-initial.json)에 보존했다. 두 항목은 서로 다른 parameterized 메서드의 표시 이름이 같을 수 있는데 이름을 고유 키로 취급한 집계 오류였다. 실제 XML 행 수와 선언 테스트 수가 일치하는 방식으로 수정했다. 나머지 한 항목은 Gradle 직후 아직 종료 중이던 Ryuk 1개였다. 이후 읽기 전용 Docker 조회에서 자연 종료를 확인했고 수동 삭제는 하지 않았다. 제품 테스트 추가 실행은 없었다.

실행 전후 제품 소스와 B 실행 경로는 동일했다. 기존 사용자 수정 파일 4개의 SHA-256도 유지됐다. QA backend/frontend/PostgreSQL 컨테이너는 기존 ID 그대로 유지됐고, 최종 조회에는 이 3개만 남았다. 과거 실험 전체 파일을 이번에 재해시했다는 의미는 아니다.

## 5. 판단과 남은 범위

**최종 B의 대상·격리 검증에 이어 일반 전체 회귀 관문도 통과했다.** 이번에는 별도 격리 계측이나 스케줄러 오염 주입을 반복하지 않았다. 해당 근거는 직전 [대상·격리 결과](715-ci-final-isolation.md)에 기록한 정상 20회·171건 및 예상 부정 사례 1회다.

이번 성공 1회만으로 간헐 DB 연결 timeout의 근본 원인이 해결됐다고 판정하지 않는다. 전체 코드의 모든 클래스 실행 순서에 대한 격리를 입증한 것도 아니다. 기존 검증을 유지한 일반 실행의 성공 근거로 사용한다.

이후 남은 핵심 작업은 같은 D·S·W와 runner/캐시 조건을 맞춘 **실제 Actions A/B 비교와 최종 성과 문서**다. 원격 비교용 ref 준비·실행은 아직 하지 않았다.

프로젝트 절차 기록은 별도다. Persona 구현 rail과 두 `report-filled` 명령은 PASS였다. `workflow finish implement`는 기존 `source-read-runtime-unavailable`로 종료 코드1을 반환했다. 따라서 공식 Persona 종료 인증은 미완료이며 보고서는 diagnostic-only다. 실제 Gradle 전체 성공과 이 절차 오류를 구분한다. 기존 환경·이력을 재생성하거나 인증을 위해 전체 테스트를 추가 실행하지 않았다.

결과·선행 문서·진행표의 로컬 링크41개와 문서 공백 검사를 통과했다. 후속 프로세스 조회에서 이번 추출 경로의 테스트 JVM·실행기·caffeinate 잔존은 없었다. 다른 작업 디렉터리에서 실행 중인 테스트 JVM은 이번 자원이 아니므로 건드리지 않았다.
