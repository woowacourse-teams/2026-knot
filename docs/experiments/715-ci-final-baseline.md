# Backend CI 최종 코드 정리와 A/B 비교 기준선 확정 결과

> 후속 커밋·push: 개선안5 제외 `f1c653d75`·공통 리포트 보존W `29bf52bad`를 `be/refactor/#715`에 반영했다. 실행 경로가 고정B와 일치함을 재확인했다. 아래 미커밋 표시는 코드 정리 당시 기록이다. [Actions A/B 계획](../implement-plan/715-actions-ab-comparison.md)은 작성했으며 원격 비교는 미실행이다.

> 후속 실행: 같은 B의 [대상 테스트·격리 결과](715-ci-final-isolation.md)에 이어 [일반 전체 회귀](715-ci-final-regression.md)도 1회 완료했다. Spotless·단위1543·통합676·인수567·bootJar 모두 통과, 총2786건 실패/오류/스킵0이다. 아래 미실행 표시는 코드 정리 당시 기록이며 Actions는 여전히 미실행이다.

## 1. 작업 결과

**개선안 5의 import 변경만 제외하고 최신 develop을 작업 브랜치에 병합했다. 최종 후보는 개선안 1·2·4와 스케줄러 수정이다.** 같은 제품 코드에서 이 세 개선안의 유무만 다른 A/B 소스 스냅샷도 보존했다.

이번 작업은 코드 정리와 정적 대조까지다. 전체 테스트·대상 테스트·격리 재검증·GitHub Actions는 실행하지 않았다. 최종 실행 시간이나 CI 개선율은 아직 계산할 수 없다.

| 항목 | 실제 상태 |
| --- | --- |
| 확인일 | 2026-10-17 |
| 작업 브랜치 | `be/refactor/#715` |
| 공통 제품 기준선 D | `0c0c4e64e9bcbf3c68a26c62bb42aa0aea4694dd` |
| develop 병합 커밋 | `10b207a70f04c247054448725f936275d5baa4a0` |
| 병합 결과 | 충돌 없음. D가 현재 HEAD의 조상임을 확인 |
| 개선안 5 제외·공통 리포트 보존 W | 로컬 작업 트리에 적용. 별도 커밋 전 |
| 원격 상태 | 이번 작업에서 push·비교용 ref/PR 생성·develop 원격 반영 없음 |

**병합 커밋은 최종 B 커밋이 아니다.** 개선안 5 제외와 W는 아직 커밋하지 않았으므로 이후 실행에서 HEAD만 지정하면 이번 최종 후보와 달라진다. 현재 후보의 소스는 아래 B tree·archive로 고정했다. 실제 Actions를 실행하기 전 해당 내용과 일치하는 비교용 커밋과 checkout SHA를 확정해야 한다.

이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715). 승인 계획: [최종 변경 범위와 비교 기준선](../implement-plan/715-final-ci-baseline.md).

## 2. 최종 채택·보류 범위

| 구분 | 이번 코드 정리 결과 | develop 대비 Java 변경 파일 수 |
| --- | --- | ---: |
| 개선안 1 | 인증 저장소 테스트의 import 정렬 유지 | 1 |
| 개선안 2 | 문서 조회 JPA 4클래스의 공통 import 구성 유지 | 4 |
| 개선안 4 | snapshot 5클래스의 동일한 type-level spy 구성과 주입 유지 | 5 |
| 스케줄러 수정 S | 초기 TRUNCATE 대신 8개 테이블의 빈 상태를 검증하는 수정 유지 | 1 |
| 개선안 3 | 캐시 확대 보류. 최대 크기 8 유지 | 0 |
| 개선안 5 | 해당 커밋의 8파일 import 변경만 역적용 | 최종 D 대비 추가 변경 0 |
| **채택 합계** | **1·2·4·S** | **11** |

개선안 5가 변경했던 8개 중 문서 조회 4개에는 개선안 2가 남아 있다. 나머지 4개가 develop 대비 원래 구성으로 돌아갔기 때문에 최종 Java diff는 15개에서 11개로 줄었다. W와 기존 실험 문서는 Java 개수에 포함하지 않는다.

### 개선안 5 제외 방식

`f9fa5058f4bb02b9a2acd6bc0e1c9ad0567ebea0`의 import 변경만 되돌렸다. 해당 8파일의 현재 내용이 이 커밋 직전의 파일과 byte 단위로 같은 것을 확인했다. 이번 D에서는 이 파일들이 공통 조상 이후 바뀌지 않아 이 대조가 가능했다. 파일 전체를 오래된 develop으로 복원한 작업은 아니다.

