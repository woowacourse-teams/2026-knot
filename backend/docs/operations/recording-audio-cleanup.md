# PCM 녹음 오디오 정리

PCM 녹음은 시작 트랜잭션에서 독립 tombstone을 만든다. 부모 녹음·워크스페이스가 물리적으로 사라져도 `recording_audio_cleanup`에는 녹음 ID와 서버 소유 prefix가 남는다. 이 표에는 의도적으로 부모 FK가 없다. 기존 WebM 삭제·문서 보관 규칙을 대체하지 않는다.

## 삭제 범위

| scope | 선정 조건 | 객체 범위 |
| --- | --- | --- |
| DORMANT | 최종화 전·복구 가능한 실패 | 삭제 없음 |
| RAW | 최종 WAV 검증·DB 채택으로 READY 확정 | `recordings/{id}/chunks/` |
| ALL | 폐기·소속 상실·워크스페이스 삭제·내용 없음·보관 만료 | `recordings/{id}/` |

S3 설정의 기존 key prefix를 앞에 붙인다. 서버가 만드는 양수 ID와 고정 경로 외 입력은 거절한다. 다른 녹음 ID와 모호한 prefix를 삭제하지 않는다.

최종 WAV 보관은 기존 완료 시각부터 30일이다. 완료되지 못한 녹음은 논리적 endedAt부터 30일이 복구 상한이며 retry로 연장하지 않는다. 실패 음원 30일은 사용자에게 위임받은 이번 설계값이다. 전사 텍스트·생성 문서 보관 기간은 변경하지 않는다.

ALL 전환은 Workspace → session → stream → finalization → tombstone 순서의 잠금에서 stream을 freeze하고 미완료 job의 소유권·retry를 닫는다. READY job은 이미 실행 소유권과 retry가 없으므로 상태·manifest·품질을 보존한다. 정상 보관 만료가 문서의 기존 PARTIAL 원본 누락 경고를 지우지 않는다. 외부 S3 작업은 DB 잠금 밖에서 수행한다.

## 반복 삭제와 장애

기존 retention scheduler·executor·audio deletion worker가 tombstone을 실행한다. versioned bucket의 실제 object version과 delete marker를 각각 삭제하고 남은 첫 페이지를 다시 조회한다. 100페이지 또는 60초의 실행 한계를 넘으면 다음 실행으로 넘긴다. list/delete의 403·5xx는 성공으로 확정하지 않는다.

살아 있는 presigned URL과 진행 중 PUT의 완료 시각을 증명할 수 없으므로 한 번 비어도 tombstone을 없애거나 최종 완료 상태로 만들지 않는다. URL 만료 전·저장소 오류 후에는 1분 뒤, 만료 후 성공 sweep 뒤에는 1시간 뒤 재확인한다. 이후 늦게 생성된 raw·orphan final·추가 version도 같은 소유 prefix에서 회수한다. `max_url_expires_at`은 그 시점에 DB가 아는 최대 URL 만료다. 부모 삭제로 일부 메타데이터가 없어도 반복 조회는 유지한다.

lease와 claim epoch는 동시 실행과 이전 실행의 늦은 완료를 격리한다. RAW 실행 중 ALL로 승격되면 기존 RAW 완료 ACK가 ALL의 상태를 덮어쓰지 않는다. 프로세스 재시작은 DB의 만료 lease를 다시 선점해 복구한다.

보관 기간은 삭제 대상 선정 시점이다. 저장소 장애가 있어도 정확한 시각에 물리 삭제가 끝난다는 SLA로 해석하지 않는다. 운영자는 아래 항목을 확인한다.

```sql
SELECT scope, count(*) AS tasks, min(next_attempt_at) AS oldest_next_attempt,
       count(*) FILTER (WHERE last_failure_code IS NOT NULL) AS failed_tasks
FROM recording_audio_cleanup
GROUP BY scope;
```

## 임시 WAV 재시작 정리

새 assembler는 `knot-recording-wav-{pid}-{processStartEpochMillis}-{UUID}.tmp`를 0600으로 원자 생성한다. 정상·실패 경로에서는 해당 실행이 만든 파일만 finally로 제거한다. 재시작 정리는 같은 사용자 소유의 일반 파일·정확한 파일명·0600 권한·NOFOLLOW를 확인하고, PID가 없거나 현재 PID의 확인 가능한 시작 시각이 다른 경우만 제거한다. 살아 있는 다른 실행, 시작 시각을 모르는 프로세스, 심볼릭 링크, 형식이 다른 구버전 파일은 보존한다.

## 검증과 배포 경계

실제 PostgreSQL 테스트는 READY 전 raw 보존, 30일 정확한 경계, 부모 삭제 뒤 tombstone 생존, 소속 상실과 freeze/job 종료, 동시 claim, scope 승격과 stale ACK, 저장소 실패 후 반복 sweep를 검증한다. S3 stub은 모든 version·marker 삭제와 prefix 격리를 검증한다. 임시 파일 테스트는 활성 PID·이전 PID·재사용 PID·소유자·권한·링크를 구분한다.

이 구현 단계에서 실제 사용자 객체를 삭제하지 않았다. 신규 PCM 시작은 전체 기능·FE 통합 검증 전까지 기본 비활성을 유지한다. 실제 버킷의 전용 테스트 prefix 정리 검증과 운영 환경 권한 확인은 배포 전 통합 검증에서 수행한다.

실행 설정은 기존 `knot.retention.enabled`, `knot.retention.poll-interval`(기본 1분), `knot.retention.deletion-interval`(기본 5초), `knot.retention.candidate-limit`(기본 20), `knot.retention.execution-lease`(기본 2분)를 공유한다. 별도 정리 executor나 새 스토리지 서비스는 추가하지 않았다. 재시작 임시 파일 정리는 retention flag와 무관하게 ApplicationReady에서 소유권 증거가 있는 이전 프로세스 파일만 처리한다.

ALL 전환에서는 완료 upload도 `revoked_at`으로 영구 철회한다. upload 행 잠금을 잡아 기존 물리 삭제 worker와 상태 갱신을 직렬화한다. `isAudioAvailable` 공통 조건이 철회된 upload를 제외하므로 탈퇴 후 재가입해도 전사 retry·외부 전사 POST 준비·늦은 AI 결과 채택이 다시 허용되지 않는다. `deleted_at`은 S3 물리 삭제 확인 전에는 변경하지 않는다. READY job의 품질 이력과 오디오 접근 권한은 별개다.
