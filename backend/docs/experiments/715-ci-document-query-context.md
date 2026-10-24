# #715 개선안 2: 문서 조회 JPA 테스트 컨텍스트 공유

- 상태: 채택. 전후 3회·단독·세 순서·전체 회귀 검증 완료. GitHub Actions 시간 검증은 후속 범위.
- Issue: https://github.com/woowacourse-teams/2026-knot/issues/715
- 브랜치: `be/refactor/#715`
- 선행 개선: 인증 저장소 두 클래스의 import 정렬. 이번 실험에서는 그대로 유지한다.

## 실험 계획

대상은 `DocumentConfirmationQueryIntegrationTest`, `DocumentDetailQueryIntegrationTest`, `DocumentGenerationJobListQueryIntegrationTest`, `DocumentListQueryIntegrationTest`다. 모두 `@Tag("integration")`, 기본 `@DataJpaTest`, `TestcontainersConfiguration`을 사용한다. 각 클래스가 자기 조회 어댑터만 import한다는 차이가 있다. 별도 프로필·속성·mock·spy·동적 속성·DirtiesContext·트랜잭션 재정의는 없다.

조회 어댑터 네 개는 생성자 주입된 Spring Data JPA 저장소에 조회를 위임한다. 직접 생성하는 워커·스케줄러·종료 후처리나 변경 가능한 인스턴스 상태는 없다. 각 테스트의 EntityManager·JdbcClient 접근과 매 테스트 DocumentFixtures 생성은 유지한다. DB 시퀀스 값이 클래스마다 다시 시작한다는 전제는 없는지 기존 단언과 fixture를 함께 검토한다.

가설: 각기 다른 import가 별도 컨텍스트·DB를 만들고 있다면, 네 클래스에서 동일한 네 조회 어댑터와 Testcontainers 설정을 import하도록 정렬하여 반복 초기화를 줄일 수 있다. 이 실험은 원인 가설 1·2의 후속 검증이며 캐시 용량 변경이나 원인 가설 2만의 별도 실험이 아니다.

### 변경 범위

네 테스트의 `@Import` 목록만 정렬한다. 테스트 본문·assertion·기본 롤백·JPA 속성·DB 이미지·Flyway·캐시 8·힙 1g는 유지한다. production 코드, 마이그레이션 테스트와 다른 JPA 테스트는 포함하지 않는다. 공유 애노테이션 도입도 이번 실험에서는 필요하지 않다.

### 실행과 측정

1. 직전 성공 전체 리포트를 별도 보존한다.
2. 변경 전 네 클래스를 동일 JVM에 결합해 같은 seed로 3회 실행한다.
3. import 정렬 후 컴파일을 별도 실행하고 같은 조건으로 3회 측정한다.
4. 같은 실행 안에서 실제 캐시 컨텍스트 ID, Spring 시작, PostgreSQL 시작, Flyway 적용 완료, 테스트별 롤백을 대조한다.
5. 네 클래스 단독 및 서로 다른 클래스 순서에서 기존 검증이 성공하는지 확인한다. 랜덤 seed만 바꾸고 순서를 가정하지 않으며 실제 클래스 진입 순서를 기록한다.
6. 마지막에 기본 실행 설정의 전체 통합 테스트와 Spotless를 확인한다. 단위·인수·bootJar도 게시 전 회귀로 확인하되 성능 비교에는 섞지 않는다.

명령은 `integrationTest --rerun --no-build-cache --console=plain`과 네 클래스의 `--tests` 필터를 사용한다. 로컬 init script로 캐시 TRACE·트랜잭션 DEBUG·클래스 진입 로그를 켠다. 전후 컴파일은 UP-TO-DATE, daemon·의존성·Docker 이미지 캐시 유지 조건으로 맞춘다. 시간은 Gradle 프로세스 시작부터 종료까지 측정하며 각 3회의 중앙값·범위를 보고한다. Spring 내부 DB 시간을 중복 합산하지 않는다.

### 채택 기준

- 전후 테스트 inventory와 검증 수가 같고 실패·오류·스킵이 없다.
- 변경 후 네 클래스가 같은 실제 컨텍스트를 조회하며 DB 초기화도 줄어든다.
- 단독·결합·순서 변경에서 기존 검증이 모두 성공하고 테스트 트랜잭션 롤백을 관측한다.
- 전체 통합 테스트와 Spotless에 회귀가 없다. 실패하면 원본을 보존하고 원인을 분리한다.
- 로컬 제한 범위의 개선율을 GitHub Actions 전체 개선율로 표현하지 않는다.

