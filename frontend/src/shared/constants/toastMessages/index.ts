import type { ToastControls } from "@provider/context/toastContext";

/**
 * 현재 V2 MVP 토스트의 종류와 문구. show(TOAST_MESSAGES.COPY_FAILED)처럼 사용해요.
 *
 * 2026-10-07 확인: Notion 공통 UI 규칙 및 Figma 에러 목록·성공 화면.
 * https://app.notion.com/p/3ddb435175228144bce5d8837695c062
 * https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2069-27113
 */
export const TOAST_MESSAGES = {
  /** 전체 문서 생성 성공 시. Figma 홈/문서 정리 완료 (1943:6015) */
  DOCUMENT_CREATED: {
    variant: "success",
    message: "문서가 만들어졌어요",
  },
  /** 같은 녹음의 문서를 본인이 모두 확인했을 때. Figma 1785:3439 */
  ALL_DOCUMENTS_CONFIRMED: {
    variant: "success",
    message: "문서를 모두 확인했어요",
  },
  /** 워크스페이스 전환 완료 후. Figma 1942:11998 */
  WORKSPACE_CHANGED: {
    variant: "success",
    message: "워크스페이스를 옮겼어요",
  },
  /**
   * 연결 끊김으로 종료된 녹음의 업로드 완료·문서 처리 시작을 확인한 뒤 사용해요.
   * 탭이 열려 있는 동안 생존 신호가 ENDED, CONNECTION_EXPIRED로 답한 경우만 알려요. Figma 1783:13225
   */
  RECORDING_CONNECTION_LOST: {
    variant: "caution",
    message: "연결이 끊겨 녹음이 끝났어요. 여기까지 문서로 정리하고 있어요",
  },
  /** 녹음한 시간이 최대 녹음 시간 15분 전에 닿았을 때 녹음마다 한 번. Figma 2072:26802 */
  RECORDING_TIME_LIMIT_APPROACHING: {
    variant: "caution",
    message:
      "최대 녹음 시간까지 15분 남았어요. 시간이 되면 녹음을 끝내고 문서로 정리해요",
  },
  /** 생존 신호가 이 탭의 녹음 제어를 거절(403 RECORDING_CONTROL_DENIED)해 녹음을 닫을 때. Figma 4826:33497 */
  RECORDING_CONTROL_LOST: {
    variant: "caution",
    message: "이 탭에서 녹음을 이어갈 수 없어 녹음이 끝났어요",
  },
  /** 녹음 파일 업로드를 다시 시도해도 끝내 실패해 오디오를 버리고 홈으로 갈 때. Figma 4826:33502 */
  RECORDING_UPLOAD_FAILED: {
    variant: "caution",
    message: "녹음 파일을 올리지 못해 문서로 정리하지 못했어요",
  },
  /** 로그인했던 탭의 토큰 재발급이 거절돼 로그인 화면에 왔을 때 로그인 화면이 띄워요(`useSessionExpiredToast`). 처음 온 비로그인 사용자에게는 띄우지 않아요. */
  SESSION_EXPIRED: {
    variant: "caution",
    message: "로그인이 만료됐어요. 다시 로그인해 주세요.",
  },
  /** 기기 문제로 종료된 녹음의 저장·문서 처리 시작이 확인된 경우에 사용해요. */
  RECORDING_INTERRUPTED: {
    variant: "caution",
    message:
      "녹음을 이어갈 수 없어 여기까지 저장했어요. 문서로 정리하고 있어요",
  },
  /** 정리 중 화면 밖에서 결과를 알릴 때. 정리 중 화면에서는 결과 화면을 보여줘요. */
  NO_DOCUMENT_CONTENT: {
    variant: "caution",
    message: "문서로 만들 내용이 없었어요",
  },
  /** 질문 접수 전 실패 시. 답변 생성 실패는 답변 자리에서 안내해요. */
  QUESTION_SEND_FAILED: {
    variant: "error",
    message: "질문을 보내지 못했어요. 잠시 후 다시 시도해 주세요.",
  },
  /** 문서 확인 요청이 401·409가 아닌 이유로 실패했을 때. Figma 4826:33512 */
  DOCUMENT_CONFIRM_FAILED: {
    variant: "error",
    message: "확인하지 못했어요. 다시 시도해 주세요.",
  },
  /** 전사·문서 생성 다시 시도가 거절(409·404)이나 401이 아닌 이유로 접수되지 않았을 때(STT-R22). Figma 4826:33517 */
  RETRY_REQUEST_FAILED: {
    variant: "error",
    message: "다시 시도하지 못했어요. 잠시 후 다시 시도해 주세요.",
  },
  /** 나가기 확인 창의 [나가기] 요청이 실패했을 때. 창은 닫지 않아요. Figma 4826:33522 */
  WORKSPACE_LEAVE_FAILED: {
    variant: "error",
    message: "워크스페이스에서 나가지 못했어요. 다시 시도해 주세요.",
  },
  /** owner 나가기 창의 [넘기고 나가기] 요청이 실패했을 때. 창은 닫지 않아요. Figma 4826:33527 */
  OWNERSHIP_TRANSFER_FAILED: {
    variant: "error",
    message: "권한을 넘기지 못했어요. 다시 시도해 주세요.",
  },
  /** 녹음 끝내기 요청이 실패해 녹음을 이어 갈 때. 「끝내고 이동」도 같아요. Figma 3812:892 */
  RECORDING_END_FAILED: {
    variant: "error",
    message:
      "녹음을 끝내지 못했어요. 녹음은 계속되고 있어요. 다시 시도해 주세요.",
  },
  /** 이어서 녹음 요청이 실패해 일시정지로 남을 때. Figma 4826:33507 */
  RECORDING_RESUME_FAILED: {
    variant: "error",
    message: "이어서 녹음하지 못했어요. 다시 시도해 주세요.",
  },
  COPY_FAILED: {
    variant: "error",
    message: "복사하지 못했어요. 다시 시도해 주세요.",
  },
  /** 피드백 패널에서 보낸 피드백이 접수함에 저장됐을 때(FEEDBACK-R8). Figma 4694:48682 */
  FEEDBACK_SENT: {
    variant: "success",
    message: "피드백을 보냈어요. 팀이 평일에 모아서 확인해요",
  },
  WORKSPACE_ACCESS_DENIED: {
    variant: "error",
    message: "이 워크스페이스에 더 이상 들어갈 수 없어요.",
  },
} as const satisfies Record<string, Parameters<ToastControls["show"]>[0]>;
