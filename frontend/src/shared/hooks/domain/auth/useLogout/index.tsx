import { getCurrentRecordingApi } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/current";
import useLogoutMutation from "@api/mutations/useLogoutMutation";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import useKeyDown from "@hooks/common/useKeyDown";
import useNavigateToLogin from "@hooks/domain/auth/useNavigateToLogin";
import useRecordingControl from "@hooks/domain/recording/useRecordingControl";
import ConfirmDialog from "@primitives/ui/ConfirmDialog";
import Dim from "@primitives/ui/Dim";
import { useDialog, useHasDialog } from "@provider/context/dialogContext";
import { useToast } from "@provider/context/toastContext";
import { useRecordingStore } from "@store/recordingStore";
import { useQueryClient } from "@tanstack/react-query";
import { clearAuthReturnPath } from "@utils/authReturnPath";
import { clearAuthSession } from "@utils/authSession";
import { clearFeedbackDraft } from "@utils/feedbackDraft";
import { clearAllLastConversationIds } from "@utils/lastConversation";
import { logRequestError } from "@utils/logRequestError";
import { useState } from "react";
import { useParams } from "react-router";

const END_RECORDING_DIALOG = {
  title: "녹음을 끝내고 로그아웃할까요?",
  description: "지금 녹음은 문서로 정리돼요.",
  confirmLabel: "끝내고 로그아웃",
};

const RECORDING_ELSEWHERE_DIALOG = {
  title: "다른 곳에서 녹음 중이에요",
  description:
    "로그아웃하면 이 브라우저의 녹음은 저장이 멈춰요.\n다른 기기의 녹음은 그대로 이어져요.",
  confirmLabel: "로그아웃",
};

interface LogoutDialogProps {
  title: string;
  description: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
}

interface AskBeforeLogoutParams {
  dialog: Pick<LogoutDialogProps, "title" | "description" | "confirmLabel">;
  onConfirm: () => void;
}

/** 끝내는 중인 녹음과 끝낸 녹음의 조각 올리기가 끝날 때까지 기다려요. 시한은 큐가 지키므로 제한 시간을 더하지 않아요(AUTH-R22) */
const waitForRecordingUpload = () =>
  new Promise<void>((resolve) => {
    const resolveWhenDone = () => {
      const { isEnding, isUploadingChunks } = useRecordingStore.getState();
      if (isEnding || isUploadingChunks) return;

      unsubscribe();
      resolve();
    };
    const unsubscribe = useRecordingStore.subscribe(resolveWhenDone);

    resolveWhenDone();
  });

/**
 * 이 브라우저의 로그인을 끝내고 로그인 화면으로 보내는 도메인 훅(AUTH-R18).
 *
 * - 녹음이 없으면 확인 없이 로그아웃해요. 요청하는 동안 `isLoggingOut`이 켜지고, 실패하면 오류 토스트를 띄우고 화면과 기록을 그대로 둬요(AUTH-R19).
 * - 성공하면 진행 중인 조회를 취소하고 쿼리 캐시와 이 탭의 기록을 지운 뒤 로그인 화면으로 `replace` 이동해요(AUTH-R20).
 *   로그인 기록도 지워, 뒤따르는 `/me` 401과 재발급 401이 로그인 만료 토스트를 띄우지 않아요(AUTH-R21).
 * - 이 탭에서 녹음 중이면(일시정지 포함) 끝낼지 물어요. 확인 창은 바로 닫고, 녹음을 끝내는 동안 `isLoggingOut`을 켜요.
 *   끝내지 못해 녹음이 이어지면(`notEnded`) 로그아웃하지 않아요. 실패 안내(오류 토스트)는 `useRecordingControl`이 띄워요.
 * - 끝낸 녹음의 소리를 올리는 중이면 다 올릴 때까지 기다린 뒤 로그아웃을 요청해요. 다 올렸는지는 구분하지 않아요(AUTH-R22).
 * - 이 탭에 녹음이 없으면 누른 때 보고 있는 워크스페이스의 녹음을 새로 조회해, 다른 곳에서 녹음 중이면 알려요.
 *   조회에 실패하면 알리지 않고 이어 가요(AUTH-R22).
 *
 * 처리 중에도 화면을 쓸 수 있어, 성공하면 그사이 시작한 녹음과 띄운 모달도 로그인 화면에 남지 않게 닫아요.
 */
