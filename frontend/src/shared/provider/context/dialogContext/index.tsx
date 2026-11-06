import styled from "@emotion/styled";
import useExitAnimation from "@hooks/common/useExitAnimation";
import useReturnFocus from "@hooks/common/useReturnFocus";
import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

interface DialogRenderParams {
  /** 이 모달만 닫아요. 이미 다른 모달로 바뀌었으면 아무 일도 하지 않아요 */
  close: () => void;
}

type DialogRender = (params: DialogRenderParams) => ReactNode;

interface OpenedDialog {
  /** 이 모달을 그리는 함수 */
  render: DialogRender;
  /** 이 모달만 닫는 함수 */
  close: () => void;
}

export interface DialogControls {
  /**
   * 넘긴 함수가 그리는 모달을 띄우고, 그 모달만 닫는 `close`를 돌려줘요.
   * 이미 떠 있으면 그 모달로 바뀌고, 바뀐 뒤에는 이전 모달의 `close`를 불러도 아무 일도 하지 않아요
   */
  open: (render: DialogRender) => () => void;
  /** 누가 띄웠든 떠 있는 모달을 닫아요. 앞 계정의 모달이 남으면 안 되는 로그아웃처럼 화면을 통째로 떠날 때 써요 */
  closeOpenedDialog: () => void;
}

const DialogContext = createContext<DialogControls | null>(null);
const HasDialogContext = createContext(false);

/**
 * 지금 떠 있는 모달 하나를 들고 있어요.
 *
 * - `dialog`: 지금 떠 있는 모달을 그리는 함수(`render`)와 그 모달만 닫는 함수(`close`). 떠 있는 모달이 없으면 `null`이에요
 * - `show`: 넘긴 모달을 띄우고 그 모달의 `close`를 돌려줘요. 이미 떠 있으면 그 자리의 모달이 새 모달로 바뀌어요
 *
 * 모달을 하나만 들고 있어서, 떠 있는 동안 `show`를 부르면 두 모달이 겹치지 않고
 * 원래 모달 자리에 새 모달이 대신 들어가요.
 * `close`는 모달마다 따로 만들어져서, 뒤늦게 불린 이전 모달의 `close`가 지금 모달을 닫지 않아요.
 */
const useOpenedDialog = () => {
  const [openedDialog, setOpenedDialog] = useState<OpenedDialog | null>(null);

  const show = useCallback((render: DialogRender) => {
    const nextDialog: OpenedDialog = {
      render,
      // 그사이 다른 모달로 바뀌었으면 그 모달은 건드리지 않아요
      close: () =>
        setOpenedDialog((current) => (current === nextDialog ? null : current)),
    };

    setOpenedDialog(nextDialog);

    return nextDialog.close;
  }, []);

  const closeOpenedDialog = useCallback(() => setOpenedDialog(null), []);

  return { dialog: openedDialog, show, closeOpenedDialog };
};

/**
 * 어느 화면에서든 `useDialog`로 모달을 띄울 수 있게 해 주는 프로바이더.
 *
 * 지금 떠 있는 모달 하나만 들고 있고, 무엇을 어떻게 그릴지는 모르므로
 * 모달의 종류나 겉모양이 바뀌어도 이 파일은 바뀌지 않아요.
 * 어떤 모달이 떠 있는지는 `useOpenedDialog`가, 모두 닫히면 처음 모달을 열기 전
 * 포커스 자리로 돌려주는 일은 `useReturnFocus`가 맡고, 여기서는 둘을 이어 주기만 해요.
 *
 * 닫힌 모달은 `useExitAnimation`이 닫힘 애니메이션이 끝날 때까지 조금 더 그려 둬요.
 * 그동안 감싼 요소에 `data-overlay-state="closing"`을 붙이고, 어떻게 사라질지는 이 표시를 읽는 `Dim`과 카드(`Pop`)가 정해요.
 * 사라지는 모달은 `inert`로 막아서 버튼이 클릭이나 키보드로 다시 눌리지 않아요.
 */
