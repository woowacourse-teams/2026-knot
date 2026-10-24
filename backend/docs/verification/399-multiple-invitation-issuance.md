# #399 I02 복수 초대 발급

## 구현 범위

`POST /api/v1/workspaces/{workspaceId}/invitations`는 요청마다 새 초대를 만들고 201로
code·linkToken·expiresAt을 반환한다. 코드와 링크는 생성 24시간 뒤 함께 만료하며 이전 초대는
그대로 유효하다. 활성 멤버는 역할과 관계없이 발급할 수 있고 횟수 제한은 없다.
신규 코드는 A-Z 6자리다. 기존 숫자 코드와 소문자 정규화는 만료 전까지 서버에서 인정한다.

취소·다시 보기·목록이 없으므로 단일 조회 `GET /invitation`, 재발급 `POST /invitations/reissue`,
원문 재표시용 암호화와 기능 플래그를 제거했다. 신규 행의 암호문 컬럼은 NULL이며 V4 제약이
허용한다. 과거 재발급으로 무효화된 초대는 `invalidated_at` 판정으로 계속 무효다.

V20은 Workspace별 단일 미무효화 UNIQUE만 제거한다. hash UNIQUE·24시간 CHECK·FK는 유지한다.
hash 충돌 시 최대 3회의 새 transaction 재시도는 기존 구현을 재사용한다.

## 배포 조건

- FE는 발급 API와 미리보기만 호출하며 제거한 두 API를 화면에서 사용하지 않는다.
- `WORKSPACE_INVITATION_ENCRYPTION_KEY`는 더 이상 읽지 않는다. 배포 workflow 정리는 별도다.
- 암호문 컬럼 삭제는 후속 migration에서 처리한다.

## 검증

- OWNER/MEMBER 반복 발급 201, A-Z 코드, 기존 code/link 미리보기와 행 보존
- 같은 Workspace의 동시 발급, 실제 PostgreSQL 충돌 재시도와 3회 소진 rollback
- 인증·CSRF·잘못된 ID·비멤버·탈퇴자·삭제 Workspace 차단, no-store
- 과거 무효화 초대의 미리보기·참여 거부
- `spotlessCheck test integrationTest acceptanceTest bootJar --no-parallel`: 단위 257·통합 105·인수 157건,
  실패·오류·건너뜀 0
