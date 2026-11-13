import useTimeout from "@hooks/common/useTimeout";
import useCopyWorkspaceInvite from "@hooks/domain/workspace/useCopyWorkspaceInvite";
import { useParams } from "react-router";

/** `복사됨` 표시를 유지하는 시간(ms). 팀원 초대 화면 카드와 같아요. */
const COPIED_DURATION_MS = 2000;

/**
 * 홈 팀원 초대 카드의 초대 조회·복사 흐름.
 *
 * 현재 `:workspaceId`의 활성 초대를 조회해 코드·링크를 얻고, 응답 전에는 `isLoading`으로 복사를 막아요.
 * 조회 실패의 이동 판정은 이 카드가 아니라 워크스페이스 레이아웃이 맡아요.
 * 받은 초대 없이 조회에 실패하면 `isLoadFailed`가 `true`이고, `handleRetry`는 이 카드의 초대만 다시 받아요.
 */
export const useWorkspaceInviteMemberCard = () => {
  const { workspaceId } = useParams();
  const {
    inviteCode,
    displayInviteLink,
    isLoading,
    error,
    retryInvitation,
    copyInviteCode,
    copyInviteLink,
  } = useCopyWorkspaceInvite({
    workspaceId: Number(workspaceId),
    surface: "home_card",
  });
  const { start: startLinkTimeout, isTimedOut: isLinkCopied } = useTimeout({
    timeout: COPIED_DURATION_MS,
  });
  const { start: startCodeTimeout, isTimedOut: isCodeCopied } = useTimeout({
    timeout: COPIED_DURATION_MS,
  });

  const handleCopyLink = () => {
    copyInviteLink({ onCopySuccess: startLinkTimeout });
  };

  const handleCopyCode = () => {
    copyInviteCode({ onCopySuccess: startCodeTimeout });
  };

  return {
    inviteCode,
    displayInviteLink,
    isLoading,
    isLoadFailed: inviteCode === undefined && error !== null,
    handleRetry: retryInvitation,
    isLinkCopied,
    isCodeCopied,
    handleCopyLink,
    handleCopyCode,
  };
};