export function DialogProvider({ children }: { children: ReactNode }) {
  const { dialog, show, closeOpenedDialog } = useOpenedDialog();
  const { remember } = useReturnFocus({ isActive: dialog !== null });
  const dialogLayerRef = useRef<HTMLDivElement>(null);
  const { renderedValue: renderedDialog, isExiting } = useExitAnimation({
    value: dialog,
    ref: dialogLayerRef,
  });

  const controlsValue = useMemo<DialogControls>(
    () => ({
      open: (nextRender) => {
        remember();

        return show(nextRender);
      },
      closeOpenedDialog,
    }),
    [closeOpenedDialog, remember, show],
  );

  return (
    <DialogContext.Provider value={controlsValue}>
      <HasDialogContext.Provider value={dialog !== null}>
        {children}
        {renderedDialog && (
          <DialogLayer
            ref={dialogLayerRef}
            data-overlay-state={isExiting ? "closing" : "open"}
            inert={isExiting}
          >
            {renderedDialog.render({ close: renderedDialog.close })}
          </DialogLayer>
        )}
      </HasDialogContext.Provider>
    </DialogContext.Provider>
  );
}

// 닫힘 표시를 붙이고 애니메이션을 재는 기준만 맡아서, 화면 배치에는 끼어들지 않아요
const DialogLayer = styled.div`
  display: contents;
`;

/**
 * 모달을 여는 함수를 돌려줘요. `DialogProvider` 안에서만 부를 수 있어요.
 *
 * 닫을 때는 모달 안에서는 `open`이 넘겨주는 `close`를, 모달 밖에서는 `open`이 돌려주는 `close`를 써요.
 * 둘은 같은 함수이고 자기 모달만 닫아요.
 *
 * @example
 * const { open } = useDialog();
 *
 * open(({ close }) => (
 *   <Dim onClick={close}>
 *     <ConfirmDialog
 *       role="dialog"
 *       aria-modal="true"
 *       aria-label="녹음을 끝낼까요?"
 *       onClick={(e) => e.stopPropagation()}
 *       title="녹음을 끝낼까요?"
 *       description="끝낸 녹음은 다시 이어 갈 수 없어요."
 *       cancelLabel="계속 녹음"
 *       confirmLabel="녹음 끝내기"
 *       onCancel={close}
 *       onConfirm={() => {
 *         close();
 *         endRecording();
 *       }}
 *     />
 *   </Dim>
 * ));
 *
 * @example
 * // 모달 밖에서 닫기: `open`이 돌려준 `close`를 들고 있다가 불러요
 * const { open } = useDialog();
 *
 * const close = open(({ close }) => (
 *   <Dim>
 *     <AlertDialog
 *       role="alertdialog"
 *       aria-modal="true"
 *       aria-label="녹음을 저장하고 있어요"
 *       title="녹음을 저장하고 있어요"
 *       description="저장이 끝나면 저절로 닫혀요."
 *       confirmLabel="확인"
 *       onConfirm={close}
 *     />
 *   </Dim>
 * ));
 *
 * await saveRecording();
 * close();
 */
export const useDialog = () => {
  const controls = useContext(DialogContext);

  if (controls === null) {
    throw new Error(
      "useDialog는 DialogProvider 안에서만 쓸 수 있어요. DialogProvider를 앱 최상단에 넣어 주세요.",
    );
  }

  return controls;
};

/**
 * 지금 떠 있는 모달이 있는지. 닫히는 애니메이션 중인 모달은 치지 않아요.
 * 모달 뒤에 열어 둔 메뉴처럼, 모달을 누를 때 바깥 누름으로 닫히면 안 되는 것이 막을 때 써요.
 * `useDialog`와 나눠서, 모달을 띄우기만 하는 곳은 모달이 열리고 닫혀도 다시 그리지 않아요. Provider 밖에서는 늘 `false`예요.
 */
export const useHasDialog = () => useContext(HasDialogContext);
