import WorkspaceJoinErrorNotice from "@widgets/workspace/WorkspaceJoinErrorNotice";

/**
 * 초대 링크 오류 화면 (`/join-error`)
 *
 * 만료되었거나 잘못된 초대 링크로 접근했을 때 보여주는 화면이다.
 * 초대 링크 진입(`/invite/:token`)에서 판정에 실패하면 이 화면으로 오고,
 * 초대 코드 직접 입력(`/workspace/code`)으로 이어진다.
 *
 * 로고와 중앙 배치는 `CenteredLayout`이 담당하고, 이 페이지는 안내를 놓기만 한다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=600-10148
 */
export default function JoinErrorPage() {
  return (
    <>
      <title>초대장을 열 수 없어요 - knot</title>
      <WorkspaceJoinErrorNotice />
    </>
  );
}
