# #765 녹음 종료와 오디오 범위 확정

이 문서는 #765 범위의 검증 스냅샷이다. 현재 HTTP 정본은 [녹음 종료 API](../api/recording-end.md)이며, #767 이후 반복 종료 요약은 현재 최종화 job 상태도 반영한다.

종료는 캡처만 끝낸다. PCM의 descriptor seal과 최종 WAV manifest는 별개이며, 이 변경은 descriptor seal과 클라이언트 저장 확인 유예를 구현한다. 신규 PCM 시작은 기본 비활성을 유지한다.

## HTTP 계약

`POST /api/v1/workspaces/{workspaceId}/recordings/{recordingId}/end`

- 본문 없음: 인증된 본인·현재 워크스페이스 소속을 확인하고 원격 종료한다. 기존 JSON 제어 증명 요청도 유지한다.
- 정상 flush: `tabId`, `controlToken`, `audioSeal`을 보낸다. `audioSeal`은 `lastSequence`, `totalFrameCount`, `pendingDescriptors`다. descriptor 형식은 조각 예약과 같다.
- 정상 flush의 최초 탭 증명·미등록 연속 tail 예약·번호/총 frames 검증·ENDED·seal을 한 트랜잭션에서 확정한다. 잘못된 seal은 tail까지 모두 rollback한다.
- 동일 descriptor 집합·번호·frames의 재시도는 원래 seal과 시각을 반환한다. 원격·timeout·상한 종료 후 정상 seal을 추가하거나 변경할 수 없다.
- PCM 응답의 `audioSave`는 `audioSaveStatus=DRAINING`, `expectedLastSequence`, `totalFrameCount`, `tailUnknown`, `acceptUntil` 요약이다. 기존 WebM 응답에는 이 필드가 없다. DRAINING은 READY나 전사 성공이 아니다.
- 0frame 정상 종료는 마지막 번호 0·frames 0·빈 descriptor 목록이다. PAUSED 시간은 samples에 더하지 않는다. 1frame도 허용한다.

## 시간·잠금 경계

서버 Clock에서 timeout 30초·샘플/녹음 시간 2시간 상한을 유지한다. 논리적 `endedAt + 120초`를 `acceptUntil`로 고정한다. worker 감지가 늦어도 시한을 늘리지 않는다. stream은 NORMAL/REMOTE/TIMEOUT/LIMIT 종료 종류를 저장한다. NORMAL만 `tailUnknown=false`다.

종료 뒤 새 descriptor는 거절한다. 이미 등록한 동일 예약의 URL 갱신과 새 저장 확인은 `now < acceptUntil`에서만 허용한다. URL 만료는 시한을 넘지 않는다. 이미 VERIFIED인 동일 결과 조회는 cutoff/freeze 뒤에도 권한을 다시 확인한 뒤 멱등 응답한다. 외부 HEAD 뒤 commit 직전에 cutoff·권한·freeze를 다시 검사한다.

Workspace → session → stream 순서로 잠근다. 종료와 예약의 경합에서 먼저 commit된 예약만 원격 seal에 들어간다. S3 I/O는 행 잠금 밖에서 실행한다. chunk 요청은 heartbeat를 갱신하지 않는다. GET도 상태를 바꾸지 않는다.

만료 상태를 갱신한 뒤 요청을 거절하는 경로는 그 논리적 종료와 seal을 commit한다. 정상 tail 등록은 별도 end 트랜잭션이므로 일반 seal 검증 실패에서 rollback된다.

## 후속 최종화 인계

`RecordingAudioStreamRepository.findSealedRecordingIds(limit)`는 sealed·미freeze 스트림의 recording ID를 `sealedAt`, `recordingId` 순서로 반환한다. 최종화 worker는 각 ID의 Workspace/session/stream 잠금을 다시 얻고 현재 권한·폐기 상태·seal을 읽어야 한다. 조회 결과만으로 작업 소유권을 주장하지 않는다.

stream getter: `sealKind`, `sealedAt`, `acceptUntil`, `tailUnknown`, `lastSequence`, `totalFrameCount`, `frozen`. `seal`은 최초 확정 이후 시각을 변경하지 않는다. `freeze`는 최종 manifest 계약의 별도 경계다.

V55는 seal 컬럼·정합 CHECK·sealed 후보 인덱스를 추가하고 기존 ENDED PCM stream도 당시 논리적 endedAt과 알려진 descriptor 범위로 backfill한다.

## 검증 범위

실제 PostgreSQL 테스트는 4.9/5/5.1초 tail, 동일/변경 seal, 원자적 rollback, 0frame, PAUSED, timeout·상한, 늦은 worker, cutoff·HEAD 중 cutoff 이동, VERIFIED 재조회, reserve/end 동시 잠금을 다룬다. 기존 recording unit/integration/acceptance 회귀도 실행한다.

제품 FE collector의 flush 이벤트 순서·실제 강제 탭 종료·최종 WAV/STT 연결은 후속 FE/전체 통합 검증 대상이다. 이 PR의 서버 테스트로 해당 사용자 흐름 완료를 주장하지 않는다.

## 실행 결과 (2026-10-18)

- `./gradlew spotlessApply test integrationTest acceptanceTest bootJar` 통과: unit 1,627개, PostgreSQL integration 717개(실제 S3 opt-in 1개 제외), HTTP acceptance 578개. 실패 0개. 실행된 2,921개 통과, opt-in 제외 1개.
- 마지막 `./gradlew spotlessCheck bootJar`도 통과했다.
- `python3 -m unittest discover ../.github/scripts -p 'test_*.py'`: Governance 10개 통과.
- 컨벤션 원문 hash gate 통과. #765 변경 Java 파일의 전체 감사 진단은 0개다. 저장소 전체 감사는 기존 진단 1,281개로 실패한다. 변경 범위 기본 감사에는 기존 build 의존성 버전 2개와 기존 RecordingStartAcceptanceTest 계층 의존성 1개가 남는다. 전체 감사 통과를 주장하지 않는다.
- `git diff --check` 통과. 실제 FE flush·강제 탭 종료와 실제 배포 전체 연결은 후속 통합 검증 대상이다.
