# #767 녹음 오디오 최종화 검증

종료된 PCM 녹음은 DB 작업으로 복구하고, 조각의 정확한 S3 버전을 순서대로 읽어 WAV를 만든다. 검증한 최종 WAV 채택과 전사 QUEUED 등록을 같은 트랜잭션에서 수행한다.

## 실행과 복구

- `recording.audio-finalization.enabled`의 기본값은 false다. 활성화 시 1초마다 작업을 찾고 프로세스당 실행자 하나가 처리한다. 실제 조립은 Spring 공용 스케줄러를 점유하지 않는 전용 스레드에서 실행한다.
- claim마다 ownerToken·claimEpoch·60초 lease를 부여한다. 별도 스레드가 15초마다 갱신하므로 최대 2분의 S3 요청 중에도 lease를 유지한다.
- NORMAL seal은 모든 조각이 검증되면 즉시 확정한다. REMOTE/TIMEOUT/LIMIT seal은 acceptUntil까지 기다린다. 누락이 있으면 acceptUntil 이후 시작한 HEAD 묶음으로 다시 확인한 뒤 확정한다.
- DB를 다시 잠그고 VERIFIED를 읽으므로 과거 HEAD 404가 나중에 commit된 ACK를 지우지 않는다. manifest 확정 뒤 재시도는 예약 객체 HEAD를 반복하지 않고 같은 exact-version manifest를 사용한다.
- checksum·크기·버전 불일치는 영구 실패다. S3 접근 거절은 `AUDIO_STORAGE_ACCESS_DENIED`, 내용 없음은 `AUDIO_NO_CONTENT`로 재시도를 막는다. 일시 실패는 1/2/4초 뒤 최대 3회 자동 재시도하고, 소진하면 같은 job의 수동 재시도를 허용한다.
- 재시도·갱신·확정·실패·최종 채택은 현재 Workspace 소속, 폐기 여부, endedAt+30일, ownerToken/claimEpoch/lease를 다시 검사한다. 최종 객체가 존재해도 같은 manifest로 계산한 길이·checksum과 비교한다.

## 자원 한도

16kHz·mono·PCM16LE의 44바이트 WAV 헤더를 사용한다. 2시간 데이터 230,400,000바이트와 헤더 44바이트를 heap에 모으지 않고 16KiB 버퍼로 순차 처리한다. 시작 전 512MiB 여유 디스크를 확인한다. 실제 조각만 checksum과 정확한 길이를 검증하며, 고정 manifest의 알려진 누락 frames만 무음으로 채운다.

임시 파일은 `knot-recording-wav-<pid>-<processStartEpochMillis>-<UUID>.tmp` 형식의 0600 파일이다. 성공·실패·lease 상실 후 본인이 만든 파일만 삭제한다. 프로세스 종료 뒤 orphan 파일의 소유권 확인과 시작 시 정리는 #768에서 담당한다.

## 공개 결과

- GET audio-save는 job ID, FINALIZING/READY/FAILED, COMPLETE/PARTIAL, 누락 범위와 retry 가능 여부를 반환한다.
- POST audio-save/retry는 새 접수 202, 이미 대기·처리 중인 같은 job이면 200이다. 현재 소유자·CSRF 검증을 유지한다.
- 녹음 상세와 문서 상세의 `sourceAudio`에 품질·누락 범위·tailUnknown을 연결한다. 같은 원본 품질을 문서마다 DB에 복제하지 않는다. 오디오 보관 종료로 job이 폐기돼도 이미 채택한 원본의 PARTIAL 경고는 보존한다.
- legacy WebM의 예약·완료 제한은 유지하며 PCM 모드 결과를 legacy 경로에서 교체할 수 없다.

## 검증

2026-10-18 실행 결과:

- `./gradlew spotlessApply test integrationTest acceptanceTest bootJar`: 성공. unit 1,668개, PostgreSQL 통합 749개(통과 748·기존 실제 S3 opt-in 1개 skip), HTTP acceptance 581개, 합계 2,998개다.
- 추가 `./gradlew integrationTest --tests '*RecordingIncrementalPipelineIntegrationTest' spotlessCheck`: 3개 모두 통과. 정상·중간 누락·최종 PUT 뒤 재시작 재선점에서 실제 PCM 조립, PostgreSQL 저장, 전사 실행자와 문서 접수까지 연결한다. 마지막 경우 두 번째 PUT 없이 같은 객체를 채택하고 오래된 실행자의 채택을 거절한다.
- 별도 96MiB heap 제한에서 2시간 WAV 230,400,044바이트 조립 통과(해당 테스트 0.867초). 전체 파일을 heap에 올리는 구현으로는 통과할 수 없는 자원 검증이다.
- 저장소 Governance Python 단위 테스트 10개 통과. 전체 컨벤션 감사에는 기존 파일 진단 1,276개가 남아 미통과이며 신규 Java 파일 진단은 0개다. 전체 감사 통과를 주장하지 않는다.
- AI 서버 기존 검증은 120개 중 119개 통과·실제 모델 opt-in 1개 skip, Spotless·bootJar 통과다. 실제 S3 WAV를 별도 실행 AI에 전달한 사전 검증도 성공했다.

통합 테스트는 S3·AI 포트를 대역으로 사용한다. 실제 모델 사전 검증과 연결 테스트는 각각의 증거이며 제품 FE·실제 배포 환경의 전체 흐름 검증을 뜻하지 않는다. 신규 PCM과 최종화 설정은 기본 비활성으로 유지하고, FE 연결·AI WAV 호환 배포·#768 정리 검증 후 활성화한다.
