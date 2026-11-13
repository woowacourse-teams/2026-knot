import useTimeout from "@hooks/common/useTimeout";
import useCopyWorkspaceInvite from "@hooks/domain/workspace/useCopyWorkspaceInvite";
import useNavigateToWorkspaceHome from "@hooks/domain/workspace/useNavigateToWorkspaceHome";
import useWorkspaceAccessGuard from "@hooks/domain/workspace/useWorkspaceAccessGuard";
import { useParams } from "react-router";

const TIMEOUT_DURATION = 2000;

/**
 * 팀원 초대 카드의 초대 조회·복사·다음 이동 흐름.
 *
 * 현재 `:workspaceId`의 활성 초대를 조회해 코드·링크를 얻고, 응답 전에는 `isLoading`으로 복사를 막아요.
 * 조회가 401·403·404로 실패하면 `useWorkspaceAccessGuard`가 로그인·워크스페이스 선택 화면으로 보내요.
 * 그 밖의 실패로 받은 초대가 없으면 `isLoadFailed`가 `true`이고, `handleRetry`는 초대만 다시 받아요.
 * 복사하면 토스트 없이 버튼 표시(`isCodeCopied`·`isLinkCopied`)로만 알리고, 실패 토스트는 `useClipboard`가 띄워요.
 */
export const useWorkspaceInvite = () => {
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
    surface: "create_flow",
  });

  const { isRedirecting } = useWorkspaceAccessGuard({ error });

  const { navigateToWorkspaceHome } = useNavigateToWorkspaceHome();
  const { start: startCodeTimeout, isTimedOut: isCodeCopied } = useTimeout({
    timeout: TIMEOUT_DURATION,
  });
  const { start: startLinkTimeout, isTimedOut: isLinkCopied } = useTimeout({
    timeout: TIMEOUT_DURATION,
  });

  const handleCopyCode = () => {
    copyInviteCode({ onCopySuccess: startCodeTimeout });
  };

  const handleCopyLink = () => {
    copyInviteLink({ onCopySuccess: startLinkTimeout });
  };

  const handleNext = () => {
    if (!workspaceId) return;

    navigateToWorkspaceHome({ workspaceId });
  };

  return {
    inviteCode,
    displayInviteLink,
    isLoading,
    // 다른 화면으로 보내는 중이면 가는 동안 안내가 잠깐 보이지 않게 빼요
    isLoadFailed: inviteCode === undefined && error !== null && !isRedirecting,
    handleRetry: retryInvitation,
    isCodeCopied,
    isLinkCopied,
    handleCopyLink,
    handleNext,
    handleCopyCode,
  };
};
