# 녹음 전사 DB 대기열과 복구 계약

[#766](https://github.com/woowacourse-teams/2026-knot/issues/766)은 다른 전사가 실행 중이어도 요청을 `QUEUED`로 보존한다. `QUEUED`는 실패가 아니며 대기 시간은 전사 실행 제한에 포함하지 않는다.

## 저장 완료 코드에서 접수하기

`RecordingTranscriptionStateService.registerAutomatic(recordingId)`는 기존 트랜잭션에 참여해야 한다. 최종 `RecordingAudioUpload` 채택과 같은 트랜잭션에서 호출한다. 동일 녹음이 이미 접수되었다면 기존 작업을 유지한다. 원본 key·version·타입·크기와 speakerCount를 attempt에 고정하며 현재 speakerCount는 null이다.

커밋이 끝난 뒤 `RecordingTranscriptionService.dispatch()`를 호출한다. 알림을 놓쳐도 기본 1초 주기의 scheduler가 DB 대기열을 조회한다. 외부 AI 호출은 DB 트랜잭션 밖에서 실행한다.

## 실행과 소유권

전역 admission advisory lock `639` 아래 접수 시각·ID 순서로 실행 가능한 작업 하나를 선점한다. DB partial unique index가 전역 `PROCESSING` 한 건을 보장한다. admission 잠금 안에서 Workspace·recording 행 잠금을 기다리지 않는다.

선점할 때 ownerToken·claimEpoch·leaseUntil을 저장한다. 재선점은 같은 attempt를 유지하며 epoch를 증가시킨다. 기본 lease는 60초, 갱신은 15초다. 준비·결과·실패·재대기·관측·갱신은 현재 owner/epoch와 유효 lease를 검사한다. DB나 프로세스 장애 이후에는 만료 lease를 선점하며 다른 owner의 유효 lease는 초기화하지 않는다.

원격 호출 직전과 채택 직전에 Workspace → recording → transcription 순서로 검사한다. 공유 Workspace가 남아 있어도 시작자의 유효 멤버십이 없으면 진행하지 않는다. 원본 삭제 여부와 논리적인 30일 보관 만료도 검사한다.

## 원격 복구

| 관측 | 처리 |
| --- | --- |
| 새 작업 | URL 발급 뒤 최초 POST 직전에 executionDeadline 저장 |
| POST 202 | 응답 snapshot의 PROCESSING·SUCCEEDED·FAILED를 처리 |
| POST 응답 유실·일시 장애 | 같은 attempt의 GET부터 확인 |
| GET의 명시적 JOB_NOT_FOUND | 기한 안이면 같은 source version의 새 URL·같은 attempt로 POST |
| GET PROCESSING | 기존 deadline으로 polling |
| GET SUCCEEDED | 현재 소유권·녹음·멤버십·원본을 확인하고 원문·발화·문서 접수를 원자적으로 채택 |
| GET FAILED | terminal 실패를 저장하고 다음 대기 작업으로 이동 |
| 명시적 BUSY·NOT_READY | 1·2·4초 이후 최대 30초 간격으로 재대기, 최초 준비 대기부터 기본 120초 상한 |
| 실행 기한 도달 | 마지막 GET 성공은 채택, 실행 중·확인 불가는 AI_OUTCOME_UNKNOWN |

최초 POST의 명시적인 접수 거절만 실행 시계를 취소할 수 있다. 이전 접수 여부가 불명확하면 기존 deadline을 유지한다. HTML 오류 페이지나 일반 503은 접수 거절 증거로 사용하지 않는다. 과거 실행 행에 deadline이 없으면 기존 startedAt과 현재 실행 제한으로 복구하며 재시작 시각으로 연장하지 않는다.

결과 미확정 실패의 사용자 재시도는 이전 attempt를 먼저 GET한다. 실행 중·성공은 기존 시도 관측으로 복귀한다. 확인된 FAILED·JOB_NOT_FOUND만 새 attempt를 만들며 조회 장애에서는 접수 성공을 반환하지 않는다. AI의 메모리 계약 때문에 외부 실행은 재수행될 수 있으며 DB 결과와 문서 접수의 멱등성을 보장한다.

## 설정과 배포

| 환경 변수 | 기본값 |
| --- | --- |
| TRANSCRIPTION_DISPATCH_INTERVAL | PT1S |
| TRANSCRIPTION_LEASE_DURATION | PT60S |
| TRANSCRIPTION_LEASE_RENEW_INTERVAL | PT15S |
| TRANSCRIPTION_READINESS_TIMEOUT | PT120S |

기존 `TRANSCRIPTION_JOB_TIMEOUT`은 최초 원격 POST 이후 실행 budget이다. 대기열을 비우지 않고 QUEUED를 처리하지 못하는 버전으로 되돌리지 않는다. 신규 시작을 막은 뒤 drain하거나 호환 버전을 배포한다.

V56은 표준 V53 이후 스키마와 과거 수동 QUEUED 스키마를 모두 확장한다. 기존 V50/V51을 수정하지 않는다. 기존 상태·attempt·객체 version을 보존하며 알려진 대기열 index를 명시한 정의로 재구성한다. 과거 원본 version이 없는 행은 임의의 최신 객체로 대체하지 않는다.

## 검증 경계

실제 PostgreSQL 테스트는 동시 등록·동시 선점·FIFO·선행 실패 후 다음 실행, lease 재선점·구 epoch 변경 거절, 시작자 탈퇴·보관 만료, 표준/과거 migration 보존을 다룬다. HTTP stub과 worker 테스트는 응답 유실·404·202 terminal snapshot·준비 대기·deadline·미확정 사용자 retry를 다룬다.

실제 제품 FE의 QUEUED 표시와 두 사용자의 실제 AI·문서 생성은 출시 통합 T16/T17에서 별도로 확인한다. 이 문서는 배포나 실제 음원 통합 성공을 의미하지 않는다.
