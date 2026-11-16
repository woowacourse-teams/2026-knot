# 회의 문서 하이브리드 검색 비교 기준

[#1084](https://github.com/woowacourse-teams/2026-knot/issues/1084)의 기준선은 현재 `develop`의 키워드 검색이다. [#1087](https://github.com/woowacourse-teams/2026-knot/issues/1087)의 색인·검색 및 [#1091](https://github.com/woowacourse-teams/2026-knot/issues/1091)의 전문 기반 답변을 추가하는 단계마다 같은 기준선을 다시 사용한다.

## 질문별 판정

두 경로에 같은 문서 스냅샷·권한·질문·대화 순서·기준 시각·공급자 설정을 사용한다. 합성 시나리오에는 문서 100개 이상, 타 워크스페이스의 동명 문서, 무결과, 조회 오류, 최초 답변, 회의 확인과 선택, 문맥이 필요한 후속, 일반 대화, SSE 재연결과 중복 접수를 포함한다.

| 지표 | 기록 방법 |
| --- | --- |
| 본문 사실 충족 | 질문이 요구한 사실·결정 상태·조건을 각각 확인한다. 인용에만 있는 사실은 본문 충족으로 집계하지 않는다. |
| 완전 대화 | 필요한 턴이 모두 본문의 요구 사항과 정확한 회의 귀속을 충족한 대화 수를 전체 대화 수와 함께 기록한다. |
| 인용 지지 | 답변의 각 주장과 연결된 원문을 대조한다. 정확한 회의·문서 귀속과 구절의 의미 지지를 각각 기록한다. |
| 안전성 및 실패 | 권한 누출·회의 오귀속·중대한 허위 결정, 세부 사실 누락, 정상 무결과, 색인 미준비, 일부 근거 및 실행 실패를 각각 집계한다. |
| 비용 및 자원 | 요청 수, 호출 수, 입력·출력 토큰, Worker와 검색 대기, DB Connection Pool, 공급자 지연·오류를 기록한다. |

고정 케이스 별칭과 집계 결과만 남긴다. 실제 회의 원문은 사용 범위와 비식별 여부가 확인된 환경에서 평가한다. 테스트 지연 로그에는 단계, 시간과 종료 이벤트만 출력한다.

## 최초 응답과 완료 시간

HTTP 요청 전송 직전부터 첫 비공백 본문 `delta`까지의 시간을 사용자 첫 응답으로 기록하고, `completed`, `stopped`, `failed`까지 걸린 시간도 따로 기록한다. `accepted`, `progress`, 재접속의 `snapshot`은 본문 Delta에 포함하지 않는다. 본문 없이 끝난 요청과 종료 이벤트 없이 끊긴 요청은 각각 집계한다.

dev에서는 동시성 4·8을 분리하고 각 조건의 최초·회의 선택·후속 요청별로 첫 Delta p95와 성공 완료 p95·p99, 전체 요청·실패·본문 미수신 건수를 기록한다. 백분위는 관측값을 정렬한 뒤 `ceil(p × n)`번째 값을 사용한다. 첫 Delta p95 10초 이하, 회의 오귀속·권한 누출·중대한 허위 결정 0건, 재접속과 기존 검색 전환을 제한 적용의 판정 근거로 삼는다.

## 2026-11-15 KST · 로컬 기준선

| 검증 | 결과 |
| --- | --- |
| Java SSE 계측 단위 테스트 | 3개 통과 |
| Testcontainers PostgreSQL 문서 검색 통합 테스트 | 43개 통과. 신규 합성 시나리오 102문서 중 같은 Workspace의 기대 문서만 조회 |
| 실제 Spring HTTP/SSE 인수 테스트 | 48개 통과. 최초·후속·중복 접수·재연결의 기존 계약 포함 |

| 단일 요청 | 첫 본문 Delta | 종료 |
| --- | ---: | ---: |
| 최초 | 134ms | 135ms |
| 후속 | 160ms | 161ms |
| 동일 후속 요청 재접수 | 15ms | 15ms |

로컬 SSE 인수 테스트는 생성기를 대역으로 실행한다. dev 부하·실모델 품질·하이브리드 검색 비교는 연결 후 수행한다.

세 이슈 변경을 합친 로컬 작업 트리에서는 단위 20개, 통합 46개, 인수 49개가 통과했다. 플래그를 켠 Spring 워커가 HTTP 대역에서 벡터와 구조화 답변을 받아 PostgreSQL에 답변과 근거를 저장하는 경로를 포함했다. 같은 회귀 묶음의 첫 본문 Delta는 최초 156ms, 후속 196ms, 동일 후속 재접수 26ms였다. 종료 시간은 각각 160ms, 197ms, 26ms였다.

```sh
./gradlew test --tests '*SearchSseTimingTest' \
  integrationTest --tests '*SearchDocumentToolServiceIntegrationTest' \
  acceptanceTest --tests '*SearchQuestionAcceptanceTest' \
  --tests '*SearchFollowupQuestionAcceptanceTest' --tests '*SearchAnswerEventsAcceptanceTest'
```
