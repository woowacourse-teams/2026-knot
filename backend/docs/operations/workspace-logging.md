# Workspace 로그 조회

워크스페이스 생성·조회·최근 선택·탈퇴와 소유권 승계 후 탈퇴를 requestId와 workspaceId로 연결한다. 탈퇴가 폐기한 녹음은 recordingId만으로 원인과 당시 요청을 찾는다. 초대 전용 로그는 후속 범위다.

## 이벤트와 결과

| event | 정상 결과 | 의미 |
| --- | --- | --- |
| workspace.create | created | Workspace와 OWNER 멤버십 커밋 |
| workspace.list | completed | 목록·마지막 선택 조회, 빈 목록도 정상 |
| workspace.detail | completed | 존재·멤버십 검증과 상세 조회 |
| workspace.last_viewed | changed / replayed | 마지막 선택 갱신 / 같은 대상 재선택 |
| workspace.leave | left / left_and_workspace_deleted / replayed | 탈퇴 커밋 / 마지막 멤버 탈퇴와 Workspace 논리 삭제 커밋 / 이미 탈퇴한 이력의 재요청 |
| workspace.ownership_transfer_and_leave | ownership_transferred_and_left | 소유권 변경·본인 탈퇴·후속 처리 커밋 |
| workspace.ownership_candidates | completed | OWNER 검증과 승계 후보 조회 |
| recording.discard | discarded | 탈퇴로 인한 해당 녹음 폐기 커밋 |

이름·역할·시각 등 실제 API 응답과 DB 정책은 바꾸지 않는다. 마지막 멤버 탈퇴 뒤 삭제된 Workspace의 재요청은 기존 404이고, 소유권 승계 재요청은 기존 권한 거절이다. 생성 요청을 같은 이름으로 반복하면 기존 동작대로 별도 Workspace가 만들어진다.

## 시작·단계·완료

operation의 started는 Service 본문 진입, 최종 결과는 트랜잭션 afterCompletion에서 기록한다. 각 단계는 started와 prepared를 즉시 기록한다. prepared는 Java 호출이 반환했다는 뜻이며 DB 커밋 성공이 아니다. JPA save는 flush·커밋 전에 반환할 수 있다.

단계는 생성·권한 검증·잠금 조회·DB 저장·녹음 폐기·멤버십 flush·조건부 문서 처리 등 기존 호출 경계다. 실패한 operation에는 failedStage와 안전한 errorCode가 있다. HTTP 전체 처리와 Service 진입 전 인증·입력 검증은 이 이벤트의 측정 범위 밖이다.

elapsedMs는 단조 시계다. stage=operation의 최종 시간은 Service 본문 진입부터 트랜잭션 완료 콜백까지, 나머지 단계의 prepared 시간은 해당 단계의 진입부터 반환까지다. 서로 다른 범위를 단순 합산하지 않는다.

## 녹음 폐기에서 탈퇴 요청으로

recording.discard는 실제 loop가 처리한 녹음에만 recordingId·workspaceId·requestId·reason을 기록한다. reason은 다음 고정값이다.

- WORKSPACE_MEMBER_LEFT: 일반 탈퇴자의 활성 녹음
- WORKSPACE_OWNERSHIP_TRANSFER_AND_LEFT: 승계 후 탈퇴한 이전 OWNER의 활성 녹음
- WORKSPACE_DELETED: 마지막 멤버 탈퇴로 삭제되는 Workspace의 모든 활성 녹음

폐기할 녹음이 없어도 마지막 멤버 탈퇴는 workspace.leave=left_and_workspace_deleted로 구분한다. 이미 종료·폐기된 녹음과 다른 Workspace의 녹음에는 새 폐기 이벤트를 만들지 않는다. 이미 탈퇴한 멤버의 재요청에서도 새 discarded를 만들지 않는다.

문서 처리에서 실패하면 준비된 녹음의 최종 결과는 rolled_back이다. 둘째 녹음 저장이 실패하면 첫째는 rolled_back, 둘째는 failed이며 미실행 항목에는 성공 로그가 없다. recordingId 검색에서 requestId를 얻은 뒤 같은 요청의 workspace 로그를 찾아 실패 단계를 확인한다.

DocumentArchivalService는 pendingCount가 0인 대상 초안만 보관한다. archive_documents=prepared나 탈퇴 성공이 모든 문서의 보관을 뜻하지 않는다.

## 실패·롤백·레벨

예상된 업무 거절은 rejected INFO, 기술 원인이 있거나 예상하지 못한 오류는 failed ERROR다. 준비된 녹음의 rolled_back은 INFO다. 트랜잭션 결과가 UNKNOWN이면 unconfirmed WARN이며 성공을 주장하지 않는다. 예외의 안전한 종류·코드 위치를 diagnostic으로 남기고 예외 메시지는 제외한다.

본문이 정상 반환한 뒤 외부 트랜잭션이 롤백되면 failedStage=transaction_completion이다. 이 값만으로 DB commit 자체가 실패했다고 단정하지 않는다. 이미 본문에서 실패한 단계를 확인했다면 해당 단계를 보존한다. 기술 예외가 4xx ProjectException으로 변환됐어도 원인이 동반되면 failed로 관측하며 HTTP 응답은 그대로다.

HTTP의 requestId는 Scope 시작 시 캡처해 완료 시에도 유지한다. 녹음 생성 API의 멱등성 입력 requestId와 다르다. HTTP 밖에서 MDC가 없으면 null이며 요청 ID를 꾸며 내지 않는다. 토큰·쿠키·Workspace 이름·사용자 이름·오디오·문서·서명 URL·예외 메시지는 기록하지 않는다.

## 조회 예시와 한계

기존 콘솔 접두사 뒤의 메시지가 JSON이며, 동일한 필드를 SLF4J key-value에도 기록한다. 기본 콘솔 한 줄 전체가 JSON인 것은 아니다. Loki에서는 실제 수집기 label을 고른 뒤 메시지를 추출한다. app은 환경에 맞게 바꾸고 ID를 label로 승격하지 않는다.

```logql
{app="backend"} | regexp `(?P<payload>\{"event":.*\})` | line_format `{{.payload}}` | json | recordingId="123"
```

폐기 이벤트의 requestId로 같은 쿼리의 필터를 requestId="..."로 바꾸면 당시 탈퇴·단계 로그를 찾는다. 중첩 operation과 기존 오류 진단이 같은 실패를 여러 줄 기록할 수 있으므로 ERROR 줄 수는 실패 요청 수가 아니다.

started만 있으면 실행 중·프로세스 종료·수집 유실도 가능하다. 정확히 한 번의 감사 원장이나 영구적인 사유 보존을 보장하지 않으며, 조회 가능 기간은 수집·보존 정책에 달려 있다. 이 변경은 수집기·배포·대시보드·알림을 설치하지 않는다.
