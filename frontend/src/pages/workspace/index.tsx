import WorkspaceEntryCard from "@widgets/workspace/WorkspaceEntryCard";

/**
 * 워크스페이스 생성 및 참여 선택 화면 (`/workspace`)
 *
 * 가입을 마친 사용자가 어느 경로로 워크스페이스에 들어갈지 고르는 분기 화면이다.
 * 새 워크스페이스 만들기(`/workspace/create`)와
 * 초대 코드로 참여하기(`/workspace/code`) 두 가지 선택지를 제공한다.
 *
 * 로고와 중앙 배치는 `CenteredLayout`이 담당하고, 이 페이지는 카드를 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=600-10188
 */
export default function WorkspacePage() {
  return (
    <>
      <title>워크스페이스 생성 및 참여 - knot</title>
      <WorkspaceEntryCard />
    </>
  );
}
