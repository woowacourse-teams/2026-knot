# 녹음 중 구간 전사 운영

PCM 녹음을 30초 core와 앞뒤 최대 5초 문맥으로 전사하고, 종료 후 남은 구간을 합쳐 기존 최종 전사·문서 생성 경로에 전달한다. FE endpoint와 payload는 유지한다. 이 문서는 로컬 구현의 실행 계약이며 운영 활성화·화자 품질·종료 후 30초 달성을 확인한 배포 기록이 아니다.

## 활성화와 복귀

`recording.audio-chunks.incremental-enabled`의 기본값은 false다. `RECORDING_INCREMENTAL_TRANSCRIPTION_ENABLED=true`로 설정하면 이후 시작하는 PCM 세션에 `pcm-window-v1-30-5`를 고정한다. 기존 PCM 저장·최종화 설정과 `knot.transcription.enabled`도 별도로 필요하다. 활성화 이후 새로 전사 접수되는 비구간 세션은 원본 업로드를 보존하고 준비 WAV를 만들어 `prepared-window-v1-30-5`로 처리한다. 이미 접수된 전체 전사는 기존 방식으로 완료한다.

1. AI의 선택적 `window` 요청과 화자 증거 반환, `/api/v1/audio-preparations`를 먼저 배포한다. runner profile의 `pythonBinary`, `sherpaLibrary`와 실제 모델을 확인한다. C API 구조 검증은 sherpa 1.13.6 기준이다.
2. BE V59·V60·V61과 코드를 flag false로 배포한다. V60은 세션 pipeline, 최근 선점 시각과 구간 PROCESSING 전역 유일성을 추가한다. V61은 원본 버전과 준비 WAV, 복구 소유권을 저장한다.
3. 실제 Chrome·저장소·AI·DB 환경에서 동시 녹음 2개의 종료 시간을 검증한다. 긴 전체 전사 작업이 슬롯을 점유하는 경우도 따로 기록한다.
4. 검증 후 신규 녹음에 flag를 적용한다. 다시 false로 바꿔도 이미 pipeline이 고정된 세션은 구간 경로를 끝까지 수행한다. 진행 중인 구간 세션이 있으면 구간을 모르는 구버전 BE로 즉시 되돌리지 않는다.

처음 활성화하기 전에 기존 전체 전사 대기열과 실행 작업이 소진됐는지 확인한다. 이미 실행 중인 작업을 강제 중단하지 않는다. 이후 접수되는 긴 파일은 추론 슬롯 밖에서 변환하고 짧은 구간으로 처리하므로 파일 전체가 추론 슬롯을 독점하지 않는다. 변환과 추론이 CPU를 공유하는 영향 및 최종 30초 목표는 실제 환경에서 측정한다.

## 실행과 복구

- 매초 스케줄러가 진행 가능한 세션과 작업을 다시 찾는다. 녹음 중에는 연속 VERIFIED 입력만 사용하며, 종료 후에는 확정 manifest에서 tail을 만든다.
- legacy와 구간은 같은 DB admission 잠금을 사용한다. 구간 결과가 DB에 저장되기 전 다음 실행을 허용하지 않는다. 복구 중인 작업이 우선이며 PCM 작업을 두 번 배정하면 다음에는 준비된 파일 작업을 우선한다. 각 종류 안에서는 녹음별 최근 선점 시각으로 순환하고 동률에서는 종료된 녹음을 먼저 처리한다. 한 종류만 있으면 계속 처리한다.
- 준비 작업은 별도 단일 변환 슬롯을 사용한다. 원본의 정확한 S3 버전을 조회하고 canonical 16 kHz mono PCM16 WAV 전체 길이·헤더·SHA256을 검증한 뒤 `recordings/{id}/prepared/{attemptId}/{sha256}.wav`에 조건부 저장한다. 준비 결과가 DB에 채택되기 전 다음 준비 작업을 시작하지 않는다. 자동 복구는 동일 attempt와 최초 15분 기한을 유지하며 명시적 재시도만 새 기한을 만든다.
- AI 조회를 먼저 실행한다. 404인 경우에만 같은 attempt와 입력으로 다시 접수한다. 조회 중 timeout·NOT_READY는 성공 여부가 불확실하므로 슬롯을 유지한다. POST의 명시적인 BUSY·NOT_READY 거절은 2초 뒤 재접수한다.
- 자동 재선점은 같은 attempt와 최초 5분 실행 기한을 유지한다. owner·epoch·lease가 다른 응답은 저장하지 않는다. 네트워크 호출은 DB 트랜잭션 밖에서 수행한다.
- 사용자의 기존 전사 재시도 API는 실패 구간만 새 attempt로 접수하고 성공 구간은 재사용한다. 명시적 새 시도에는 새 5분 기한이 적용된다. 병합 근거가 서로 호환되지 않아 `INCREMENTAL_MERGE_FAILED`가 된 경우에는 성공 구간도 새로 처리한다. 원본 30일 보관 기한은 연장하지 않는다.

## 입력·출력 보존

입력은 정확한 S3 version·checksum·frame 범위로 고정한다. 구간 WAV는 `recordings/{id}/windows/{manifestSha256}.wav`에 조건부 저장한다. RAW가 정리된 뒤에는 확정된 최종 WAV의 같은 version·byte range를 읽는다. 임시 WAV는 기존 PID·프로세스 시작 시각 기반 정리 규약을 따른다.

AI는 입력 hash·pipeline·model·frame과 단어 시각·화자 embedding을 반환한다. BE는 입력 identity를 검증한 뒤 구간 결과를 최대 1MiB JSON으로 저장한다. 병합은 단어 중심 시각으로 core 소유권을 정하고, 실제 embedding의 cosine 유사도로 구간 간 화자를 연결한다. 화자를 정하지 못한 단어도 보존하며 최종 `speakerNumber`는 null이다. 유사도 0.65는 구현값이며 검수된 화자 정확도 보장이 아니다.

모든 구간과 tail이 성공하고 PCM 최종 오디오 또는 준비 WAV가 READY일 때만 전체 전사·발화·문서 접수를 같은 트랜잭션으로 저장한다. 무음 전체는 기존 `NO_CONTENT` 실패로 처리한다. ALL 정리는 준비 작업과 구간을 취소하고 결과·embedding을 지우며 전체 recording prefix를 반복 회수한다. RAW 정리는 chunks만 삭제한다.

## 점검 쿼리

음성·전사 원문·embedding 없이 상태와 지연을 확인한다.

```sql
SELECT status, count(*) AS windows, min(next_run_at) AS oldest_due,
       min(execution_deadline) AS earliest_deadline
FROM recording_transcription_windows
GROUP BY status;

SELECT recording_id, count(*) FILTER (WHERE status = 'SUCCEEDED') AS completed,
       count(*) FILTER (WHERE status = 'FAILED') AS failed,
       max(core_end_frame) FILTER (WHERE status = 'SUCCEEDED') / 16000.0 AS latest_core_seconds
FROM recording_transcription_windows
GROUP BY recording_id;
```

`latest_core_seconds`는 연속 완료 보장이 없는 내부 관측값이다. FE 저장 ACK나 사용자 전사 진행률로 전달하지 않는다. 30초 목표는 종료 클릭부터 FE가 최종 전사 성공을 관측할 때까지 별도 측정하며 요약 문서 생성 시간을 포함하지 않는다.
