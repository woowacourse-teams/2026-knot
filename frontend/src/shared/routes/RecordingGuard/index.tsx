import useServerRecording from "@hooks/domain/recording/useServerRecording";
import { useRecordingStore } from "@store/recordingStore";
import { getRecordingResumeProof } from "@utils/recordingControlProof";
import { useRef, useState } from "react";
import { Navigate, Outlet, useParams } from "react-router";

import { getRouterPath } from "../PATH_ROUTE";

/**
 * 진행 중인 녹음이 있을 때만 화면을 여는 가드.
 *
 * 녹음은 독의 마이크에서 권한을 받은 뒤 시작하고, 녹음 화면은 이미 시작한 녹음을 보여 주기만 해요.
 * 그래서 녹음 없이 들어오면(주소를 직접 친 경우 등) 워크스페이스 홈으로 보내요.
 * 뒤로 가기로 빈 녹음 화면에 돌아오지 않도록 기록을 바꿔요.
 * 이 탭을 새로고침했으면 이어 녹음 증명이 남아 있어, 서버가 그 녹음을 이어 갈 수 있다고 답할 때만 열어요(STT-R26).
 *
 * 들어올 때 한 번만 확인하고 녹음 상태를 구독하지 않아요. 녹음을 끝낸 뒤의 이동은 끝낸 쪽이 정해요.
 * 구독하면 [끝내고 이동]처럼 끝내기와 이동이 함께 일어날 때, 이동이 반영되기 전에 가드가 홈으로 보내
 * 이동을 덮어써요.
 */
export default function RecordingGuard() {
  const { workspaceId } = useParams();
  const [isIdleOnEntry] = useState(
    () => useRecordingStore.getState().status === "idle",
  );
  const [hasResumeProof] = useState(
    () => getRecordingResumeProof()?.workspaceId === Number(workspaceId),
  );

  if (isIdleOnEntry && hasResumeProof) return <ReloadedRecordingGate />;

  if (isIdleOnEntry && workspaceId) {
    return <RedirectToHome workspaceId={workspaceId} />;
  }

  return <Outlet />;
}

/** 새로고침한 탭의 녹음 화면. 이어 받기 전까지 서버의 답을 보고, 이어 갈 수 없는 녹음이면 홈으로 보내요 */
function ReloadedRecordingGate() {
  const { workspaceId } = useParams();
  const { isResumable, isLoading } = useServerRecording();
  const isThisTabIdle = useRecordingStore((state) => state.status === "idle");
  // 한 번 이어 받은 뒤의 이동은 끝낸 쪽이 정해요
  const hasReconnectedRef = useRef(false);
  if (!isThisTabIdle) hasReconnectedRef.current = true;

  if (isLoading) return null;

  if (
    workspaceId &&
    isThisTabIdle &&
    !isResumable &&
    !hasReconnectedRef.current
  ) {
    return <RedirectToHome workspaceId={workspaceId} />;
  }

  return <Outlet />;
}

interface RedirectToHomeProps {
  workspaceId: string;
}

function RedirectToHome({ workspaceId }: RedirectToHomeProps) {
  return (
    <Navigate
      to={getRouterPath({
        routeKey: "WORKSPACE_HOME",
        params: { workspaceId },
      })}
      replace
    />
  );
}
