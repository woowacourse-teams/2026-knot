import styled from "@emotion/styled";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useState } from "react";
import { MemoryRouter, Navigate, Route, Routes } from "react-router";

import DockColumn, {
  DOCK_BOTTOM,
  DOCK_HEIGHT,
  DOCK_TOAST_GAP,
} from "@primitives/layout/DockColumn";
import Button from "@primitives/ui/Button";
import { TOAST_MESSAGES } from "@constants/toastMessages";

import { ToastProvider, ToastViewport, usePinnedToast, useToast } from ".";

const FOUR_IN_A_ROW_INTERVAL_MS = 400;
const THREE_QUICKLY_INTERVAL_MS = 100;

/**
 * 앱 최상단에서 `useToast().show`로 토스트를 띄우게 해 주는 프로바이더예요. 독 유무가 바뀌면 이전 알림을 정리해요.
 *
 * **쌓이는 규칙**
 * - 가장 최근 토스트가 맨 아래(독에 가장 가까이)에 쌓이고, 떠 있는 토스트는 최대 3개예요.
 *   4번째가 오면 가장 오래된 토스트가 모션 없이 바로 빠져요.
 * - 종류·문구가 모두 같은 토스트가 떠 있으면 새로 쌓지 않고 떠 있는 시간만 처음부터 다시 세요.
 * - 사라지는 중인 토스트는 이미 끝난 알림이라 3개에 세지 않아요. 그동안 같은 토스트를 띄우면 새로 쌓여요.
 *   그래서 잠깐 최대 4개(떠 있는 3개 + 사라지는 1개)가 보일 수 있어요.
 *
 * **고정 토스트**
 * - 끝날 때까지 알려야 하는 안내는 `usePinnedToast`로 띄워요. 예: 녹음 중 연결 끊김
 * - 시간이 지나도 사라지지 않고, X를 누르거나 띄운 쪽이 값을 없애면 닫혀요.
 * - 일반 토스트가 쌓여 4번째가 와도 밀려나지 않고, 대신 가장 오래된 일반 토스트가 빠져요. 고정 토스트도 떠 있는 3개에 세요.
 * - 값이 바뀌면 쌓지 않고 문구를 바꿔요. X로 닫은 뒤 값이 그대로면 다시 띄우지 않고, 값이 바뀌면 다시 띄워요.
 *
 * **떠 있는 시간과 모션**
 * - 정상 5초, 주의·오류 8초 동안 떠 있어요. 주의·오류는 읽고 대처할 시간이 더 필요해서예요.
 * - 나타날 때 0.26초 동안 아래에서 살짝 떠오르며 칸이 펼쳐지고, 사라질 때 0.25초 동안 흐려지며 칸이 접혀요.
 *   여러 개가 잇달아 떠도 위의 토스트가 멈칫하지 않고 한 덩어리로 밀려 올라가고, 칸이 접히는 만큼 부드럽게 내려와요.
 *
 * **위치**
 * - 독이 있는 화면에서는 독 바로 위 16px에 쌓여요. 독 위에 `ToastViewport placement="inline"`을 둬요.
 * - 독이 없는 화면(로그인·온보딩 등)에서는 화면 하단 가운데, 독이 있는 화면의 토스트와 같은 높이(바닥에서 104px = 독 아래 28 + 접힌 독 60 + 간격 16)에 떠요.
 * - 토스트는 모달보다 위에 뜨고, 뒤 화면의 클릭을 가리지 않아요. 고정 토스트의 X만 클릭을 받아요.
 * - 문구가 길면 독 폭에서 줄을 바꿔요.
 *
 * **화면이 바뀌면**
 * - 독이 있는 화면 ↔ 없는 화면 사이를 이동하면 고정 토스트만 남기고 이전 알림을 지워요. 독 유무가 같으면 남아요.
 * - 화면을 옮기며 알릴 때는 `navigateWithToast`를 써요. 도착한 화면에서 토스트가 떠요. 예: 로그인 만료 → 로그인 화면
 */
const meta = {
  title: "Shared/ToastProvider",
  component: ToastProvider,
  parameters: {
    layout: "fullscreen",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1783-13216",
    },
  },
  argTypes: {
    children: { control: false },
  },
  args: {
    children: null,
  },
  decorators: [
    (Story) => (
      <MemoryRouter>
        <StoryScreen>
          <Story />
        </StoryScreen>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof ToastProvider>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 버튼으로 토스트를 띄워 쌓이는 규칙과 나타나고 사라지는 모션을 직접 확인해요. 독이 없는 화면처럼 하단 가운데에 떠요 */
export const Playground: Story = {
  render: () => (
    <ToastProvider>
      <ToastPlayground />
    </ToastProvider>
  ),
};

/** 독이 있는 화면이에요. 토스트가 독 바로 위에 쌓여요. 예: 워크스페이스 홈·탐색 */
export const AboveDock: Story = {
  render: () => (
    <ToastProvider hasDock>
      <ToastPlayground />
      <DockContainer>
        <ToastViewport placement="inline" />
        <DockStandIn aria-hidden />
      </DockContainer>
    </ToastProvider>
  ),
};

/** 시간이 지나도 사라지지 않고 X로 닫는 토스트예요. 일반 토스트를 더 띄워도 밀려나지 않아요. 예: 녹음 중 연결 끊김 */
export const Pinned: Story = {
  render: () => (
    <ToastProvider>
      <ToastPlayground />
      <PinnedToastPlayground />
    </ToastProvider>
  ),
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6083-27373",
    },
  },
};

