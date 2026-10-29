import CreateWorkspaceCard from "@widgets/workspace/CreateWorkspaceCard";

/**
 * 새 워크스페이스 생성 화면 (`/workspace/create`)
 *
 * 워크스페이스 이름을 입력해 새 워크스페이스를 만드는 화면이다.
 * 입력 전 / 입력 중 / 입력 에러 세 가지 상태를 한 화면에서 다룬다.
 * 생성에 성공하면 팀원 초대(`/workspace/:workspaceId/invite`)로 이어진다.
 *
 * 로고와 중앙 배치는 `CenteredLayout`이 담당하고, 이 페이지는 카드를 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=431-1294 새 워크스페이스 생성/입력 전
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1576 새 워크스페이스 생성/입력 중
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=432-1594 새 워크스페이스 생성/입력 에러
 */
export default function WorkspaceCreatePage() {
  return (
    <>
      <title>워크스페이스 만들기 - knot</title>
      <CreateWorkspaceCard />
    </>
  );
}
