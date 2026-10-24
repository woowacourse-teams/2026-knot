# Workspace 삭제 API를 두지 않고 혼자 남은 OWNER의 탈퇴로만 Workspace를 논리 삭제한다

## 상태

Proposed

## 관련 Issue

- #410 [BE] 워크스페이스 삭제 API와 진행 중 녹음 폐기 적용

## 한 줄 요약

Workspace 삭제 API를 두지 않고 혼자 남은 OWNER의 탈퇴로만 Workspace를 논리 삭제한다

## 왜 이 결정이 필요했나

2026-09-30 정책은 다른 멤버가 있는 OWNER에게 승계 후 탈퇴와 Workspace 논리 삭제를 모두 허용했고, 삭제 시 다른 멤버의 진행 중 녹음까지 폐기하기로 했다.

2026-10-05 OWNER 삭제 API(#470, PR #471)를 구현한 뒤 담당자가 다른 멤버가 남은 Workspace를 삭제하는 경로가 정책과 맞지 않는다고 확정했다.

결정 동인:

- 다른 멤버가 남은 Workspace는 OWNER 한 명의 결정으로 없애지 않는다
- 같은 결과를 내는 경로를 중복으로 두지 않는다
- 이미 구현된 승계·탈퇴 경로를 그대로 쓴다

## 트레이드 오프

- OWNER가 언제든 삭제하고 진행 중 녹음을 폐기(9/30 결정, PR #471): 다른 멤버가 있어도 Workspace와 그들의 녹음을 OWNER 혼자 없앤다
- 삭제 API를 유지하되 OWNER 혼자일 때만 허용: 결과가 마지막 멤버 탈퇴와 같아 API만 늘어난다
- 삭제 API 없이 혼자 남은 OWNER의 탈퇴로만 논리 삭제(채택): 다른 멤버가 있으면 반드시 승계한다

## 무엇을 결정했나

Workspace 삭제 API를 두지 않고 혼자 남은 OWNER의 탈퇴로만 Workspace를 논리 삭제한다

다른 멤버가 남은 Workspace는 반드시 승계로 이어지고, Workspace가 사라지는 경우는 OWNER 혼자 남았을 때뿐이다. 혼자일 때의 삭제는 이미 마지막 멤버 탈퇴가 처리하므로 별도 API가 필요 없다.

## 결과

- DELETE /api/v1/workspaces/{workspaceId}는 만들지 않는다
- 다른 멤버가 있는 OWNER의 일반 탈퇴는 계속 409 WORKSPACE_OWNER_TRANSFER_REQUIRED다
- 다른 멤버의 진행 중 녹음을 OWNER가 폐기하는 경로가 없어진다
- 9/30 정책의 OWNER 삭제 문구와 #471의 ADR 초안은 대체된다

## 다시 논의해야 할 조건

- 다른 멤버가 남은 Workspace를 정리해야 하는 운영·제품 요구가 생길 때
- soft delete 복구 요구가 생길 때

## 확인

- 예정 경로: `backend/docs/adr/410-workspace-deletion-only-by-last-owner-leave.md`
- 결정 주체: 루덴스
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
