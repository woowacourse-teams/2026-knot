# #715 첫 실험: 인증 저장소 테스트 컨텍스트 재사용

- 확인일: 2026-10-15 KST
- Issue: https://github.com/woowacourse-teams/2026-knot/issues/715
- 브랜치: `be/refactor/#715`
- 기준 HEAD: `e826050fa095c03ca03069b70530ab4c3d72672b`에서 작업 트리 변경으로 측정
- 상태: 첫 실험 채택. 대상 실험·단독·역순·전체 통합 649개·Spotless 검증 완료. #715 전체와 GitHub Actions 개선 검증은 후속 범위.

## 문제와 변경

`AuthSessionRepositoryTest`와 `ConsumedRefreshTokenRepositoryTest`는 같은 JPA 속성, 공통 테스트 속성, PostgreSQL 구성을 사용하지만 저장소 구현체 import 하나가 달랐다. 변경 전 결합 실행에서 실제 서로 다른 컨텍스트와 DB 초기화가 확인됐다.

`backend/src/test/java/com/knot/backend/auth/infrastructure/AuthSessionRepositoryTest.java:26`의 import에 `ConsumedRefreshTokenRepositoryImpl.class`를 추가해 두 클래스의 import를 정렬했다. 추가 구현체는 JPA 저장소에 위임하는 어댑터이며 스케줄러나 생명주기 초기화 동작이 없다.

테스트 본문과 assertion, 기본 트랜잭션 롤백, PostgreSQL 이미지, Flyway, 캐시 최대 크기 8, 테스트 힙 1g는 유지했다. 새 공통 애노테이션과 production 변경은 없다.

## 측정 조건

| 항목 | 조건 |
| --- | --- |
| 호스트 | Apple M5 Pro, 메모리 24 GiB, macOS 26.6.2 |
| Java | Homebrew OpenJDK 25.0.3, arm64 |
| Gradle | Wrapper 9.5.1 |
| Docker / DB | Docker 29.2.1, aarch64 / `pgvector/pgvector:pg18` |
| 실행 | 두 클래스를 동일 `integrationTest` JVM에서 결합 실행, 전후 각 3회 |
| 재실행 | `integrationTest --rerun --no-build-cache --console=plain` |
| 관측 | 제품 설정을 바꾸지 않고 로컬 init script로 context cache TRACE 및 transaction DEBUG 활성화 |
| 순서 | seed 1에서 AuthSession → ConsumedRefreshToken, 실제 Gradle 클래스 진입 로그로 확인 |
| 준비 비용 | 측정 전후 모두 컴파일 UP-TO-DATE. 변경 후 컴파일은 별도 `testClasses`로 먼저 완료 |
| 캐시 | Gradle daemon·의존성·Docker 이미지 캐시는 유지. clean과 이미지 삭제는 하지 않음 |

첫 변경 전 실행은 다른 실행보다 느렸다. 임의로 제외하지 않고 세 실행 모두와 범위를 기록했다. 무작위 교차 반복 실험은 아니므로 호스트 상태와 캐시의 시간적 변화가 수치에 영향을 줄 수 있다. 구조적인 초기화 감소와 소표본의 실행 시간 관측을 구분한다.

## 결합 실행 결과

| 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 기존 테스트 | 매 실행 10개 성공, 실패·오류·스킵 0 | 동일 |
| 실제 컨텍스트 수 | 매 실행 2개 | 매 실행 1개 |
| Spring 시작 완료 로그 | 매 실행 2건 | 매 실행 1건 |
| PostgreSQL 시작 완료 로그 | 매 실행 2건 | 매 실행 1건 |
| Flyway 적용 완료 로그 | 매 실행 2건 | 매 실행 1건 |
| 테스트 트랜잭션 롤백 | 매 실행 10건 | 매 실행 10건 |
| 명령 전체 시간 | 16.110 / 12.351 / 12.180초 | 8.298 / 8.025 / 8.181초 |
| 명령 전체 시간 중앙값 | **12.351초** | **8.181초** |
| 명령 전체 시간 범위 | 12.180–16.110초 | 8.025–8.298초 |
| XML suite time 합계 중앙값 | 10.036초 | 6.409초 |
| Spring 시작 시간 합계 중앙값 | 9.347초 | 5.710초 |

명령 전체 시간은 Python이 Gradle 프로세스 실행부터 종료까지 측정한 시간이다. XML suite time, HTML duration, CI step 시간과 다른 지표다. Spring 시작 시간에는 DB 준비 비용이 포함되므로 PostgreSQL/Flyway 시간을 더하지 않는다.

이 대상 범위의 명령 전체 시간 중앙값은 약 **4.17초(33.8%)** 감소했다. 이는 캐시 추적 로그를 켠 로컬 두 클래스 실행 결과이며 GitHub Actions 전체 CI 개선율이 아니다.

## 실제 컨텍스트 재사용 근거

변경 전 첫 실행에서는 AuthSession의 컨텍스트 ID가 `900073076`, ConsumedRefreshToken의 ID가 `1071273853`이었다. 변경 후 첫 실행에서는 두 클래스가 모두 `900073076`을 조회했다.

```text
AuthSessionRepositoryTest: Storing ApplicationContext [900073076] in cache ...
ConsumedRefreshTokenRepositoryTest: Retrieved ApplicationContext [900073076] from cache ...
```

위 두 줄은 원본 로그에서 클래스명과 식별자만 발췌한 것이다. 식별자는 같은 실행의 JVM 안에서 비교했다. 서로 다른 실행에서 같은 숫자가 나오는 것을 프로세스 간 재사용으로 해석하지 않는다.

