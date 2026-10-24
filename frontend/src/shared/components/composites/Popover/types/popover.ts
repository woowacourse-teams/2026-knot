/**
 * 팝오버를 트리거 아래에 띄우고, 트리거의 어느 가장자리에 맞출지. 피그마에 적힌 이름을 그대로 써요.
 *
 * - `bottom-start`: 왼쪽 끝끼리 맞춰요. 왼쪽이나 본문 안의 트리거에 써요.
 * - `bottom-end`: 오른쪽 끝끼리 맞춰요. 화면이나 줄의 오른쪽 끝에 붙은 트리거에 써요.
 *
 * 아래 공간이 모자라면 같은 가장자리를 유지한 채 위로 뒤집혀요.
 */
export type PopoverPlacement = "bottom-start" | "bottom-end";

/**
 * 팝오버를 무엇으로 열지.
 *
 * - `hover`: 트리거에 포인터를 올리면 열고, 트리거와 팝오버 밖으로 나가면 닫아요. 잠깐 보고 마는 정보에 써요.
 * - `click`: 트리거를 누르면 열고, 다시 누르면 닫아요. 안에서 읽을 거리가 많은 패널에 써요.
 *
 * 어느 쪽이든 바깥을 누르거나 ESC를 누르면 닫혀요.
 */
export type PopoverOpenOn = "hover" | "click";

/**
 * 팝오버가 트리거에 붙일 props. 쓰는 쪽은 트리거로 쓸 요소에 `{...triggerProps}`로 그대로 펼쳐요.
 * 안에 무엇이 들었는지는 몰라도 돼요.
 */
export interface PopoverTriggerProps {
  /** 트리거 DOM 요소를 받아 두는 콜백 ref. `button`이든 `div`든 타입이 맞도록 함수로 넘겨요 */
  ref: (node: HTMLElement | null) => void;
  /** `hover`일 때만 있어요 */
  onPointerEnter?: () => void;
  /** `hover`일 때만 있어요 */
  onPointerLeave?: () => void;
  /** `click`일 때만 있어요 */
  onClick?: () => void;
  /** `click`일 때만 있어요. 트리거가 연 팝오버가 열려 있는지 보조기기에 알려요 */
  "aria-expanded"?: boolean;
}

/** 함수로 넘긴 팝오버 내용이 받는 값 */
export interface PopoverContentControls {
  /** 팝오버를 닫아요. `isDismissible`이 꺼져 있어도 닫혀요 */
  close: () => void;
}
