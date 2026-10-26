import CenteredLayout from "@pages/_layout/CenteredLayout";
import WorkspaceLayout from "@pages/_layout/WorkspaceLayout";
import InvitePage from "@pages/invite/[token]";
import JoinErrorPage from "@pages/join-error";
import LandingPage from "@pages/landing";
import LoginPage from "@pages/login";
import NotFoundPage from "@pages/not-found";
import OnboardingPage from "@pages/onboarding";
import OnboardingCompletePage from "@pages/onboarding/complete";
import WorkspacePage from "@pages/workspace";
import WorkspaceHomePage from "@pages/workspace/[workspaceId]";
import ChatPage from "@pages/workspace/[workspaceId]/chat";
import DocumentsPage from "@pages/workspace/[workspaceId]/documents";
import DocumentPage from "@pages/workspace/[workspaceId]/documents/[documentId]";
import WorkspaceInvitePage from "@pages/workspace/[workspaceId]/invite";
import WorkspaceJoinPage from "@pages/workspace/[workspaceId]/join";
import RecordingPage from "@pages/workspace/[workspaceId]/recording";
import RecordingDocumentsPage from "@pages/workspace/[workspaceId]/recordings/[recordingId]";
import WorkspaceCodePage from "@pages/workspace/code";
import WorkspaceCreatePage from "@pages/workspace/create";
import RecordingHeartbeat from "@widgets/recording/RecordingHeartbeat";
import WorkspaceAnalytics from "@/shared/components/domain/WorkspaceAnalytics";
import type { RouteObject } from "react-router";

import AuthGuard from "./AuthGuard";
import DocumentsRedirect from "./DocumentsRedirect";
import EntryRedirect from "./EntryRedirect";
import GuestGuard from "./GuestGuard";
import LandingGuard from "./LandingGuard";
import { PATH_ROUTE } from "./PATH_ROUTE";
import RecordingGuard from "./RecordingGuard";

/**
 * 로그인 여부로 세 구역이 나뉘어요.
 *
 * - 누구나: 온보딩, 가입 완료, 초대 링크 판정, 입장 확인, 초대 오류
 * - 로그인한 사람만(`AuthGuard`): 워크스페이스 선택·생성·초대·코드
 * - 로그인하지 않은 사람용(`GuestGuard`): 로그인 화면
 *
 * 온보딩(`/onboarding`)은 닉네임을 아직 정하지 않아 `/api/v1/auth/me`가 401인 상태에서
 * 열어야 하므로 감싸지 않아요. 가입 완료(`/onboarding/complete`)는 방금 등록한 닉네임을
 * 라우터 state로 받아야 열리고, 입장 확인(`/workspace/:workspaceId/join`)도 초대 state와
 * 참여 요청의 401을 스스로 다루므로 자체 검사에 맡깁니다.
 *
 * 워크스페이스 레이아웃 구간은 `useWorkspaceEntry`가 워크스페이스 조회로 401·403·404를
 * 이미 판정하므로 `AuthGuard`를 겹치지 않아요. 겹치면 같은 화면에서 로그인 확인 요청이
 * 한 번 더 나가고 401을 두 곳에서 다루게 됩니다.
 *
 * 가드는 레이아웃 안쪽에 둬요. 판정 중 로딩과 실패 안내가 로고가 있는 자리에서 보여야
 * 화면이 비어 보이지 않기 때문이에요.
 */
export const routes: RouteObject[] = [
  // 홈 — 로그인하지 않은 사람은 화면 전체를 쓰는 랜딩을, 로그인한 사람은 진입 분기를 거쳐 워크스페이스로
  {
    path: PATH_ROUTE.HOME,
    element: <LandingGuard landing={<LandingPage />} />,
    children: [
      {
        element: <CenteredLayout />,
        children: [{ index: true, element: <EntryRedirect /> }],
      },
    ],
  },

  // 화면 가운데 정렬 — 워크스페이스 진입 전 플로우
  {
    element: <CenteredLayout />,
    children: [
      {
        element: <GuestGuard />,
        children: [
          {
            path: PATH_ROUTE.LOGIN,
            element: <LoginPage />,
          },
        ],
      },
      {
        path: PATH_ROUTE.ONBOARDING,
        element: <OnboardingPage />,
      },
      {
        path: PATH_ROUTE.ONBOARDING_COMPLETE,
        element: <OnboardingCompletePage />,
      },
      {
        path: PATH_ROUTE.INVITE,
        element: <InvitePage />,
      },
      {
        path: PATH_ROUTE.WORKSPACE_JOIN,
        element: <WorkspaceJoinPage />,
      },
      {
        path: PATH_ROUTE.JOIN_ERROR,
        element: <JoinErrorPage />,
      },
      {
        element: <AuthGuard />,
        children: [
          {
            path: PATH_ROUTE.WORKSPACE,
            element: <WorkspacePage />,
          },
          {
            path: PATH_ROUTE.WORKSPACE_CREATE,
            element: <WorkspaceCreatePage />,
          },
          {
            path: PATH_ROUTE.WORKSPACE_INVITE,
            element: <WorkspaceInvitePage />,
          },
          {
            path: PATH_ROUTE.WORKSPACE_CODE,
            element: <WorkspaceCodePage />,
          },
        ],
      },
    ],
  },

  // GNB — 워크스페이스 입장 후
  {
    handle: { hasDock: true },
    // 녹음은 레이아웃 안의 어느 화면에서나 이어지므로, 생존 신호가 끊기지 않게 레이아웃째 감싸요.
    // 사용 기록도 화면 코드와 섞이지 않게 레이아웃 바깥에서 감싸요
    element: (
      <RecordingHeartbeat>
        <WorkspaceAnalytics>
          <WorkspaceLayout />
        </WorkspaceAnalytics>
      </RecordingHeartbeat>
    ),
    children: [
      {
        path: PATH_ROUTE.WORKSPACE_HOME,
        element: <WorkspaceHomePage />,
      },
      {
        path: PATH_ROUTE.CHAT,
        element: <ChatPage />,
      },
      {
        path: PATH_ROUTE.CHAT_SESSION,
        element: <ChatPage />,
      },
      {
        element: <RecordingGuard />,
        children: [
          {
            path: PATH_ROUTE.RECORDING,
            element: <RecordingPage />,
          },
        ],
      },
      {
        path: PATH_ROUTE.RECORDING_DOCUMENTS,
        element: <RecordingDocumentsPage />,
      },
      // 탭이 없는 문서 탭 주소는 「녹음별」 탭으로 보내요. 탭 주소는 글자가 고정이라 `:documentId`보다 먼저 맞아요
      {
        path: PATH_ROUTE.DOCUMENTS,
        element: <DocumentsRedirect />,
      },
      {
        path: PATH_ROUTE.DOCUMENTS_BY_RECORDING,
        element: <DocumentsPage />,
      },
      {
        path: PATH_ROUTE.DOCUMENTS_BY_TOPIC,
        element: <DocumentsPage />,
      },
      {
        path: PATH_ROUTE.DOCUMENT,
        element: <DocumentPage />,
      },
    ],
  },

  // 어느 라우트에도 맞지 않는 주소 — 빈 화면 대신 ERR-01. 맞는 라우트가 하나라도 있으면 그쪽이 먼저예요
  {
    path: "*",
    element: <NotFoundPage />,
  },
];