/** 도착 화면이 즉시 다른 화면으로 보내도 최종 도착 화면을 유지해요. */
export const ImmediateRedirect: Story = {
  render: () => (
    <ToastProvider>
      <Routes>
        <Route path="/" element={<RedirectTrigger />} />
        <Route path="/guarded" element={<Navigate to="/target" replace />} />
        <Route path="/target" element={<p>최종 도착 화면</p>} />
      </Routes>
    </ToastProvider>
  ),
};

function RedirectTrigger() {
  const { navigateWithToast } = useToast();

  return (
    <Button
      size="sm"
      variant="accent"
      onClick={() =>
        navigateWithToast({
          to: "/guarded",
          toast: TOAST_MESSAGES.COPY_FAILED,
        })
      }
    >
      토스트와 함께 이동
    </Button>
  );
}

function PinnedToastPlayground() {
  const [toast, setToast] = useState<
    | typeof TOAST_MESSAGES.RECORDING_DISCONNECTED
    | typeof TOAST_MESSAGES.RECORDING_ENDING_DISCONNECTED
    | null
  >(null);

  usePinnedToast({ pinKey: "story", toast });

  return (
    <ButtonContainer>
      <Button
        size="sm"
        variant="outline"
        onClick={() => setToast(TOAST_MESSAGES.RECORDING_DISCONNECTED)}
      >
        고정 토스트 띄우기
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() => setToast(TOAST_MESSAGES.RECORDING_ENDING_DISCONNECTED)}
      >
        문구 바꾸기
      </Button>
      <Button size="sm" variant="outline" onClick={() => setToast(null)}>
        값 없애기
      </Button>
    </ButtonContainer>
  );
}

function ToastPlayground() {
  const { show } = useToast();

  const showInARow = (
    toasts: Parameters<typeof show>[0][],
    intervalMs: number,
  ) => {
    toasts.forEach((toast, index) => {
      setTimeout(() => show(toast), index * intervalMs);
    });
  };

  return (
    <ButtonContainer>
      <Button
        size="sm"
        variant="accent"
        onClick={() => show(TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED)}
      >
        정상 띄우기
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() => show(TOAST_MESSAGES.RECORDING_CONNECTION_LOST)}
      >
        주의 띄우기
      </Button>
      <Button
        size="sm"
        variant="danger"
        onClick={() => show(TOAST_MESSAGES.QUESTION_SEND_FAILED)}
      >
        오류 띄우기
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() => show(TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED)}
      >
        같은 토스트 다시 띄우기
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() =>
          showInARow(
            [
              TOAST_MESSAGES.ALL_DOCUMENTS_CONFIRMED,
              TOAST_MESSAGES.COPY_FAILED,
              TOAST_MESSAGES.NO_DOCUMENT_CONTENT,
              TOAST_MESSAGES.WORKSPACE_CHANGED,
            ],
            FOUR_IN_A_ROW_INTERVAL_MS,
          )
        }
      >
        4개 연달아 띄우기
      </Button>
      <Button
        size="sm"
        variant="outline"
        onClick={() =>
          showInARow(
            [
              TOAST_MESSAGES.COPY_FAILED,
              TOAST_MESSAGES.NO_DOCUMENT_CONTENT,
              TOAST_MESSAGES.WORKSPACE_CHANGED,
            ],
            THREE_QUICKLY_INTERVAL_MS,
          )
        }
      >
        서로 다른 문구 3개 빠르게
      </Button>
    </ButtonContainer>
  );
}

// 토스트가 하단에 고정으로 떠서, 토스트 4개(떠 있는 3개 + 사라지는 1개)가 버튼과 겹치지 않을 만큼 높이를 둬요.
// Docs의 스토리 블록은 transform이 걸려 있어 고정 위치가 화면이 아니라 이 블록의 아래를 기준으로 잡혀요
const StoryScreen = styled.div`
  box-sizing: border-box;
  min-height: 32rem; /* 512px */
  padding: 1.5rem;
`;

// 워크스페이스 레이아웃의 독 자리(DockSlot)와 같은 배치예요. 하단 기준은 토스트 목록처럼 이 블록을 따라요
const DockContainer = styled(DockColumn)`
  position: fixed;
  right: 0;
  bottom: ${DOCK_BOTTOM};
  left: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: ${DOCK_TOAST_GAP};
  pointer-events: none;
`;

// 독은 워크스페이스 데이터가 필요한 위젯이라, 접힌 독의 크기만 흉내 내요
const DockStandIn = styled.div`
  width: 6.75rem; /* 108px */
  height: ${DOCK_HEIGHT};
  border-radius: 6.25rem; /* 100px */
  background-color: ${({ theme }) => theme.neutral[800]};
`;

const ButtonContainer = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem; /* 8px */
`;