| 테스트 그룹 | 최종 import 구성 |
| --- | --- |
| DocumentConfirmationQuery / DocumentDetailQuery / DocumentGenerationJobListQuery / DocumentListQuery | Testcontainers 설정 + 조회 어댑터 4개. 개선안 2 유지 |
| DocumentCommandRepository | Testcontainers 설정 + DocumentRepositoryAdapter + DocumentConfirmationRepositoryAdapter |
| DocumentGenerationJobRetryRepository | Testcontainers 설정 + DocumentGenerationJobRepositoryAdapter + DocumentGenerationInputQueryAdapter |
| DocumentTranscriptQuery | Testcontainers 설정 + DocumentTranscriptQueryAdapter + TranscriptSegmentRepositoryAdapter |
| TranscriptSegmentRepository | Testcontainers 설정 + TranscriptSegmentRepositoryAdapter |

위 이름은 각 IntegrationTest 클래스의 접미사를 생략한 것이다. 파일은 `backend/src/test/java/com/knot/backend/` 아래 기존 위치를 유지한다.

### develop과 기존 작업 보존

`git pull --ff-only`로 upstream을 확인하고 고정한 D 전체를 병합했다. D의 제품 코드·테스트·V52 migration을 공통 기반으로 사용한다. 열린 PR의 코드를 별도로 넣지 않았다.

첫 병합 시도는 기존 untracked `docs/implement-plan/search-intent-classification.md`와 develop의 같은 경로가 겹쳐 Git이 중단했다. 로컬 원본을 별도 임시 경로에 보존하고 병합한 뒤 원래 위치에 복구했다. 따라서 이 파일은 이제 develop 문서와 다른 사용자 소유의 로컬 변경으로 표시된다. CI 변경에 포함하지 않는다.

이 문서와 기존 tracked 사용자 변경 3개, 총 4파일의 SHA-256이 작업 전후 동일하다. 나머지 사용자 파일과 기존 관측·실패 로그는 변경 대상에서 제외했다. 기존 실험 폴더 전체를 이번에 다시 hash 검증한 것은 아니다.

## 3. A/B 비교 기준선

**A = D + S + W**, **B = D + S + W + 개선안 1·2·4**로 고정했다.

| 비교 항목 | 비교 전 A | 비교 후 B |
| --- | --- | --- |
| develop D | 동일 SHA | 동일 SHA |
| 스케줄러 수정 S | 적용 | 같은 소스 적용 |
| 공통 리포트 보존 W | 적용 | 같은 workflow 적용 |
| 개선안 1·2·4 | 미적용 | 적용 |
| 개선안 3·5 | 미적용 | 미적용 |
| 운영 코드·migration·Gradle·테스트 공통 설정 | 동일 | 동일 |
| 테스트 메서드·assertion·준비/정리 로직 | 동일 | 동일 |

실제 비교는 **1·2·4를 함께 적용한 효과**를 측정한다. 스케줄러 수정의 성능 효과는 이 A/B의 차이에 포함되지 않는다.

### 보존한 소스 식별자

| 항목 | Git tree ID |
| --- | --- |
| A | `82f44a9047f0ece2c92720432d7cba4a1a93ba2f` |
| B | `683b6d90249b60d3d60362af507b767971badfc7` |

이 값은 **커밋 SHA가 아닌 Git tree ID**다. 비교 브랜치나 PR을 만든 상태가 아니다. 현재 작업 트리의 backend 소스·빌드 설정·workflow가 B와 같은 것을 확인했다. 사용자 문서와 과거 브랜치의 실험 기록은 순수 A/B 스냅샷에 추가하지 않았다.

소스와 재현 자료는 다음 전용 경로에 저장했다.

```text
backend/output/ci-715/final-baseline/
  manifest.json       기준 SHA·tree·파일 hash·정적 검증·미실행 범위
  A.tar.gz            비교 전 소스
  B.tar.gz            비교 후 소스
  D-to-A.patch        D에 공통 S·W 적용
  A-to-B.patch        개선안 1·2·4만 적용
  D-to-B.patch        최종 채택 코드와 W
  freeze.py           임시 Git index를 사용하는 소스 대조·보존 도구
```

archive와 patch의 SHA-256은 manifest에 기록했다. 스냅샷은 별도 임시 index에서 구성했으며 실제 브랜치의 index·ref를 비교 버전으로 바꾸지 않았다.

## 4. 공통 리포트 보존 W

