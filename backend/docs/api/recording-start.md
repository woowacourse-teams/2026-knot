# 녹음 시작 API

[Issue #378](https://github.com/woowacourse-teams/2026-Knot/issues/378)의 구현 계약이다.
기존 [Notion 시작 명세](https://www.notion.so/3deb4351752280298779ccdde0ccb68e)의 본문 없음·201 전용 계약을
멱등 시작과 최초 탭 제어 증명에 맞춰 보완한다. 팀 Notion의 실시간 반영·승인을 확인한 문서는 아니다.

## 요청

`POST /api/v1/workspaces/{workspaceId}/recordings`

인증 쿠키와 `X-XSRF-TOKEN`을 보낸다. `workspaceId`는 현재 참여 중인 Workspace여야 한다.
JSON 본문은 세 필드를 모두 요구한다.

| 필드 | 형식 | 유지 범위 |
| --- | --- | --- |
| `requestId` | UUID | 하나의 시작 시도와 그 재시도에서 동일 |
| `tabId` | UUID | 최초 녹음 탭에서 새로고침 동안 유지 |
| `controlToken` | 암호학적 난수 32바이트의 패딩 없는 Base64URL, 43자 | 같은 요청 재시도와 같은 탭 복구에 유지 |

클라이언트는 요청 전에 세 값을 만들어 보관한다. 응답 유실 때 새 키를 만들지 않는다.
다른 탭·기기에 증명을 공유하거나 전체 브라우저가 공유하는 저장소에 넣지 않는다.
서버는 SHA-256 해시만 저장하며 토큰·해시·탭 ID·요청 ID를 응답에 포함하지 않는다.
브라우저의 탭 복제 시 저장소 복사와 중복 제어 방지는 FE·#384의 통합 검증 대상이다.
서버가 확인하는 것은 인증된 Member와 등록한 탭 ID·증명의 조합이다.

### 탭 ID와 제어 증명을 함께 받는 이유

제어 권한을 실제로 지키는 값은 `controlToken`이다. `tabId`는 비밀값이 아니라서
같은 회원의 다른 탭도 바꿔 보낼 수 있다([ADR 378](../adr/378-recording-tab-recovery-and-expiry.md)).
권한 판단만 보면 `controlToken` 하나로 충분하지만, 두 값을 함께 받는 이유는 다음과 같다.

- 수명이 다르다. `controlToken`은 녹음마다 새로 만들지만 `tabId`는 같은 탭이면 다음 녹음에서도 유지할 수 있다.
  `tabId`는 녹음과 관계없이 그 탭을 가리키는 식별자다.
- 기록에 쓸 수 있다. `controlToken`은 로그·응답에 남기면 안 되지만, `tabId`는 비밀값이 아니라서
  장애를 분석할 때 어느 탭이 보낸 요청인지 가리키는 데 쓸 수 있다. 현재 서버는 `tabId`를 로그나 응답에 남기지 않는다.

## 성공 응답

```json
{
  "recordingId": 10,
  "status": "RECORDING",
  "startedAt": "2026-10-01T00:00:00Z"
}
```

- 새 요청은 `201 Created`이며 `status=RECORDING`이다.
- 같은 Member의 같은 `requestId`와 같은 Workspace·탭·증명은 `200 OK`다.
  기존 `recordingId`·`startedAt`을 유지하고 현재 `RECORDING`·`PAUSED`·`ENDED`·`DISCARDED` 상태를 반환한다.
- 재전송은 상태·생존 시각을 갱신하지 않는다. 종료·폐기된 키를 보내도 새 세션을 만들거나 재개하지 않는다.
- 기존 세션이 종료·폐기되면 새 `requestId`로 시작할 수 있다. 종료·폐기된 키의 이력은 세션과 함께 유지한다.
- `DISCARDED`는 Workspace 탈퇴·승계 후 탈퇴·마지막 멤버 탈퇴로 진행 중 녹음을 저장 없이 폐기한 상태다.
  FE는 `ENDED`와 같이 수집을 멈추고, 임시 오디오를 저장하지 않고 버린다.

조회 API는 아직 구현하지 않아 `Location`을 제공하지 않는다.
`startedAt`은 서버 UTC 시각이며 PostgreSQL 저장과 응답 모두 마이크로초 정밀도를 사용한다.

## 실패 응답

기존 `ErrorResponse`의 `code`, 한글 `message`, `fieldErrors`를 사용한다.

| 상태 | 코드 | 조건 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 누락·잘못된 증명 형식 |
| 400 | `INVALID_REQUEST_BODY` 또는 `INVALID_PARAMETER` | UUID·JSON·경로 타입 오류 |
| 401 | `UNAUTHENTICATED` | 인증 없음 |
| 403 | `FORBIDDEN` | CSRF 검증 실패 |
| 403 | `WORKSPACE_ACCESS_DENIED` | 현재 소속 없음 |
| 404 | `WORKSPACE_NOT_FOUND` | Workspace 없음(양수가 아닌 ID 포함) |
| 409 | `ACTIVE_RECORDING_ALREADY_EXISTS` | 다른 요청으로 만든 본인 활성 세션 존재 |
| 409 | `RECORDING_START_REQUEST_CONFLICT` | 같은 키에 Workspace·탭·증명 중 하나라도 변경 |

오류는 입력 원문, 제어값, DB 제약명·SQL·예외명·스택을 포함하지 않는다.

## 저장과 후속 연동

Workspace 행을 잠그고 소속을 확인한 뒤 Member 행을 잠근다.
다른 Workspace의 동시 시작도 Member 잠금 아래 직렬화하며, 활성 상태에만 적용되는
Member 부분 Unique Index와 `(member_id, request_id)` Unique로 DB에서도 보장한다.
Workspace 전체 녹음 개수 제한은 추가하지 않는다.

시간은 `RECORDING` 구간만 합산하고 `PAUSED` 구간을 제외한다. 이는 서버 상태의 시간이며
새로고침 중 오디오 공백이나 실제 음성 파일 길이를 보장하지 않는다.
종료·복구·2시간 상한·파일 저장·STT·문서 생성 API는 후속 범위다.
현재 구현만으로 녹음 제품 흐름을 공개하거나 `PROCESSING`을 반환하지 않는다.

#411의 활성 멤버십·삭제 Workspace 필터는 해당 PR 병합 후 연결한다.
일반·승계 탈퇴는 본인의 진행 중·일시정지 녹음을 저장 없이 폐기하고,
마지막 멤버 탈퇴·Workspace 삭제는 해당 Workspace의 진행 중 녹음을 폐기한다.
이 시작 API가 폐기 처리를 구현한 것은 아니다.

새 migration은 최신 develop의 V14 다음인 V15다. #411에도 미병합 V15가 있으므로
병합 순서가 정해지면 아직 배포되지 않은 쪽의 번호를 조정하고 전체 migration을 다시 검증한다.
V16을 먼저 배포한 뒤 V15를 추가하는 순서를 피한다.

검증 명령·완료 조건별 증거·컨벤션 적용과 기존 위반은 [구현 검증 기록](../verification/378-recording-start.md)을 따른다.