## 결과

기준 HEAD는 인증 개선 커밋 `0fb869689e9089114888febcabc5dc6cfb788905`다. 이후 네 테스트의 import만 작업 트리에서 변경했다. 컴파일과 Spotless는 별도로 먼저 성공했고 전후 측정의 컴파일·리소스 작업은 UP-TO-DATE였다.

| 지표 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 기존 테스트 | 매 실행 38개 성공 | 매 실행 38개 성공 |
| 실패·오류·스킵 | 모두 0 | 모두 0 |
| 실제 컨텍스트 | 매 실행 4개 | 매 실행 1개 |
| Spring 시작 | 매 실행 4회 | 매 실행 1회 |
| PostgreSQL 시작 | 매 실행 4회 | 매 실행 1회 |
| Flyway 적용 완료 | 매 실행 4회 | 매 실행 1회 |
| 트랜잭션 롤백 | 매 실행 38회 | 매 실행 38회 |
| Gradle 명령 전체 시간 | 20.956 / 16.578 / 15.520초 | 8.754 / 11.267 / 12.472초 |
| 시간 중앙값 | **16.578초** | **11.267초** |
| 시간 범위 | 15.520–20.956초 | 8.754–12.472초 |

이 네 클래스 범위에서 중앙값은 **5.311초, 약 32.0% 감소**했다. 같은 JVM 안에서 캐시 컨텍스트 식별자가 4개에서 1개로 줄었고, 초기화 완료 로그도 각각 3회씩 감소했다. 시간 변동은 그대로 공개한다. 전후 각 3회의 순차 측정이고 무작위 교차 실험이 아니므로 캐시·호스트 상태에 따른 차이를 통제한 정밀 추정이나 전체 CI 개선율로 해석하지 않는다.

호스트는 기존 실험과 같은 Apple M5 Pro·24 GiB·macOS 26.6.2·OpenJDK 25.0.3·Gradle 9.5.1·Docker 29.2.1 arm64다. 동일 seed 1의 실제 클래스 진입 순서는 List → Confirmation → Detail → GenerationJobList였다. 변경 후 각 실행에서 네 클래스가 같은 컨텍스트를 사용했다. 프로세스가 다른 실행 사이에서 같은 숫자가 나오는 것을 공유 증거로 사용하지 않는다.

원시 산출물은 `backend/output/ci-715/document-query-context-20261015/`에 실행별로 보존한다. `original-reports/`에는 실험 전 리포트, `before-1`–`before-3`·`after-1`–`after-3`에는 각각 `summary.json`, `console.log`, XML·HTML이 있다. 기존 결과를 덮어쓰지 않는다. 로컬 실행기는 1차 실험의 `measure.py`를 재사용하고 대상 클래스와 산출물 경로만 바꾼다.

## 격리와 순서 검증

| 실행 | 기존 테스트 수 | 롤백 | 결과 |
| --- | --- | --- | --- |
| Confirmation 단독 | 10 | 10 | 성공 |
| Detail 단독 | 9 | 9 | 성공 |
| GenerationJobList 단독 | 12 | 12 | 성공 |
| List 단독 | 7 | 7 | 성공 |
| seed 1: List → Confirmation → Detail → GenerationJobList | 38 | 38 | 성공 |
| seed 4096: GenerationJobList → Detail → List → Confirmation | 38 | 38 | 성공 |
| seed 27: Confirmation → Detail → List → GenerationJobList | 38 | 38 | 성공 |

각 실행의 실패·오류·스킵은 0이다. 결합 실행은 세 순서 모두 실제 컨텍스트·PostgreSQL·Flyway 초기화가 각각 1회였다. 전체 순열을 검사한 것은 아니며 서로 다른 세 순서에서 성공한 증거다.

기존 테스트는 조회 범위·다른 Workspace 제외·커서·마이크로초 경계·상태별 집계·재가입 중복·JPA 자동 flush·읽기 전후 snapshot 불변·DB 실패 기한 CHECK를 검증한다. 공유 후에도 본문과 assertion을 바꾸지 않았다. fixture는 INSERT RETURNING으로 얻은 ID를 사용하며 시퀀스가 특정 값에서 시작함을 요구하지 않는다. 트랜잭션 롤백과 기존 검증 성공은 이 네 클래스 범위의 격리 근거다.

