# 문서 생성 자료 보존 정리 운영

Issue #526의 로컬 구현 기준이다. 자동 정리는 기본 비활성이며 실제 운영 버킷 삭제를 관측한 문서가 아니다.

## 적용 규칙

- FAILED Job: 마지막 실패 후 정확히 7일에 만료한다. Document가 참조하는 Job은 삭제하지 않는다.
- Transcript와 TranscriptSegment: 성공 Document, QUEUED/RUNNING Job, 기한 내 FAILED Job이 있으면 보존한다. 사용자 재시도 3회를 소진한 Job도 7일까지 보존한다.
- 모두 만료된 실패 입력: 만료 Job과 문서 미참조 성공 분류 Job을 제거한 뒤 Batch의 입력 FK를 해제하고 구간·원문을 삭제한다.
- Batch의 FAILED/NO_CONTENT 결과, 주제, 집계, 최초 종료 시각은 Job 삭제 후에도 유지한다. 해제한 transcript ID는 FK 없는 메타데이터로 중복 통지를 판정한다.
- 오디오: 업로드 완료 확인 시각에서 정확히 30일을 계산한다. 문서·원문·구간은 오디오 삭제에 따라 지우지 않는다.
- NO_CONTENT: 결과를 먼저 저장하고 입력 정리와 오디오 삭제 접수를 같은 DB 트랜잭션에서 처리한다. 파일은 접수 커밋 후 즉시 삭제를 시도한다.
- 조회 API는 삭제 작업을 수행하지 않는다. 만료 Job 재시도는 삭제 전 409, 삭제 후 404다.

## 실행과 복구

`DocumentRetentionCleanupService.cleanup`은 Workspace(삭제 이력 포함) → RecordingSession → ID순 Job → Transcript → Batch → Upload 순으로 잠근다.
기존 retry·claim·result 저장과 Workspace 잠금을 공유한다. 모든 입력/Job 참조를 바꾸는 STT 저장 코드도 이 순서를 지켜야 한다.

오디오 단독 접수는 Upload 잠금과 UNIQUE upload_id로 중복을 막는다. NO_CONTENT는 되돌아가지 않는 결과여서 조회로 재검사한다.
오디오 상태 갱신은 Upload → Task만 잠그며 이후 Workspace 잠금을 얻지 않는다. Upload 잠금을 기다린 뒤 Task를 refresh해 오래된 JPA 1차 캐시를 사용하지 않는다.
잠금 대기 한도는 현재 트랜잭션의 3초다.

`recording_audio_deletion_tasks`에는 실제 소비할 삭제 작업을 저장한다.
Worker는 짧은 트랜잭션으로 RUNNING과 attempt/lease를 기록하고, DB 트랜잭션 밖에서 저장소를 호출한 뒤 결과를 기록한다.
실패는 PENDING과 다음 시각으로 돌아간다. 재시도 간격은 1·2·4·8·16·32·60분이며 이후 60분을 유지한다.
RUNNING에서 중단된 작업은 lease 만료 후 새 attempt로 회수한다. 이전 attempt/만료 lease의 늦은 결과는 반영하지 않는다.
파일 삭제 후 완료 기록 전에 중단돼도 동일 key 삭제를 반복한다. 없는 파일의 DeleteObject 404는 성공이다.

파일 풀은 생성 Worker와 별도로 동시성 1, 대기열 0이다. 슬롯에 제출한 뒤 claim하므로 거절된 제출은 상태를 바꾸지 않는다.
한 저장소 호출의 기존 timeout은 5초, 버전 삭제 루프는 전체 60초·최대 100페이지로 제한한다.
완료 전에 시간이 초과되면 Task를 재처리하며, 기본 lease 120초보다 짧게 끝내도록 한다. lease 설정은 75초~1시간이다.

## 버전과 삭제 확정

S3 Adapter는 GetBucketVersioning으로 상태를 확인한다. 활성·중단 버킷이면 해당 key의 모든 객체 버전과 삭제 마커를 versionId로 삭제한다.
Prefix로 목록을 읽어도 key가 완전히 일치하는 항목만 삭제하고 다음 페이지를 이어 읽는다.
버전 ID 누락·알 수 없는 버전 상태·조회/삭제 권한 오류는 성공 처리하지 않는다.

NO_CONTENT와 30일 만료 모두 현재 시도의 첫 삭제가 성공하면 Task를 SUCCEEDED로 끝내고 Upload.deletedAt을 기록한다.
삭제 후에도 Upload의 COMPLETED와 최초 completedAt은 유지한다.
업로드 완료 뒤에는 PUT URL을 새로 발급하지 않는다. 완료 전에 받은 URL(기본 15분)로 삭제 뒤 같은 key에 다시 올린 파일은 버킷에 남을 수 있다.
녹음 시작자 본인 파일이며 다른 사용자에게 노출되지 않으므로 #720에서 이 위험을 받아들이고 삭제 재확인 대기를 없앴다.

## 설정과 활성화

```properties
knot.retention.enabled=false
knot.retention.candidate-limit=20
knot.retention.poll-interval=1m
knot.retention.deletion-interval=5s
knot.retention.execution-lease=2m
```

`enabled=true`로 바꾸기 전에 실제 환경에서 다음을 확인한다.

1. 운영 Flyway 이력과 V34~V36 번호 충돌, 기존 Batch 결과/참조를 확인한다. 기존 migration을 수정하지 않는다.
2. GetBucketVersioning, ListBucketVersions, DeleteObject, DeleteObjectVersion의 저장소 지원과 최소 권한을 확인한다. 설정되지 않은 저장소는 실패를 기록하며 완료로 처리하지 않는다.
3. 작은 테스트 key로 버전·마커·반복 삭제와 목록 pagination을 확인한다. 운영 파일이나 버킷 전체를 시험 삭제하지 않는다.
4. 과거 key에 적용된 bucket/prefix와 현재 설정이 같은지 확인한다. 저장소 이동 시 과거 삭제 대상 주소를 먼저 이행한다.
5. 작업 상태와 정제된 오류 코드를 관측한다. 로그에는 task/job ID와 회차만 남기고 key, 원문, 토큰, 외부 오류 본문을 남기지 않는다.

## 관측 쿼리

다음은 읽기 전용이다.

```sql
SELECT status, count(*) FROM recording_audio_deletion_tasks GROUP BY status;

SELECT id, attempt_count, next_attempt_at, execution_deadline_at, last_failure_code
FROM recording_audio_deletion_tasks
WHERE status <> 'SUCCEEDED'
ORDER BY id;

SELECT processing_status, failed_count, released_transcript_id, input_released_at
FROM document_generation_batches
WHERE input_released_at IS NOT NULL;
```

로컬 증거는 고정 Clock 경계, 실제 PostgreSQL FK/롤백/동시성, 활성 Spring 스케줄러, MockMvc 인증/CSRF/원문 조회, 모의 S3 SDK 응답이다.
실제 운영 버킷·IAM·OS 강제 종료·운영 배포 검증은 포함하지 않는다.
