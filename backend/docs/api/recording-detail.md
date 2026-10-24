# 녹음 단건 조회 API

이 문서는 [Issue #380](https://github.com/woowacourse-teams/2026-Knot/issues/380)의 단건 조회와 [Issue #386](https://github.com/woowacourse-teams/2026-Knot/issues/386)의 HTTP 상태 조회 계약이다. [Issue #651](https://github.com/woowacourse-teams/2026-Knot/issues/651)은 정리 화면에 필요한 문서 생성 결과를 추가한다.
팀 Notion에 반영하거나 승인받은 문서는 아니다.

## 요청

`GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}`

인증 쿠키만 보낸다. 조회는 생존 신호가 아니다. 상태·마지막 신호·제어 권한을 바꾸지 않으며, 최초 탭이 아닌 화면에서도 호출할 수 있다.

## 성공 응답 `200 OK`

```json
{
  "status": "ENDED",
  "elapsedMillis": 600000,
  "endedAt": "2026-10-09T10:10:00Z",
  "endReason": "USER_ENDED",
  "audioUploadStatus": "COMPLETED",
  "transcriptionAttemptId": "d0a03c18-1687-4512-8f79-1df67a8e56ef",
  "transcriptionStatus": "PROCESSING",
  "transcriptionFailureCode": null,
  "documentGeneration": null
}
```

| 필드 | 설명 |
| --- | --- |
| `status` | 세션 상태 `RECORDING`·`PAUSED`·`ENDED`·`DISCARDED`. 전사·문서 처리 상태가 아니다 |
| `elapsedMillis` | 일시정지를 뺀 서버 기준 녹음 시간. 진행 중이면 응답 시점까지 세며 최대 2시간(`7200000`)이다 |
| `endedAt`·`endReason` | 종료 시각과 사유(`USER_ENDED`·`CONNECTION_EXPIRED`·`MAX_DURATION`). 진행 중이면 `null`. 이 기능 이전에 종료된 녹음의 사유는 `null` |
| `audioUploadStatus` | 최종 오디오 업로드 예약이 없으면 `null`. 예약되면 `RESERVED`, 저장 확인 뒤 `COMPLETED` |
| `transcriptionAttemptId` | 현재 시도 UUID. 시도가 없으면 `null`, 명시적 재시도 시 교체된다 |
| `transcriptionStatus` | 현재 저장된 `PROCESSING`·`SUCCEEDED`·`FAILED`. 시도가 없으면 `null` |
| `transcriptionFailureCode` | 실패 이유 코드. 실패가 아니면 `null`. URL·토큰·전사 원문은 포함하지 않는다 |
| `documentGeneration` | 문서 생성 접수 전에는 `null`. 접수 뒤에는 아래 객체를 반환한다 |

세션과 업로드 예약은 한 시점의 값이다. 진행 중 상태와 업로드 정보가 섞인 조합(`RECORDING` + `COMPLETED` 등)은 반환하지 않는다.

### 문서 생성 결과

```json
{
  "status": "FAILED",
  "succeededCount": 2,
  "failedJobIds": [104, 105]
}
```

| 필드 | 의미 |
| --- | --- |
| `status` | 기존 `DocumentGenerationProgressResult.status`와 같은 분류·문서 생성 종합 상태 |
| `succeededCount` | 생성에 성공한 문서 수. `FAILED`일 때 0이면 전체 실패, 1 이상이면 일부 실패 |
| `failedJobIds` | 같은 녹음의 보존 기한 내 `FAILED` 작업 ID를 ID 오름차순으로 반환한다. 분류 실패와 사용자 재시도 3회 소진 작업을 포함하며 없으면 `[]` |

- `QUEUED`·`RUNNING`은 정리 중, `SUCCEEDED`는 모든 주제 완료, `NO_CONTENT`는 정리할 내용 없음, `FAILED`는 진행 중인 작업 없이 실패가 남은 상태다. 실행 중 작업, 대기 작업, 실패 순으로 우선한다. 2개 성공·1개 대기이면 `QUEUED`다.
- 문서 생성 접수 전에도 녹음 조회는 `200`이며 `documentGeneration: null`이다. 전사 실패는 `transcriptionStatus: FAILED`로 구분한다. 전사 진행 값은 `RUNNING`이 아니라 `PROCESSING`이다.
- 각 실패 ID는 [문서 생성 재시도 API](document-generation-job-retry.md)에 전달한다. 접수 시 작업과 집계가 `QUEUED`로 바뀌지만, 다음 조회 전에 실행이 끝나면 `SUCCEEDED`나 새 `FAILED`를 받을 수 있다. 409·404를 받은 ID는 다른 실패 ID와 구분해서 처리한다.
- 정확한 `expiresAt`부터 실패 ID를 제외한다. 실패 작업이 삭제되어도 저장된 종합 결과와 성공 수는 남으므로 `FAILED`·`succeededCount`와 `failedJobIds: []`를 함께 받을 수 있다.
- 단건 조회의 세션·업로드·전사·문서 집계와 실패 ID는 읽기 전용 `REPEATABLE_READ` 트랜잭션의 같은 스냅샷에서 읽는다. 조회 도중 재시도·실행·정리가 커밋되어도 서로 다른 시점의 값이 섞이지 않는다.
- 미완료 업로드를 최종 실패로 확정하는 정책은 이번 범위에 포함하지 않는다. `audioUploadStatus`는 기존 `null`·`RESERVED`·`COMPLETED`를 유지한다.

## 오류

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | `INVALID_WORKSPACE_ID`·`INVALID_RECORDING_DATA`·`INVALID_PARAMETER` | 잘못된 ID |
| 401 | `UNAUTHENTICATED` | 로그인 필요 |
| 403 | `WORKSPACE_ACCESS_DENIED` | Workspace 멤버가 아님. 탈퇴로 녹음이 폐기된 회원도 여기에 해당한다 |
| 403 | `RECORDING_CONTROL_DENIED` | 같은 Workspace의 다른 회원 녹음 |
| 404 | `WORKSPACE_NOT_FOUND`·`RECORDING_NOT_FOUND` | 없거나 다른 Workspace의 녹음 |

## 상태 수렴 규칙 (#386)

- 상태는 별도 구독 없이 두 HTTP 응답으로 받는다. 최초 탭은 [생존 신호](recording-heartbeat.md) 응답으로, 다른 탭·화면은 이 조회로 받는다. 두 응답의 `status`·`endedAt`·`endReason`은 같은 기준이다.
- 같은 `recordingId`에서 `ENDED`·`DISCARDED`를 한 번 본 FE는 늦게 도착한 활성 응답으로 화면을 되돌리지 않는다. 종료는 서버에서 되돌아가지 않는다.
- `403 WORKSPACE_ACCESS_DENIED`·`404`를 받으면 FE는 수집을 멈추고 임시 버퍼를 버린다.
- 기존 `GET .../recordings/current`는 바꾸지 않았다. 종료된 녹음은 여전히 `204`이므로, 종료 상태는 이 API로 확인한다.

## 이번 범위 밖

- SSE·웹소켓 같은 구독 API
- 새로고침 뒤 `recordingId`·음성 보존(FE 책임)

## 2026-10-18 홈 복귀 근거 대조

[STT-R24](https://www.notion.so/3edb43517522819d83a6fa3dfcdc3115)
(수정 `2026-10-18T06:00:00Z`)는 종료 후 처리·전사 실패·문서 생성 실패를 홈 카드에서 구분하고,
복원 근거가 없으면 빈 카드로 단정하지 않도록 한다. 같은 날
[API 보완 메모](https://www.notion.so/3deb4351752280e2ad98f2b5c5670fb2)
(수정 `2026-10-18T06:03:00Z`)는 단건 조회를 세션·업로드 5개 필드로 설명하지만,
현재 `RecordingDetailService`·`RecordingDetailResponse`와 닫힌
[#651](https://github.com/woowacourse-teams/2026-Knot/issues/651)은 이미 전사 상태와
`documentGeneration` 집계를 제공한다. 원문 메모를 현재 코드의 미구현 근거로 사용하지 않는다.

조회할 `recordingId`를 알고 있으면 위 필드로 상태를 읽을 수 있다. 반면 종료 후
`recordings/current`의 204와 Job 목록만으로는 문서 Job이 없는 전사 실패 녹음의 ID를
다시 발견할 수 없다. 새로고침·새 탭에서 종료 녹음 ID를 복원할 FE–BE 계약은 별도 확인이 필요하다.
[ADR 378](../adr/378-recording-tab-recovery-and-expiry.md)의 활성 세션 제어 증명을
종료 녹음의 복원 계약으로 확대하지 않는다. 홈 복귀·배포 검증 완료 판정은 보류한다.