const useLogout = () => {
  const { workspaceId } = useParams();
  const { open, closeOpenedDialog } = useDialog();
  const hasDialog = useHasDialog();
  const { show } = useToast();
  const queryClient = useQueryClient();
  const { navigateToLogin } = useNavigateToLogin();
  const { mutateAsync: requestLogout } = useLogoutMutation();
  const { endRecordingWithoutConfirm } = useRecordingControl();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const finishLogout = async () => {
    setIsLoggingOut(true);
    await waitForRecordingUpload();
    // 다른 화면에서 시작한 끝내기가 실패해 녹음이 이어지면 로그아웃하지 않아요
    if (useRecordingStore.getState().status !== "idle") {
      setIsLoggingOut(false);
      return;
    }

    try {
      await requestLogout();
    } catch {
      setIsLoggingOut(false);
      show(TOAST_MESSAGES.LOGOUT_FAILED);
      return;
    }

    useRecordingStore.getState().discardRecording();
    closeOpenedDialog();
    await queryClient.cancelQueries();
    queryClient.clear();
    clearFeedbackDraft();
    clearAuthReturnPath();
    clearAllLastConversationIds();
    clearAuthSession();
    navigateToLogin({ replace: true });
  };

  const endRecordingAndLogout = async () => {
    setIsLoggingOut(true);
    // 창을 띄운 사이 서버가 끝내 비운 녹음은 끝낼 것이 없어 그대로 이어 가요
    const isRecordingActive = useRecordingStore.getState().status !== "idle";
    if (
      isRecordingActive &&
      (await endRecordingWithoutConfirm()) === "notEnded"
    ) {
      setIsLoggingOut(false);
      return;
    }

    await finishLogout();
  };

  const askBeforeLogout = ({ dialog, onConfirm }: AskBeforeLogoutParams) => {
    setIsLoggingOut(false);
    open(({ close }) => (
      <LogoutDialog
        {...dialog}
        onCancel={close}
        onConfirm={() => {
          close();
          onConfirm();
        }}
      />
    ));
  };

  const isRecordingElsewhere = async () => {
    try {
      const recording = await getCurrentRecordingApi(Number(workspaceId));

      return (
        recording?.status === "RECORDING" || recording?.status === "PAUSED"
      );
    } catch (error) {
      logRequestError({
        feature: "auth",
        action: "로그아웃 전 현재 녹음 조회",
        error,
      });

      return false;
    }
  };

  const logout = async () => {
    // 창은 포커스를 가져가지 않아, 창이 떠 있어도 메뉴 항목이 키보드로 다시 눌려요
    if (isLoggingOut || hasDialog) return;

    const { status, isEnding } = useRecordingStore.getState();
    if (status !== "idle" && !isEnding) {
      askBeforeLogout({
        dialog: END_RECORDING_DIALOG,
        onConfirm: endRecordingAndLogout,
      });
      return;
    }

    setIsLoggingOut(true);
    if (status === "idle" && (await isRecordingElsewhere())) {
      askBeforeLogout({
        dialog: RECORDING_ELSEWHERE_DIALOG,
        onConfirm: finishLogout,
      });
      return;
    }

    await finishLogout();
  };

  return { logout, isLoggingOut };
};

/**
 * 로그아웃 전에 묻는 모달. 녹음 중 확인 창과 다른 곳 녹음 알림 창이 같은 모양이에요.
 *
 * 확인 유형이라 바깥을 누르거나 ESC를 눌러도 [취소]와 같이 모달만 닫혀요.
 */
function LogoutDialog({
  title,
  description,
  confirmLabel,
  onCancel,
  onConfirm,
}: LogoutDialogProps) {
  useKeyDown({ key: "Escape", isEnabled: true, onKeyDown: onCancel });

  return (
    <Dim onClick={onCancel}>
      <ConfirmDialog
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        title={title}
        description={description}
        cancelLabel="취소"
        confirmLabel={confirmLabel}
        onCancel={onCancel}
        onConfirm={onConfirm}
      />
    </Dim>
  );
}

export default useLogout;