## 격리와 순서 검증

| 실행 | 관측한 클래스 순서 | 결과 | 롤백 |
| --- | --- | --- | --- |
| AuthSession 단독 | AuthSession | 6개 성공 | 6건 |
| ConsumedRefreshToken 단독 | ConsumedRefreshToken | 4개 성공 | 4건 |
| 결합 정순, seed 1 | AuthSession → ConsumedRefreshToken | 10개 성공, 컨텍스트·DB 각각 1개 | 10건 |
| 결합 역순, seed 4096 | ConsumedRefreshToken → AuthSession | 10개 성공, 컨텍스트·DB 각각 1개 | 10건 |

기존 테스트가 사용하는 동일 해시의 저장·중복 거부·FK·만료 제약 검증을 변경하지 않고 통과했다. 롤백 로그와 양방향 실행은 이 두 클래스의 관측 범위에서 격리가 유지되는 근거다. 모든 향후 공유 조합의 격리를 증명하지는 않는다.

## 원시 근거와 재현

원시 산출물은 `backend/output/ci-715/auth-context-20261015/`에 보존했다.

- `original-reports/`: 재실행 전 기존 통합·인수 HTML 및 XML 원본
- `before-1`–`before-3`, `after-1`–`after-3`: 실행별 command·exit·시간·집계 `summary.json`, `console.log`, `xml/`, `html/`
- `standalone-1`, `standalone-2`, `order-seed-4096`: 단독·역순 결과
- `full-integration`: 첫 전체 회귀 실패 원본(649개 중 14개 실패)
- `recording-recheck`: 실패 클래스 단독 14개 성공 원본
- `full-integration-retry`: 전체 649개 성공 및 Spotless 통과 원본
- `change.patch`: 이 실험의 Java 한 줄 변경
- `profile.init.gradle`: 제품 설정을 변경하지 않는 로컬 관측 설정
- `measure.py`: 실행별 리포트를 별도 디렉터리에 복사하고 기존 결과 덮어쓰기를 거부하는 로컬 실행기

실제 실행 명령은 각 `summary.json`에 저장돼 있다. 같은 출력 이름을 재사용하는 실행기는 기존 결과를 덮어쓰지 않는다. 다시 측정할 때는 새 산출물 경로를 사용한다.

## 전체 회귀 중 관찰한 실패

첫 전체 통합 실행은 649개 중 `RecordingSessionRepositoryIntegrationTest`의 14개가 실패했다. 첫 실패는 컨테이너가 시작된 다음 Flyway가 DB 연결을 얻는 단계의 `SQL State 08001` → PostgreSQL `The connection attempt failed` → `SocketTimeoutException: Read timed out`이다. 나머지는 같은 컨텍스트 로딩 실패에 따른 연쇄 실패였다.

해당 실행에서 인증 저장소 두 클래스는 통과했다. 하지만 이 사실만으로 전체 실패와 이번 변경이 무관하다고 확정하지 않는다. 실패 원본을 보존하고 코드·timeout·컨테이너 설정을 바꾸지 않은 채 실패 클래스 단독 14개 및 전체 통합 649개 재실행이 모두 통과하는 것을 확인했다. 연결 실패의 근본 원인을 확정하거나 해결한 것은 아니다. 실패 실행은 성능 개선 비교에 사용하지 않는다.

최종 `integrationTest --rerun --no-build-cache --console=plain spotlessCheck`는 명령 전체 214.437초에 종료 코드 0을 반환했다. 80개 클래스·649개 테스트의 실패·오류·스킵은 모두 0이며 Spotless도 통과했다.

| 전체 통합 지표 | 보존한 기존 리포트 | 이번 변경 후 성공 실행 |
| --- | --- | --- |
| 테스트 수 | 649 | 649 |
| Spring 시작 완료 로그 | 50 | 49 |
| PostgreSQL 시작 완료 로그 | 58 | 57 |
| Flyway 적용 완료 로그 | 128 | 127 |

각 초기화 로그가 전체 실행에서도 한 번씩 감소했다. 기존 전체 실행과 이번 전체 실행은 동일 조건의 3회 반복 비교가 아니므로 전체 통합 시간 개선율은 계산하지 않는다. 최초 전체 실패·단독 성공·전체 재실행 성공을 함께 기록한다. 인수·단위 테스트와 GitHub Actions는 이번 첫 실험에서 다시 실행하지 않았다.

## 판정과 다음 범위

두 클래스에서 가설 1의 설정 차이가 실제 컨텍스트 분리로 이어졌고, 가설 2의 DB/Flyway 초기화도 동반됐다. import 정렬로 컨텍스트·컨테이너 초기화 한 번을 제거할 수 있었다.

이 실험은 캐시 퇴출 가설 3이나 SSE·워커 대기 가설 4를 검증한 것이 아니다. 전체 CI 개선은 완료되지 않았다. 다음에는 문서 조회 등 유사한 JPA 테스트의 import 차이를 검토할 수 있다. 캐시 크기 조정은 퇴출·재생성 로그에 근거한 별도 실험으로 진행한다.

## 프로젝트 종료 검사 상태

Persona의 implementation/review 보고서 작성 검사는 모두 통과했다. `npx ph workflow finish implement`는 `source-read-runtime-unavailable`로 종료 코드 1을 반환했다. 변경 후 Java evidence read도 unavailable이었다. 이는 위 실제 Gradle 컴파일·집중·전체 통합·Spotless 성공과 별도의 도구 환경 한계이며 Persona 종료 인증을 받았다고 주장하지 않는다. 다른 작업의 전역 상태를 초기화하거나 무관한 검증을 재실행하지 않았다.
