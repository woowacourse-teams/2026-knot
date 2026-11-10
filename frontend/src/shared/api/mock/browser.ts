import { focusManager } from "@tanstack/react-query";
import { setupWorker } from "msw/browser";

import { handlers } from "./handlers";
import { devAuthHandlers } from "./handlers/dev";
import { deploymentCompatibilityHandlers } from "./handlers/dev/deploymentCompatibility";
import {
  applyMockScenarioFromQuery,
  CURRENT_RECORDING_SCENARIOS,
  devScenarioHandlers,
  readMockScenario,
  writeMockScenario,
  type CurrentRecordingScenario,
} from "./handlers/dev/scenario";

// 개발 서버 전용. src/index.tsx가 API_MOCKING 플래그 안에서 동적 import해요
// 먼저 맞는 핸들러가 이기므로, 개발 전용 핸들러(쿠키 로그인 판정 me·nickname·refresh·초대 참여)를
// 앞에 둬 기본 핸들러만 덮어요.
// vitest(server.ts)에는 넣지 않아요
export const mockWorker = setupWorker(...devAuthHandlers, ...deploymentCompatibilityHandlers, ...devScenarioHandlers, ...handlers);

/** 워커의 답을 기다리는 가장 긴 시간(ms). 멈춰 있던 워커도 실제로는 몇 ms 안에 답해요 */
const MOCK_WORKER_REPLY_TIMEOUT_MS = 1000;

// public/mockServiceWorker.js가 주고받는 메시지 이름이에요. msw를 올리면 그 파일에서 다시 확인해야 해요
const MOCK_ACTIVATE_MESSAGE = "MOCK_ACTIVATE";
const MOCKING_ENABLED_MESSAGE = "MOCKING_ENABLED";

const isMockingEnabledMessage = (data: unknown) =>
  typeof data === "object" &&
  data !== null &&
  "type" in data &&
  data.type === MOCKING_ENABLED_MESSAGE;

/**
 * mock 서비스 워커에 이 창을 다시 등록하고, 워커가 답할 때까지 기다려요.
 *
 * 워커가 이 페이지를 맡고 있지 않거나 답이 늦으면 기다리지 않아요. 다시 조회가
 * 영영 나가지 않는 것보다 mock 없이 나가서 실패 안내가 뜨는 편이 나아요.
 */
const reactivateMockWorker = () =>
  new Promise<void>((resolve) => {
    const { serviceWorker } = navigator;
    const { controller } = serviceWorker;

    if (controller === null) {
      resolve();

      return;
    }

    const finish = () => {
      clearTimeout(timeoutId);
      serviceWorker.removeEventListener("message", handleMessage);
      resolve();
    };

    const handleMessage = (event: MessageEvent<unknown>) => {
      if (isMockingEnabledMessage(event.data)) finish();
    };

    const timeoutId = setTimeout(finish, MOCK_WORKER_REPLY_TIMEOUT_MS);

    serviceWorker.addEventListener("message", handleMessage);
    controller.postMessage(MOCK_ACTIVATE_MESSAGE);
  });

/**
 * 창이 다시 보일 때 mock 서비스 워커를 다시 활성화하고, 그 뒤에 창 포커스 다시 조회가 나가게 해요.
 *
 * 워커는 mock을 켠 창의 목록을 메모리에만 둬요. 창을 떠나 있는 동안 브라우저가 쉬는 워커를
 * 끝냈다가 다시 띄우면 목록이 비어, 워커가 모든 요청을 그대로 통과시켜요. 그러면 요청이
 * 개발 서버로 가서 404를 받고, 새로고침하기 전까지 화면마다 실패 안내가 떠요.
 *
 * 다시 등록하라고 보내기만 해서는 부족해요. 워커가 등록을 끝내기 전에 같은 순간의
 * 다시 조회가 먼저 도착해 통과돼요. 그래서 워커의 답을 받은 뒤에 다시 조회를 내보내요.
 *
 * 다시 조회 알림만 늦추지 않고, 창이 보이는지에 대한 값 자체를 답을 받을 때까지 「안 보임」으로 둬요.
 * 일정 간격 조회는 알림을 기다리지 않고 이 값만 보고 나가서, 값이 먼저 「보임」이 되면
 * 창이 다시 보이는 순간에 밀려 있던 조회가 워커의 답보다 먼저 나가요.
 *
 * 창이 가려져 있는 동안에도 나가는 요청(녹음 생존 신호 등)은 여기서 다루지 않아요.
 */
export const reactivateMockWorkerOnFocus = () => {
  focusManager.setEventListener((handleFocus) => {
    const handleVisibilityChange = () => {
      // 가려질 때는 다시 조회가 나가지 않으니 워커를 깨우지 않고 알리기만 해요
      if (document.visibilityState === "hidden") {
        handleFocus(false);

        return;
      }

      // 기다리는 사이 창이 다시 가려졌을 수 있어, 답을 받은 때의 상태를 알려요
      void reactivateMockWorker().then(() =>
        handleFocus(document.visibilityState !== "hidden"),
      );
    };

    window.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      window.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  });
};

declare global {
  interface Window {
    /** 개발 브라우저에서 mock 상황을 바꾸는 손잡이. 콘솔에서 `knotMock.help()`를 쳐 보세요 */
    knotMock?: {
      help: () => void;
      currentRecording: (scenario: CurrentRecordingScenario) => void;
    };
  }
}

/**
 * 개발 브라우저에서 mock 상황을 바꾸는 손잡이를 콘솔에 둬요.
 *
 * 주소에 `?mock.currentRecording=paused`를 붙이거나 콘솔에서 `knotMock.currentRecording("paused")`를 부르면
 * 저장한 뒤 새로고침해 화면 전체가 그 상황으로 다시 그려져요. 고를 수 있는 값은 `knotMock.help()`가 알려 줘요.
 */
export const installMockScenarioSwitch = () => {
  if (applyMockScenarioFromQuery(window.location.search)) {
    const url = new URL(window.location.href);
    [...url.searchParams.keys()].filter((key) => key.startsWith("mock.")).forEach((key) => url.searchParams.delete(key));
    window.history.replaceState(null, "", url);
  }

  const help = () => {
    console.info(
      [
        "knot mock 상황 바꾸기",
        `- 지금: ${JSON.stringify(readMockScenario())}`,
        `- knotMock.currentRecording(${CURRENT_RECORDING_SCENARIOS.map((s) => `"${s}"`).join(" | ")})`,
        "- 주소에 ?mock.currentRecording=paused 처럼 붙여도 돼요",
      ].join("\n"),
    );
  };
  window.knotMock = {
    help,
    currentRecording: (scenario) => {
      writeMockScenario({ currentRecording: scenario });
      window.location.reload();
    },
  };
  help();
};
