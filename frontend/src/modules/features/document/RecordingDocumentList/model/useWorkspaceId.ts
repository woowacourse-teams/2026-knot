import { useParams } from "react-router";

/**
 * 주소의 `:workspaceId`를 숫자로 바꿔 돌려줘요.
 *
 * 숫자인지는 확인하지 않아요. 잘못된 주소인지는 서버가 판단하고, 같은 판단을 프론트에도 두면 기준이 두 곳에 생기기 때문이에요.
 */
export const useWorkspaceId = () => {
  const params = useParams();

  return { workspaceId: Number(params.workspaceId) };
};
