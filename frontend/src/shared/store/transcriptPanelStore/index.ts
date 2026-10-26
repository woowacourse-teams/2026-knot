import { create } from "zustand";

interface ChangeIsOpenParams {
  /** 버튼을 누른 회원. 회원 정보를 받지 못했으면 없어요 */
  memberId?: number;
  /** 버튼을 눌러 정한 열림 여부 */
  isOpen: boolean;
}

interface SaveIsOpenForNextTimeParams {
  memberId: number;
  isOpen: boolean;
}

interface RecordingDocumentChoice {
  /** 버튼을 누른 녹음 문서 화면. 화면에 들어올 때마다 바뀌는 방문 기록의 키예요 */
  visitKey: string;
  /** 그 화면에서 버튼을 눌러 정한 열림 여부 */
  isOpen: boolean;
}

interface TranscriptPanelState {
  /**
   * 이번 방문에서 원문 버튼을 눌러 정한 열림 여부.
   * 아직 한 번도 누르지 않았으면 `null`이에요. 새로고침하면 다시 `null`이 돼요
   */
  isOpenThisVisit: boolean | null;
  /**
   * 녹음 문서에서 원문 버튼을 눌러 정한 열림 여부. 아직 누르지 않았으면 `null`이에요.
   * 녹음 문서는 열 때마다 원문을 열어 두므로(STT-R39), 문서 보기의 값과 따로 담고 브라우저에 남기지 않아요
   */
  recordingDocumentChoice: RecordingDocumentChoice | null;
}

interface TranscriptPanelActions {
  /** 열림 여부를 바꿔요. 이번 방문의 값으로 담고, 회원을 알면 다음번에도 쓰도록 브라우저에 남겨요 */
  changeIsOpen: (params: ChangeIsOpenParams) => void;
  /** 녹음 문서 화면에서 정한 열림 여부를 담아요. 문서 보기의 값과 브라우저에 남긴 값은 바꾸지 않아요 */
  changeRecordingDocumentIsOpen: (choice: RecordingDocumentChoice) => void;
}

/** 지난번 열림 여부를 브라우저에 남겨 두는 키의 앞부분. 회원마다 따로 남기도록 뒤에 회원 id를 붙여요 */
const IS_OPEN_LAST_TIME_KEY_PREFIX = "knot.transcriptPanelOpen";

/** 회원의 지난번 열림 여부를 브라우저에 남겨 두는 키. 스토리가 남긴 값을 지울 때도 써요 */
export const getIsOpenLastTimeKey = (memberId: number) =>
  `${IS_OPEN_LAST_TIME_KEY_PREFIX}.${memberId}`;

/**
 * 회원이 지난번에 마지막으로 둔 열림 여부를 브라우저에서 읽어요. 문서마다가 아니라 회원마다 하나예요.
 *
 * 남긴 값이 없으면 문서를 처음 보는 회원이라 열림이에요(DOC-R3). 읽을 수 없을 때도 열림으로 봐요.
 */
export const readIsOpenLastTime = (memberId: number) => {
  try {
    return localStorage.getItem(getIsOpenLastTimeKey(memberId)) !== "false";
  } catch {
    return true;
  }
};

/** 다음번에 `readIsOpenLastTime`이 읽을 수 있게, 방금 정한 열림 여부를 브라우저에 남겨요 */
const saveIsOpenForNextTime = ({
  memberId,
  isOpen,
}: SaveIsOpenForNextTimeParams) => {
  try {
    localStorage.setItem(getIsOpenLastTimeKey(memberId), String(isOpen));
  } catch {
    // 남길 수 없으면 이번 방문에만 적용해요
  }
};

/**
 * 문서 보기 · 녹음 문서 화면의 원문 패널을 열어 둘지 담는 전역 저장소.
 *
 * 여닫는 버튼은 GNB에, 패널은 화면 오른쪽 자리에 있어 서로 다른 구획이라 한곳에 두고 함께 읽어요.
 *
 * 여기에는 이번 방문에서 버튼으로 정한 값만 담아요. 버튼을 누르기 전의 값(회원이 지난번에 둔 상태)은
 * `@hooks/domain/document/useTranscriptPanel`이 회원을 알아낸 뒤 `readIsOpenLastTime`으로 읽어요.
 *
 * 화면은 도메인 훅으로 읽고, 테스트는 이번 방문의 값이 테스트끼리 새지 않도록 여기서 직접 되돌려요.
 */
export const useTranscriptPanelStore = create<
  TranscriptPanelState & TranscriptPanelActions
>()((set) => ({
  isOpenThisVisit: null,
  recordingDocumentChoice: null,

  changeIsOpen: ({ memberId, isOpen }) => {
    if (memberId !== undefined) saveIsOpenForNextTime({ memberId, isOpen });

    set({ isOpenThisVisit: isOpen });
  },

  changeRecordingDocumentIsOpen: (choice) => {
    set({ recordingDocumentChoice: choice });
  },
}));
