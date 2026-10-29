import WorkspaceInviteLinkGate from "@widgets/workspace/WorkspaceInviteLinkGate";

/**
 * 초대 링크 진입 화면 (`/invite/:token`)
 *
 * 팀원이 공유한 초대 링크(`origin/invite/<linkToken>`)를 열었을 때 가장 먼저 닿는 화면이다.
 * 자체 UI 없이 토큰을 판정하는 동안 스피너만 보여주고,
 * 통과하면 워크스페이스 입장 확인(`/workspace/:workspaceId/join`)으로,
 * 만료·잘못된 링크면 초대 링크 오류(`/join-error`)로 `replace` 이동해 뒤로 가기 때 이 화면으로 돌아오지 않는다.
 *
 * 로고와 중앙 배치는 `CenteredLayout`이 담당하고, 이 페이지는 위젯을 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=600-10180 초대 링크로 워크스페이스 입장
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=600-10148 올바르지 않은 초대 링크 접근
 */
export default function InvitePage() {
  return (
    <>
      <title>초대 링크 확인 - knot</title>
      <WorkspaceInviteLinkGate />
    </>
  );
}
