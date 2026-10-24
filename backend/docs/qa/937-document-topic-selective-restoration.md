# #937 직전 주제 정리 되돌리기 검증

확인일: 2026-10-24 KST. 브랜치: `be/feature/#937`, 기반 develop `45d6b9999` (#934~#936 병합 포함).
대상은 복원 API와 복원 실행이며 운영 배포·운영 DB 복구 작업은 수행하지 않았다.

## 구현과 계약

- `POST /api/v1/workspaces/{workspaceId}/document-topics/restorations`에서 OWNER·인증·CSRF·strict UUID requestId·양수 Long versionId를 검증한다.
- 신규202·재전송200, 공개 Long 식별자와 RESTORATION 타입 및6필드를 유지한다. 같은 요청은 소진 후에도 기존 작업을 반환한다.
- 직전 재정리의 changed 문서만 고정한다. expected/restored 배정을 따로 저장하고 적용 직전에 기대 배정·버전·claim을 확인한다.
- LEGACY·FALLBACK·원래 source/rule을 복원한다. 내용·안건·확인·보관 상태와 이후 문서를 덮어쓰지 않는다.
- 문서 배정·영향 주제의 가용성·복원 정보 소진·SUCCEEDED·임시 자료 정리는 한 트랜잭션이다. requestId와 대상 버전 기록은 유지한다.
- 복원은 LLM을 사용하지 않는다. 준비 체크포인트와 APPLYING 재인계는 #936의90초 lease·5초 대기1회 재시도·최초30분 기한을 따른다.

## 테스트를 먼저 실행한 기록

1. 배정 값·Document 복원·RESTORE factory 테스트를 먼저 작성했다. 미구현 심볼로14건의 컴파일 오류를 확인한 후 구현했고 도메인 테스트가 통과했다.
2. 복원 접수·실행 통합 테스트를 먼저 작성해 미구현 서비스 오류를 확인한 후 migration/adapter/서비스를 구현했다.
3. 실제 Security HTTP 인수를 먼저 작성했다. 경로가 없어서16개 중15개 실패, 연결 후 모두 통과했다. 초기 성공1개는 엔드포인트가 없어도 기존 인증 경계가 거절하는 사례였으며 구현 성공으로 해석하지 않았다.
4. 첫 통합 실행의2개 실패는 fixture 본문과 기존 권한 오류 문구를 잘못 기대한 테스트였다. 실제 기존 값을 확인해 수정했다. 새 Scheduler 의존성을 추가하면서 수동 구성 테스트에도 복원 워커를 등록했다.

5. 마지막 명세 대조에서 접수 자료 오류가 내부 코드로 응답되는 것을 발견했다. 명세의 INTERNAL_SERVER_ERROR를 기대하는 인수를 먼저 추가해 실패를 재현하고, 복원 Controller에만 적용되는 scoped advice에서 기존 공통 handler로 변환했다. 기존409 등 비즈니스 오류는 원래 handler에 위임한다.

## 검증 시나리오

| 범위 | 실제 확인한 내용 | 결과 |
| --- | --- | --- |
| 순수 도메인 | LEGACY·BROAD·FALLBACK, 잘못된 튜플/Workspace, 원래 source/rule, 보관 상태, 버전 불변 | 통과 |
| 접수 서비스 | 권한 후 replay 선행, 활성 작업/버전/가용성 검사 순서, MICRO 시각, 대상 수 불일치 | 통과 |
| PostgreSQL 접수 | 같은 UUID 동시 접수, 버전·종류 충돌, snapshot 저장 실패 rollback, 성공 소진 후 재전송 | 통과 |
| 고정 대상 | 변경3개·미변경1개·이후1개, 같은 생성 시각의 후속 문서 | changed3개만 복원 |
| 주제 | 원래 후보 활성화, 생성 주제의 외부 참조 보존, 참조 없는 생성 주제 퇴역, 후속 무관 주제/기타 유지 | 통과 |
| 데이터 보호 | 외부 배정 변경 시 미적용, 원본 수 불일치·복원 포인터 누락, 다른 Workspace FK | 통과 |
| 취소·경합 | 부분 준비 취소, 구 token 폐기, 양쪽 commit 순서를 다른 DB 연결로 실행, 완료 취소 재요청 | 취소 선행 미적용, APPLYING 선행409 |
| 중단·시간 | 준비/적용 lease 재인계, 5초 경계, 두 번째 실패,30분 기한, OWNER 탈퇴·Workspace 삭제 | 공통 상태 정책 유지 |
| 저장 실패 | 문서 UPDATE·포인터 소진 DELETE·복원/원본 임시 자료 DELETE 오류 주입 | 배정·성공·소진 함께 rollback |
| 취소 정리 실패 | 실제 trigger로 DELETE 실패 | CANCEL_REQUESTED와 자료 유지, 재실행 완료 |
| HTTP |6필드·202/200, UUID/Long 범위, JSON,401/403/404/409, 내부 자료 오류의 공통500·rollback, CSRF, OpenAPI | 통과 |
| 선행 회귀 | #934 재정리·#935 상태 snapshot·#936 취소와 신규 문서 카탈로그 재검증 | 전체 check 결과에서 확인 |

동시성 테스트는 TransactionTemplate·별도 연결·CountDownLatch·bounded timeout을 사용한다. Mock만으로 SQL 잠금이나 rollback을 증명하지 않았다. 복원 중 후속 문서 참조 계산은 적용 시점의 실제 DB를 읽는다.

## 실제 Docker HTTP 검증

별도 프로젝트 `knot-restore-937`, `pgvector/pgvector:pg18`, Java25 실행 이미지, 루프백 `127.0.0.1:18937`을 사용했다. Flyway V67까지 적용하고 Hibernate validate로 앱을 실행했다. 아래 초기8개 시나리오는 로컬 앱·DB·HTTP 스텁으로 수행했다. 실제 모델 연동은 다음 절에 별도로 기록한다. 운영 앱·운영 DB는 사용하지 않았다.

재정리 연결만 정상 OpenAI 호환 HTTP LLM fixture를 사용한다. 복원 단계에서는 공급자 fixture가503을 반환하도록 설정했다. 모델 작성 품질을 평가한 실행이 아니다. 쿠키 인증·실제 CSRF·Controller·Service·JPA/JDBC·스케줄러를 거치는 실제 HTTP 요청이다.

| 시나리오 | 관측 |
| --- | --- |
| 재정리→새 문서→복원→같은 요청 | 원래5개만 LEGACY 복원, 이후1개 배정·주제 유지. 소진 후에도 같은 요청200·최초 시각 동일 |
|1,000개 준비 중 취소 | 부분 준비 후 CANCEL_REQUESTED→CANCELLED, 문서 배정 미변경·복원 기회 유지·복원 임시 자료 제거 |
| 준비 체크포인트 후 SIGKILL |100개 중 일부만 준비한 뒤 앱을 강제 종료. 재시작 뒤 저장된 진척에서 이어서100개 성공, 중복 집계 없음 |
| 적용 저장 오류 두 번 | 실제 UPDATE trigger로 오류를 주입. retry_count1 뒤 FAILED·안전한 안내, 분류·복원본 유지. trigger 제거 후 새 요청 성공 |
| APPLYING SQL 도중 SIGKILL | 문서 UPDATE를 지연하고 앱을 강제 종료. 미커밋 배정과 소진 rollback. DB 시각을 앞당기지 않고 실제90초 lease 만료 후 재인계·성공 |
| 취소 정리 SQL 도중 SIGKILL | DELETE 지연 도중 강제 종료. CANCEL_REQUESTED·임시 자료·복원본 보존. 재시작 뒤 CANCELLED, 최초 취소 시각과 복원 기회 유지 |
| 최종 JAR의 내부 자료 오류 | 실제 HTTP에서 공통500과 접수 rollback·복원본 유지 확인. 자료 복구 뒤 정상 복원도 성공 |

최초 추가 취소 실험의 지연 trigger는20초를 행마다 적용해1,000개 행에서 실험 대기가 끝나지 않았다. 이 프로젝트의 지연 쿼리만 취소하고 statement 단위 단일 지연으로 수정한 뒤 같은 시나리오를 다시 통과했다. 이 실험 설정 오류를 제품 버그나 제품 복구 성능으로 해석하지 않는다.

적용 중 강제 종료와 저장 오류 주입은 실제 앱/DB 동작이다. 모든 OS·DB·네트워크 장애에서 무조건 즉시 완료됨을 보장하는 증거는 아니다. DB나 서버가 계속 중지된 동안에는 완료할 수 없고 기존 자료를 보존한다.

## 실제 LM Studio 추가 흐름 검증

사용자가 제공한 설정으로 실제 LM Studio의 `qwen3.8-27b`를 호출했다. `/v1/models`에서 HTTP200과 해당 모델 ID를 확인했다. 로컬 전용 환경 파일은 권한600·Git 제외로 두고 토큰은 문서·PR·출력에 기록하지 않았다. 검색 워커·에이전트도 제공된 설정으로 시작했지만 이번 대상은 #934~#937의 주제 재정리·조회·복원·취소 흐름이며, 음성 STT나 새 문서 본문 생성 품질을 검증한 실험은 아니다.

별도 Docker 프로젝트 `knot-live-937`, 로컬 DB, 최종 검증된 JAR와 합성 문서8개를 사용했다. 재정리 batchSize8·polling1초에서 실제 모델의 BATCH와 CONSOLIDATE 응답을 기존 parser·validator·저장 로직으로 처리했다. 마지막 전체 check에 사용한 JAR SHA-256과 동일함을 확인했다.

| 문서 내용 | 실제 큰 주제 | 관측 |
| --- | --- | --- |
| 오디오 업로드·발언 화자/시각·녹음 연결 복구3개 | 녹음·전사 | 기존 주제 재사용 |
| 무중단 배포·배포 후 모니터링2개 | 운영·배포 | 기존 주제 재사용 |
| 실패 문구·알림 채널 비교2개 | 알림·통지 | 새 주제1개 생성 |
| 노션 문서 이전1개 | 문서 관리·이전 | 새 주제1개 생성 |

재정리를2회 관측해 각각21.831초·20.672초가 걸렸고 두 번 모두 위4개 그룹으로 배정됐다. 첫 실행은 이후 실험의 metrics 조회가 localhost 전용 보안 규칙에403으로 거절돼 중단했으며, 제품 보안을 변경하지 않고 앱 네트워크 내부의 loopback에서 지표를 읽도록 실험을 고쳤다. 두 번째 실행에서 아래 전체 흐름이 통과했다.

1. 실제 모델 재정리 성공, 상태 조회·동일 requestId 재전송200, 기존 주제 재사용·새 주제 생성 확인.
2. 새 문서1개를 실제 모델이 만든 주제에 배정하고, 오래된 versionId409·복원 접수202·같은 요청200 확인.
3. 원래8개 중 실제 변경된5개만 복원. 원래8개 전체 DB 행이 재정리 전과 일치하고, 확인 기록·ARCHIVED·후속9번 문서의 행/참조 주제도 유지됨을 확인.
4. 복원 성공 후 버전 소진·canRestore=false, 같은 요청200과 기존 버전 유지 확인. 복원 전후 재정리 워커 지표는 증가하지 않았다. 지표는 워커 단계 횟수이며 공급자의 실제 요청 수와 동일한 지표로 해석하지 않는다.
5. 다음 재정리가 실제 모델을 처리 중일 때 다른 시작 요청409·취소202·CANCELLED 확인. 늦은 응답을 discarded로 처리한 이후에도 전체 문서 배정과 복원 정보가 변하지 않음을 확인.

이번 실제 모델 관측은8개 자료의 기능 연결과 제한된 그룹 품질 확인이다. **1,000개 실제 LLM 재정리의5분 목표를 검증한 결과가 아니다.** 앞 절의1,000개41.941초는 LLM을 사용하지 않는 복원 측정이다.

실제 모델의 원시 결과는 ignored `live-result.json`, 재실행 스크립트와 환경은 같은 로컬 전용 디렉터리에 보존한다. 실험 뒤 전용 Docker 자원은 종료한다.

## 최종 검증 결과

최종 코드로 Java25에서 `./gradlew clean spotlessCheck check bootJar`를 단일 실행해 **BUILD SUCCESSFUL,5분11초**를 확인했다. 전체 **3,705개, 실패·오류0, 기존 skip6**이다. 기반 #936 대비62개 검증이 추가됐다. 테스트 비활성화는 추가하지 않았다.

| 검증 | 테스트 수 | 실패·오류 | skip |
| --- | ---: | ---: | ---: |
| 단위 |2,094 |0 |2 |
| PostgreSQL 통합 |967 |0 |4 |
| 실제 Security HTTP 인수 |644 |0 |0 |
| 전체 |3,705 |0 |6 |

최종 HTTP500 수정 전에 전체3,704개가5분35초에 통과했고, 확인 기록 보존 assertion을 강화한 복원 통합29개와500 수정 후4개 API 인수도 각각 통과했다. 마지막 표는 그 후 전체를 다시 실행한 최종 결과다.

`git diff --check`와 문서 경로 하네스5개도 통과했다. 최종 XML 집계와 JAR SHA-256은 ignored `verification.json`에 보관했다.

HTTP503을 실제 probe로 확인한 뒤 batch25·polling1초에서1,000개를 복원했다. 41.941초에 SUCCEEDED, processedDocumentCount=1,000, LEGACY 복원1,000개·복원 포인터0을 확인했다. 공급자 전체 요청 수는 probe1회에서 변하지 않았다(requests1→1, rejected1→1). 따라서 복원 실행 중 공급자 요청은0회다. 로컬1회 측정이며 운영 p95나 부하 SLA가 아니다.

별도 Docker 프로젝트는 검증 뒤 down했다. 기존 사용자 컨테이너는 유지했다. 원시 결과8개 시나리오는 ignored JSON에 남기고 이 문서에는 결과와 한계만 기록한다.

## 보관과 배포 확인

- [개발 계획](../implement-plan/937-document-topic-selective-restoration.md), [Proposed ADR](../adr/937-document-topic-selective-restoration.md).
- 로컬 compose·스텁·실행 스크립트·JSON은 `backend/ops/document-topic-local/937/`에 두고 기존 `.gitignore`의 `/ops/document-topic-local/` 규칙으로 제외한다. 파일별 `git check-ignore`로 확인했다. 공용 Docker 설정은 제외하지 않았다.
- 실제 운영의 과거/수동 RESTORE 행은 관측하지 않았다. V67 적용 전에 기존 `kind='RESTORE'` 행이 있는지 읽기 조회로 확인해야 하며, 존재하면 version/source를 추정해서 채우지 않는다. 현재 API에선 최초 도입이고 테스트의 기존 raw RESTORE fixture는 새 메타데이터 제약에 맞췄다.
- 원래 Category 이름/설명은 불변이라는 전제에서 원래 ID를 재사용한다. 향후 이름 수정/물리 삭제 기능이 생기면 별도 재검토한다.
- 구현·검증 후 사용자의 별도 요청으로 같은 브랜치의 commit·push·PR을 진행한다. Notion 문서 수정과 merge는 수행하지 않는다.
