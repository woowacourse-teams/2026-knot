import type { KeyboardEvent } from "react";

interface UseTabsKeyboardParams<Value extends string> {
  /** 탭의 값 목록. 화살표로 옮길 순서예요 */
  values: readonly Value[];
  /** 지금 고른 탭의 값 */
  value: Value;
  /** 다른 탭을 골랐을 때. 화살표로 옮겨도 바로 골라요 */
  onChange: (value: Value) => void;
}

/**
 * 탭 목록의 키보드 조작. 왼쪽 · 오른쪽 화살표로 옆 탭, Home · End로 처음 · 끝 탭으로 옮기고 바로 골라요.
 *
 * 양 끝에서는 반대쪽 끝으로 이어져요. 옮긴 탭으로 포커스도 함께 보내, 고른 탭만 Tab 키 순서에 들어가는 탭 목록에서 길을 잃지 않게 해요.
 */
export const useTabsKeyboard = <Value extends string>({
  values,
  value,
  onChange,
}: UseTabsKeyboardParams<Value>) => {
  const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const currentIndex = values.indexOf(value);
    const lastIndex = values.length - 1;
    const nextIndexByKey: Record<string, number> = {
      ArrowRight: currentIndex === lastIndex ? 0 : currentIndex + 1,
      ArrowLeft: currentIndex === 0 ? lastIndex : currentIndex - 1,
      Home: 0,
      End: lastIndex,
    };
    const nextIndex = nextIndexByKey[event.key];

    if (nextIndex === undefined) return;

    event.preventDefault();
    const nextValue = values[nextIndex];
    onChange(nextValue);

    // 옮긴 탭이 아직 포커스를 받을 수 없는 상태(tabIndex -1)여도 직접 포커스를 보내요
    const nextTab =
      event.currentTarget.parentElement?.querySelector<HTMLElement>(
        `[data-value="${nextValue}"]`,
      );
    nextTab?.focus();
  };

  return { handleKeyDown };
};
