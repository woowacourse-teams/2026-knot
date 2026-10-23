import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";

import useKeyDown from "@hooks/common/useKeyDown";
import useTimeout from "@hooks/common/useTimeout";

import type {
  PopoverOpenOn,
  PopoverPlacement,
  PopoverTriggerProps,
} from "../types/popover";
import { getPopoverPosition } from "../utils/getPopoverPosition";

/** 포인터가 트리거와 팝오버 사이 8px 틈을 지나는 동안 닫지 않고 기다리는 시간 */
const CLOSE_DELAY_MS = 150;

interface UsePopoverParams {
  /** 트리거의 어느 가장자리에 맞출지 */
  placement: PopoverPlacement;
  /** 무엇으로 열지 */
  openOn: PopoverOpenOn;
  /** 처음 그릴 때부터 열어 둘지 */
  isInitiallyOpen: boolean;
  /** 열릴 때마다 부를 함수 */
  onOpen?: () => void;
  /** 트리거 · 바깥 누름 · ESC · 스크롤 · 창 크기 변화로 닫을지 */
  isDismissible: boolean;
}

/**
 * 트리거로 팝오버를 여닫는 상태. `openOn`이 `hover`면 포인터를 올려 열고, `click`이면 눌러서 열어요.
 *
 * 팝오버는 `body`에 따로 그려져서 DOM으로는 트리거 밖에 있어요.
 * 그래서 `hover`일 때는 트리거(`triggerProps`)와 내용(`contentProps`) 양쪽에 같은 여닫기 함수를 붙이고, 벗어나면 바로 닫지 않고
 * {@link CLOSE_DELAY_MS}만큼 기다려요. 그 사이 다른 쪽에 들어오면 닫기를 취소해요.
 * 바깥 누름도 같은 이유로 트리거와 내용 둘 다의 밖을 눌렀을 때만 닫아요.
 *
 * 위치는 팝오버를 그린 뒤 크기를 재서 정해요. 크기를 알아야 위로 뒤집을지 정할 수 있어서예요.
 * 열려 있는 동안 트리거를 품은 영역이 스크롤되거나 창 크기가 바뀌면 트리거와 자리가 어긋나므로 닫아요.
 * 내용 안이나 트리거와 관계없는 영역이 스크롤될 때는 닫지 않아요.
 * 열린 동안 내용의 크기가 바뀌면(메뉴에서 패널로 바뀌는 등) 그 크기로 자리를 다시 맞춰요.
 *
 * `isDismissible`이 꺼져 있는 동안에는 위 방법으로 닫지 않고, 내용이 `close`를 불러야만 닫혀요.
 * 닫힐 때 포커스가 내용 안에 있었거나 갈 곳 없이 비어 있으면 트리거로 돌려줘요. 내용이 사라지면서 포커스를 잃지 않게요.
 */
const usePopover = ({
  placement,
  openOn,
  isInitiallyOpen,
  onOpen,
  isDismissible,
}: UsePopoverParams) => {
  const [isOpen, setIsOpen] = useState(isInitiallyOpen);
  const [position, setPosition] = useState({ top: 0, left: 0 });

  const triggerRef = useRef<HTMLElement | null>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  const { start: startCloseTimer, clear: clearCloseTimer } = useTimeout({
    timeout: CLOSE_DELAY_MS,
    callback: () => setIsOpen(false),
  });

  // 렌더링마다 새 함수가 되면 React가 ref를 떼었다 다시 붙이므로 한 번만 만들어요
  const setTriggerRef = useCallback((node: HTMLElement | null) => {
    triggerRef.current = node;
  }, []);

  const close = () => {
    clearCloseTimer();

    const { activeElement } = document;
    if (
      activeElement === document.body ||
      contentRef.current?.contains(activeElement)
    ) {
      triggerRef.current?.focus();
    }

    setIsOpen(false);
  };

  const dismiss = () => {
    if (isDismissible) close();
  };

  const handlePointerEnter = () => {
    clearCloseTimer();
    setIsOpen(true);
  };

  const handlePointerLeave = () => {
    startCloseTimer();
  };

  const handleClick = () => {
    if (isOpen) dismiss();
    else setIsOpen(true);
  };

  useKeyDown({
    key: "Escape",
    isEnabled: isOpen && isDismissible,
    onKeyDown: close,
  });

  // 여기서 바꾼 좌표는 브라우저가 그리기 전에 반영돼요. 그래서 이전 좌표나 (0, 0)에 잠깐 보이지 않아요
  useLayoutEffect(() => {
    if (!isOpen || triggerRef.current === null || contentRef.current === null) {
      return;
    }

    const trigger = triggerRef.current;
    const content = contentRef.current;

    const updatePosition = () => {
      const { clientWidth, clientHeight } = document.documentElement;

      setPosition(
        getPopoverPosition({
          triggerRect: trigger.getBoundingClientRect(),
          popoverSize: content.getBoundingClientRect(),
          // 스크롤바를 뺀, 실제로 보이는 영역 기준으로 가장자리를 재요
          viewport: { width: clientWidth, height: clientHeight },
          placement,
        }),
      );
    };

    updatePosition();

    const resizeObserver = new ResizeObserver(updatePosition);
    resizeObserver.observe(content);

    return () => resizeObserver.disconnect();
  }, [isOpen, placement]);

  // 열린 순간에만 불러요. 쓰는 쪽이 렌더링마다 새 onOpen을 넘겨도 다시 부르지 않아요
  useEffect(() => {
    if (isOpen) onOpen?.();
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;

    const handleScroll = (event: Event) => {
      // 트리거를 품은 영역이 스크롤될 때만 트리거가 움직여요. 페이지 스크롤이면 대상이 document예요
      if (!(event.target instanceof Node)) return;
      if (!event.target.contains(triggerRef.current)) return;

      dismiss();
    };

    // 트리거를 누를 때도 닫으면 이어지는 click이 다시 열어 버려서, 트리거 안은 바깥으로 보지 않아요
    const handlePointerDown = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return;
      if (triggerRef.current?.contains(event.target)) return;
      if (contentRef.current?.contains(event.target)) return;

      dismiss();
    };

    // 캡처 단계에서 들어야 페이지뿐 아니라 안쪽 스크롤 영역의 스크롤도 잡혀요
    window.addEventListener("scroll", handleScroll, true);
    window.addEventListener("resize", dismiss);
    document.addEventListener("pointerdown", handlePointerDown);

    return () => {
      window.removeEventListener("scroll", handleScroll, true);
      window.removeEventListener("resize", dismiss);
      document.removeEventListener("pointerdown", handlePointerDown);
    };
  }, [isOpen, isDismissible]);

  /** 트리거와 내용 양쪽에 붙여요. 둘 사이를 오가는 동안에는 닫히지 않아요 */
  const hoverProps = {
    onPointerEnter: handlePointerEnter,
    onPointerLeave: handlePointerLeave,
  };

  const triggerProps: PopoverTriggerProps =
    openOn === "hover"
      ? { ref: setTriggerRef, ...hoverProps }
      : { ref: setTriggerRef, onClick: handleClick, "aria-expanded": isOpen };
  const contentProps = {
    ref: contentRef,
    ...(openOn === "hover" ? hoverProps : {}),
  };

  return { isOpen, position, triggerProps, contentProps, close };
};

export default usePopover;
