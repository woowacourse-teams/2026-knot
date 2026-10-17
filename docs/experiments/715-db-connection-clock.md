# C3 시계 편차 판정 검증 결과

## 1. 실험 목적과 범위

**Fixture 15개와 기존 C1~C3 로그 재생 1묶음으로 실행 전체의 시계 경고와 실제 수집 구간의 일관성을 분리했다. 새 전체 통합 테스트와 DB·패킷 수집은 실행하지 않았다.**

- 확인일: 2026-10-17
- 관련 이슈: [#715 — Backend CI 실행 시간 개선](https://github.com/woowacourse-teams/2026-knot/issues/715)
- 브랜치·HEAD: `be/refactor/#715`, `1b7d001ecb29d9021659e270655d3bd364da8803`
- 선행 계획: [C3 시계 편차 분석과 수집 구간별 판정 검증](../implement-plan/715-db-connection-clock.md)
- 기존 실행 결과: [DB 전달 구간 관측](715-db-connection-delivery.md)

기존 구현은 테스트 전체의 wall/monotonic offset 범위를 검사하고 같은 결과를 모든 수집 session에 적용했다. 수집이 끝난 뒤 발생한 변화도 이미 종료한 session을 불완전으로 만들 수 있었다. 이번에는 별도 읽기 전용 분석기를 작성해 이 설명을 검증했다. 기존 수집 코드·Java agent·manifest·엄격 판정은 수정하지 않았다.

작업 전 `git pull --ff-only` 결과는 현재 브랜치 upstream 기준 `Already up to date`였다. develop 병합·rebase는 수행하지 않았다.

## 2. 판정 방법

| 결과 | 의미 |
| --- | --- |
| 실행 전체 경고 | 해당 source 전체에서 wall/monotonic offset 범위가 10ms 이상 |
| 구간 내 관측 일관성 | 경계·표본 조건을 충족하고, 수집 구간과 인접 표본에서 큰 변화가 관측되지 않음 |
| 미확인 | 경계 누락·표본 부족·긴 공백·변화 경계 겹침 등으로 판단하기 어려움 |

`window_clock_consistent`는 **보조 분석 결과**다. 기존 `capture_complete`를 대체하지 않고, PostgreSQL까지의 물리 소켓 연결 정체성이나 장애 해결을 증명하지 않는다.

- source 내부에서 `wall 차이 − monotonic 차이`를 계산한다. Java와 Python의 raw monotonic 원점을 직접 빼지 않는다.
- manifest의 container ID·첫 Driver ID·port와 원본의 시작·ENTER·EXIT를 대조한다. 양쪽 READY·종료 경계도 확인한다.
- JVM의 밀리초 해상도를 고려해 수집 요청부터 마지막 수집 종료까지 ±1ms 범위를 조회한다.
- JVM 이벤트와 독립 Python heartbeat를 각각 검사한다. 구간 안 표본 3개 이상, 바깥 경계 표본, 인접 표본 간격 최대 2.5초를 요구한다.
- 2.5초는 1초 heartbeat에서 한 주기 누락과 여유를 허용한 보조 분석 정책이다. 연속 관측이나 통계적 보장을 뜻하지 않는다. 부족하면 기준을 낮추지 않고 미확인으로 남긴다.
- 호스트 및 컨테이너 reader가 호스트에서 기록한 수신 시각도 source별로 검사한다. 컨테이너 VM clock 자체를 검증한 값은 아니다.
- 지속 변화는 변화 전·후 각각 3개 표본의 일관성을 확인한다. JVM과 독립 Python의 변화 구간·방향이 함께 맞는지도 대조한다.

원본은 두 시각 조회를 앞뒤 monotonic으로 감싸지 않았다. 표본 사이의 숨은 변화나 개별 조회 지연을 완전히 배제할 수 없으므로 결과 이름에도 ‘관측 표본에서’를 명시했다.

## 3. Fixture 검증 결과

최종 **15개 모두 통과**했다.

| 검증 범주 | 사례 수 | 확인한 동작 |
| --- | ---: | --- |
| 변화 시점과 구간 경계 | 3 | 수집 뒤 변화는 전체 경고와 분리. 구간 안·경계에 걸친 변화는 일관성 확정 금지 |
| 경계·표본 부족 | 5 | 경계 누락, 희소 표본, 바깥 경계 누락, 내부 표본 2개, 긴 공백을 미확인 처리 |
| 일회성 이상과 지속 변화 | 2 | 한 표본 이상을 지속 변화로 오인하지 않음. 지속 변화도 서비스 원인으로 확정하지 않음 |
| clock domain·입력 순서 | 3 | 다른 monotonic 원점에 영향받지 않으며, source 혼합·역순 입력 거부 |
| 기존 한도·연결 증거 한계 | 2 | 10ms 한도 유지. 시계 일관성만으로 proxy 소켓 대응 확정 금지 |

수집 뒤 변화 fixture는 원본 `capture.py`의 `clock_stable()` 함수만 AST로 분리해 실행했다. 원래 함수는 전체 실행을 불안정으로 판정했고, 새 분석은 변화 전 수집 구간을 구분했다. 실제 수집기를 시작하지 않고 기존 판정의 원인을 검증했다.

처음에는 분석기 미구현으로 import 오류가 발생했다. 구현 후에는 13개 중 한 표본 이상 fixture가 실패했다. 원상 복귀 순간을 지속 변화로 오인하는 문제를 발견해 변화 전·후 각각 3개 표본이 안정적인 경우만 지속 변화로 기록하도록 수정했다. 이후 경계 사례 2개를 추가한 최종 15개가 통과했다. 제품 테스트 실패를 고친 결과가 아니라 새 분석기의 검증 과정이다.

## 4. 기존 C1~C3 재생 결과

세 실행을 같은 분석기로 각각 한 번 처리했다.

### 실행 전체

| 실행 | JVM offset 범위 | 독립 Python heartbeat 범위 | 전체 경고 | 기존 완전 수집 판정 |
| --- | ---: | ---: | --- | ---: |
| C1 | 1.495ms | 1.700ms | 없음 | 2/2 유지 |
| C2 | 1.516ms | 9.308ms | 없음 | 2/2 유지 |
| C3 | 57.381ms | 56.442ms | **유지** | **0/2 유지** |

C3는 JVM·독립 Python heartbeat의 지속 변화 구간과 방향이 함께 맞았다. 별도 JVM heartbeat와 Python 자원 수집의 offset 범위도 각각 56.755ms·56.424ms였다. 특정 OS 서비스의 시간 보정을 확정한 결과는 아니다.

### 수집 구간별 보조 판정

| 실행 | 대상 클래스 | JVM 표본 수 | 구간 JVM offset 범위 | 구간 안 독립 heartbeat 수 | 보조 판정 |
| --- | --- | ---: | ---: | ---: | --- |
| C1 | DocumentConfirmationSnapshotIntegrationTest | 146 | 0.973ms | 3 | 관측 표본에서 일관됨 |
| C1 | SearchConversationListSnapshotIntegrationTest | 153 | 0.961ms | 2 | **미확인: heartbeat 부족** |
| C2 | DocumentConfirmationSnapshotIntegrationTest | 146 | 0.974ms | 2 | **미확인: heartbeat 부족** |
| C2 | SearchConversationListSnapshotIntegrationTest | 152 | 0.984ms | 3 | 관측 표본에서 일관됨 |
| C3 | DocumentConfirmationSnapshotIntegrationTest | 99 | 0.991ms | 3 | 관측 표본에서 일관됨 |
| C3 | SearchConversationListSnapshotIntegrationTest | 108 | 0.990ms | 5 | 관측 표본에서 일관됨 |

6구간 중 **4구간은 관측 일관성 확인, 2구간은 자료 부족으로 미확인**이다. C1·C2의 기존 완전 수집 판정을 실패로 덮어쓴 것도 아니다. 새 분석은 독립 heartbeat 조건을 추가해 기존 판정과 다른 질문에 답한다.

호스트/컨테이너 reader 수신 시각의 offset 범위는 6구간 모두 10ms 미만이었다. C3에서 각각 호스트 0.038/0.014ms, 컨테이너 reader 0.013/0.012ms였다.

### C3의 시간 순서

| 사건 | 저장된 wall 시각의 KST 표시 |
| --- | --- |
| 첫 DB의 양쪽 수집 종료 완료 | 17:57:41.584 |
| 두 번째 DB의 양쪽 수집 종료 완료 | 17:59:46.860 |
| JVM의 마지막 큰 변화 전 표본 | 18:00:23.017 |
| JVM의 첫 큰 변화 후 표본 | 18:00:23.683 |
| 독립 Python heartbeat의 변화 전·후 | 18:00:22.956 → 18:00:23.904 |

**큰 변화 구간은 두 번째 수집 종료 뒤 약 36초 이상 떨어져 있었다.** C3의 두 구간 결과는 이전 수동 분석과 일치했다. 이후 전체 경고를 모든 session에 적용하는 코드가 기존 0/2 판정으로 이어졌다는 설명을 뒷받침한다. 이 시각으로 clock domain 간 단방향 네트워크 지연을 계산하지 않았다.

## 5. 원본·기존 검증 보존

| 항목 | 결과 |
| --- | --- |
| 원본 connection-delivery 트리 | **3,069개 파일의 재생 전후 SHA-256 모두 동일** |
| C1/C2/C3 기존 capture_complete | **2/2, 2/2, 0/2 유지** |
| 기존 cohort exit code | **1 유지** |
| 새 전체 통합·인수 테스트 | **0회** |
| 새 DB·패킷 수집·host clock 설정 변경 | **0회** |
| 제품 Java·기존 agent·frozen runtime 변경 | **없음** |

기존 세 실행의 649개 성공·대상 74개 격리·스케줄러 검증·로그 보존 증거는 원본 그대로 보존했다. 이를 이번에 다시 실행한 검증으로 표현하지 않는다. 원본 해시 일치는 보존의 증거이며 새 DB 격리 검증의 대체가 아니다.

### 산출물과 실행 명령

- 분석기: [clock_review.py](../../backend/output/ci-715/connection-clock-review/clock_review.py)
- fixture: [test_clock_review.py](../../backend/output/ci-715/connection-clock-review/test_clock_review.py)
- 최종 fixture 로그: [fixture-result.txt](../../backend/output/ci-715/connection-clock-review/fixture-result.txt)
- 구간별 분석·원본 전후 해시: [replay-result.json](../../backend/output/ci-715/connection-clock-review/replay-result.json)

backend 디렉터리 기준으로 실행했다.

```sh
python3 -B -m unittest discover -s output/ci-715/connection-clock-review -p 'test_clock_review.py' -v
python3 -B output/ci-715/connection-clock-review/clock_review.py --input output/ci-715/connection-delivery --output output/ci-715/connection-clock-review/replay-result.json
```

분석기는 원본 트리 안 출력과 기존 출력 덮어쓰기를 거부한다. 위 JSON이 이미 생성돼 같은 명령을 반복하면 거부된다. 재생 1묶음 뒤 추가 재생은 하지 않았다.

Python LSP는 기존 미설치 상태로 유지했다. 실제 Python 실행과 에디터 검사를 구분한다. Persona의 implementation/review 보고서 검사는 통과했지만 `workflow finish implement`는 `source-read-runtime-unavailable`로 exit 1이었다. 공식 종료 인증은 미완료이며 실제 fixture·재생 결과와 구분한다. 과거 이력을 재생성하거나 무관한 전체 테스트를 실행해 우회하지 않았다. 결과 불변식·로컬 문서 링크 21개·공백 검사와 `git diff --check`도 통과했다.

## 6. 판단과 종료

**이번 작은 판정 검증은 완료하고 종료한다.** C3는 실행 전체 시계 경고를 유지하면서 수집 구간 안의 관측 일관성을 별도로 설명할 수 있다. C1·C2의 자료가 부족한 구간은 미확인으로 남긴다.

OS clock 변화의 정확한 주체, 과거 A3 timeout 원인, Docker proxy 양쪽의 물리 소켓 대응, 개선안 5의 전체 CI 성능 이점은 확정하지 않았다. 이를 확정하려고 전체 테스트를 추가 반복하지 않는다.

CI 개선 마무리는 [전체 진행표](715-ci-improvement-progress.md)의 **최종 코드·공통 기준선 확정 → 최종 회귀·실제 Actions 비교 → 성과 문서 정리**로 돌아간다. 개선안 5 채택이나 후보 제거는 이번에 실행하지 않았다.
