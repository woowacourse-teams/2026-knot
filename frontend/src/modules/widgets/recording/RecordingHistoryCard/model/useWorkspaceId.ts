import { useParams } from "react-router";

/** 현재 경로의 `:workspaceId`. */
export const useWorkspaceId = () => {
  const { workspaceId } = useParams();
  return workspaceId;
};
