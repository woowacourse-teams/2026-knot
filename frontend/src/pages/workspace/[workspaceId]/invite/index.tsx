import WorkspaceInviteCard from "@widgets/workspace/WorkspaceInviteCard";

/**
 * 팀원 초대 - 참여 코드 및 링크 공유 화면 (`/workspace/:workspaceId/invite`)
 *
 * 워크스페이스를 만든 직후 팀원을 부르는 화면이다.
 * 참여 코드와 초대 링크를 복사해 공유하고,
 * 「다음」으로 워크스페이스 홈(`/workspace/:workspaceId`)에 들어간다.
 *
 * 로고와 중앙 배치는 `CenteredLayout`이 담당하고, 이 페이지는 카드를 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1868 새 워크스페이스 생성/참여 코드 및 링크 공유
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=679-3120 새 워크스페이스 생성/참여 코드 복사
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=704-3182 새 워크스페이스 생성/참여 코드 복사 완료
 */
export default function WorkspaceInvitePage() {
  return (
    <>
      <title>팀원 초대 - knot</title>
      <WorkspaceInviteCard />
    </>
  );
}