실제 CI에서 XML·HTML을 비교할 수 있도록 `.github/workflows/backend-ci.yml` 마지막에 `Preserve test reports` 한 단계를 추가했다. 업로드 구현과 입력은 [GitHub 공식 upload-artifact 문서](https://github.com/actions/upload-artifact/tree/v4)를 기준으로 했다.

- `actions/upload-artifact@v4`, `if: always()` 사용.
- `backend/build/test-results/`, `backend/build/reports/tests/` 보존.
- 이름에 run ID와 attempt를 넣어 실행을 구분.
- 리포트가 생성되지 않은 경우 `if-no-files-found: warn`으로 기존 검증 실패와 구분.
- 기존 포맷 → 단위 → 통합 → 인수 → JAR 순서·명령·runner·캐시·Discord 실패 알림은 그대로 유지.

YAML 구문 검사와 기존 workflow 전체 설정·단계 대조는 통과했다. 실제 Actions 업로드 성공은 아직 확인하지 않았다. 업로드 시간은 공통 관측 비용이므로 기존 검증 단계 시간과 분리해 기록한다. workflow 전체 시간에는 업로드 비용도 포함된다.

## 5. 이번 정적 검증 결과와 한계

| 확인 항목 | 결과 | 근거의 범위 |
| --- | --- | --- |
| 개선안 5만 제외 | 통과 | 8파일이 개선안 5 직전 소스와 byte 동일 |
| develop 반영 | 통과 | D가 병합 HEAD의 조상, 미해결 충돌 없음 |
| 최종 변경 범위 | 통과 | D → B는 Java 11파일 + W 1파일 |
| A/B 변경 범위 | 통과 | 차이는 1·2·4의 Java 10파일뿐 |
| 테스트 본문 보존 | 통과 | 해당 10파일의 클래스 본문에서 field 주입 애노테이션만 제외하면 byte 동일. 다른 애노테이션도 설정 정렬 범위만 변경 |
| 스케줄러 공통 적용 | 통과 | A/B의 스케줄러 파일 byte 동일 |
| 제품·DB·빌드 공통 조건 | 통과 | 운영 코드·migration·Gradle·testsupport·workflow 동일 |
| 사용자 파일 보존 | 통과 | 별도 확인한 4파일의 작업 전후 SHA-256 동일 |
| YAML·기존 단계 보존 | 통과 | YAML 구문 정상, 기존 단계·설정과 추가 마지막 1단계 구분 대조 |
| 공백·패치 검사 | 통과 | `git diff --check`, D → B patch 검사 |
| 컴파일·Spotless·대상 테스트·격리·전체 회귀 | **미실행** | 사용자 요청에 따라 코드 정리까지만 수행 |
| 실제 GitHub Actions·성능 비교 | **미실행** | 최종 시간·감소율 미확정 |

스냅샷 생성기의 정적 불변식 14개가 모두 통과했다. 이는 실제 Spring 컨텍스트 공유·DB 격리·스케줄러 동작이 최신 D에서도 통과했다는 의미가 아니다. 캐시 최대 8, JVM 힙 1GB, PostgreSQL 이미지 `pgvector/pgvector:pg18`는 유지했다. 실제 이미지 digest와 실행 테스트 목록은 다음 실행 시 확보한다.

Persona 시작 rail과 implementation/review report-filled 두 검사는 통과했다. `npx ph workflow finish implement`는 기존 `source-read-runtime-unavailable`로 exit 1을 반환해 공식 종료 인증은 미완료다. profile의 직접 읽기와 공식 evidence 등록 실패를 구분하며, 하네스 환경을 재생성하거나 무관한 전체 검증을 실행하지 않았다. 이는 위 정적 검증 실패나 새 테스트 실패를 뜻하지 않는다. Java/YAML/Python LSP의 설치 여부를 실제 컴파일이나 Actions 성공의 근거로 쓰지 않는다.

결과·진행표·계획의 로컬 링크 19개와 보존한 archive/patch 5개의 SHA-256도 확인했다. A/B archive는 각각 2,839개 경로를 포함한다.

## 6. 남은 검증

**코드 정리와 비교 소스 확정은 완료했다. 남은 핵심 작업은 최종 검증·실제 CI 비교, 이후 성과 문서의 두 묶음이다.** 코드 제외·W·문서는 아직 별도 커밋과 push 전이며, 실제 CI 전에 비교 커밋을 확정해야 한다.

1. 최신 D를 반영한 최종 B에서 인증·문서 조회·snapshot 대상 검증과 격리를 확인한다. 스케줄러는 정상 독립 3회와 오염 부정 사례 1회를 확인한다.
2. 최종 B에서 Spotless·단위·통합·인수·bootJar 일반 전체 회귀 1회를 수행한다.
3. 같은 D·S·W의 비교 커밋을 고정하고 실제 Actions를 A/B 각 3회 실행한다. 원시값·중앙값·범위·성공/실패·캐시·실제 checkout SHA·XML을 보존한다.
4. 실제 CI의 단계별 변화와 전체 효과, 채택/보류·간헐 실패·측정 한계를 최종 문서에 기록한다.

새로운 연결·시계 진단이나 개선안 5 재채택 실험을 이번 마무리의 필수 작업으로 추가하지 않는다. 기존 A3 연결 timeout의 원인은 미확정이며, 최종 검증에서 실패하면 원본을 보존하고 해당 검증을 미충족으로 남긴다.

과거 평균 7분 13초와 대상 그룹의 로컬 감소율은 배경·개별 실험 결과다. 이번 A/B의 직접 결과로 사용하거나 서로 합산하지 않는다.