XML의 테스트 식별자 38개가 전후 모두 같은지, 초기화·롤백 횟수와 컴파일 UP-TO-DATE 조건이 맞는지 별도 집계로 대조했다. 실제 소스 diff도 import를 제외한 내용이 기준 커밋과 같은지 확인했다.

## 전체 회귀

기본 실행 설정의 `integrationTest --rerun --no-build-cache --console=plain spotlessCheck`는 종료 코드 0으로 성공했다. 80개 클래스·649개 테스트가 모두 통과했고 실패·오류·스킵은 0이었다. Spotless는 선행 별도 검사에서 통과했으며 이 전체 실행에서는 UP-TO-DATE였다. 프로파일링 init script와 랜덤 seed를 적용하지 않았고 집중 실험 시간과 합산하지 않는다.

| 전체 통합 초기화 지표 | 개선안 1 이후 성공 실행 | 개선안 2 이후 성공 실행 |
| --- | --- | --- |
| 기존 테스트 수 | 649 | 649 |
| Spring 시작 완료 | 49 | 46 |
| PostgreSQL 시작 완료 | 57 | 54 |
| Flyway 적용 완료 | 127 | 124 |

대상 실험에서 관찰한 초기화 3회 감소가 전체 실행의 완료 로그에서도 확인됐다. 이번 명령 전체 시간은 192.940초, XML suite time 합계는 182.610초였다. 앞선 성공 전체 실행의 214.437초와는 각 1회 관측이며 통제된 반복 비교가 아니므로 전체 통합·CI 개선율은 계산하지 않는다. 전체 기본 실행은 TRACE·transaction DEBUG를 켜지 않았으므로 컨텍스트 ID와 rollback 지표가 비어 있는 것을 생성·롤백이 없었다고 해석하지 않는다.

`test --rerun acceptanceTest --rerun bootJar --no-build-cache --console=plain`도 종료 코드 0으로 성공했다. 단위 테스트는 184개 클래스·1,425개, 인수 테스트는 45개 클래스·561개였고 모두 실패·오류·스킵 0이다. `bootJar`도 실행됐다. 이는 회귀 확인이며 인수 테스트의 성능 개선 실험이 아니다.

원시 전체 통합 결과는 `full-integration/`, 단위·인수·빌드 결과는 `remaining-regression/`에 별도 보존했다. 1차 실험에서 발생한 Flyway 연결 timeout의 근본 원인이 이번 실험으로 해결됐다고 주장하지 않는다. 이번 2차 실험과 회귀에서는 실패가 관측되지 않았다.

## 판정

동일한 JPA 조건에서 조회 어댑터 import 차이만으로 나뉘던 컨텍스트를 정렬했고, 대상의 기존 검증과 롤백을 유지하면서 컨텍스트·DB 초기화 3회를 줄였다. 전체 통합에서도 초기화 완료 로그가 각각 3회 감소했으며 전체 회귀가 통과했으므로 개선안 2를 채택한다.

가설 1·2에 해당하는 반복 초기화 비용을 줄인 결과다. 가설 3의 캐시 퇴출이나 가설 4의 테스트 내부 대기 비용을 검증한 결과는 아니다. 캐시 용량과 테스트의 의도적인 timeout·마이그레이션 시나리오는 유지했다.

현재 Backend CI 워크플로는 pull_request 및 main/develop push에서 실행된다. 작업 브랜치 push만으로 해당 CI가 실행된다고 가정하지 않으며 이번 로컬 결과를 Actions 개선 실적으로 기록하지 않는다. 후속 CI 비교에서는 동일한 기준·캐시 조건·시간 범위로 반복 실행을 수집해야 한다.

## 프로젝트 종료 검사

Persona의 implementation/review 보고서 작성 검사는 통과했다. `npx ph workflow finish implement`는 `source-read-runtime-unavailable`로 종료 코드 1을 반환했다. profile과 변경 Java의 evidence read도 unavailable이었고 LSP는 timeout이었다. 소스 직접 검토·실제 Gradle 컴파일·집중/전체 테스트·Spotless 성공과 구분하며 Persona 종료 인증을 받았다고 주장하지 않는다. 다른 workflow 이력이나 환경을 초기화하지 않았다.
